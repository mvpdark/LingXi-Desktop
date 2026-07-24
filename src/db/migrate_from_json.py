#!/usr/bin/env python
"""将现有 JSON 数据迁移到 PostgreSQL（一次性迁移脚本，幂等，可重复执行）。

迁移内容（数据均来自 src/cache/ 下的 JSON 文件）：
    1. storage/auth/accounts.json        -> users             用户账户
    2. storage/auth/sessions.json        -> sessions          登录 token
    3. storage/billing/baselines.json    -> billing_baselines 计费基线
    4. storage/sessions.json             -> chat_sessions     聊天会话
    5. storage/{session_id}_chat.json    -> chat_messages     聊天消息
    6. storage/memory/profile.json       -> memory_profiles   用户记忆画像
    7. storage/memory/{id}_summary.json  -> memory_summaries  会话记忆摘要
    8. tavily_usage.json                 -> usage_stats       API 用量汇总

特性：
    - 幂等：每类数据先查询是否已存在，存在则跳过并打印日志，可安全重复执行
    - 容错：单个文件缺失或解析失败只打印警告，不中断整体迁移
    - 事务：全部迁移在同一事务中，任一环节异常则整体回滚

用法（需先在 config.yaml 配置 database_url 或设置环境变量 DATABASE_URL）：
    python src/db/migrate_from_json.py
"""

from __future__ import annotations

import asyncio
import json
import sys
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import UUID

# 将项目根目录加入 sys.path（脚本位于 src/db/ 下，向上三级为项目根），
# 保证直接执行 `python src/db/migrate_from_json.py` 时 `src.*` 包可导入
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from src.config import Config
from src.db.models import (
    BillingBaseline,
    ChatMessage,
    ChatSession,
    MemoryProfile,
    MemorySummary,
    Session,
    UsageStat,
    User,
)

# JSON 数据位置（锚定项目根目录，脚本可在任意工作目录下执行）
STORAGE_DIR = PROJECT_ROOT / "src" / "cache" / "storage"
TAVILY_USAGE_FILE = PROJECT_ROOT / "src" / "cache" / "tavily_usage.json"


# ---------------------------------------------------------------------------
# 通用工具
# ---------------------------------------------------------------------------


def create_engine_and_session(
    database_url: str,
) -> tuple[AsyncEngine, async_sessionmaker[AsyncSession]]:
    """创建迁移专用的异步引擎与会话工厂。

    独立于 src.db.database 单独创建：该模块在 import 时即强制要求
    DATABASE_URL 环境变量，不适合一次性脚本按需初始化的场景。
    """
    # 容错：允许配置不带 asyncpg 驱动前缀的连接串，自动补齐
    if database_url.startswith("postgresql://"):
        database_url = database_url.replace(
            "postgresql://", "postgresql+asyncpg://", 1
        )
    elif database_url.startswith("postgres://"):
        database_url = database_url.replace(
            "postgres://", "postgresql+asyncpg://", 1
        )

    engine = create_async_engine(database_url, pool_pre_ping=True)
    session_factory = async_sessionmaker(
        bind=engine, class_=AsyncSession, expire_on_commit=False
    )
    return engine, session_factory


def load_json_file(path: Path) -> Any | None:
    """容错读取 JSON 文件：不存在或解析失败时打印警告并返回 None。"""
    if not path.exists():
        print(f"  [警告] 文件不存在，跳过: {path.relative_to(PROJECT_ROOT)}")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"  [警告] 读取失败，跳过 {path.relative_to(PROJECT_ROOT)}: {exc}")
        return None


def parse_datetime(value: Any) -> datetime | None:
    """将 JSON 中的时间值转换为带时区的 datetime。

    兼容 Unix 时间戳（int/float，项目现有数据格式）与 ISO 8601 字符串；
    无法解析时返回 None（由模型默认值兜底）。
    """
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value, tz=timezone.utc)
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        # ISO 8601 格式（兼容尾部 Z）
        try:
            dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
            return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
        except ValueError:
            pass
        # 数字字符串形式的 Unix 时间戳
        try:
            return datetime.fromtimestamp(float(text), tz=timezone.utc)
        except ValueError:
            return None
    return None


def normalize_images(msg: dict) -> list | None:
    """统一 images 字段为 JSONB 数组格式：字符串包一层数组，无图片返回 None。"""
    images = msg.get("images")
    if isinstance(images, list):
        return images
    if isinstance(images, str) and images:
        return [images]
    image_url = msg.get("image_url")
    if isinstance(image_url, str) and image_url:
        return [image_url]
    return None


async def get_user_by_username(
    session: AsyncSession, username: str
) -> User | None:
    """按用户名查询用户。"""
    result = await session.execute(select(User).where(User.username == username))
    return result.scalar_one_or_none()


async def get_admin_user(session: AsyncSession) -> User | None:
    """获取 admin 用户；不存在时回退到最早创建的用户（首个用户即管理员）。"""
    result = await session.execute(
        select(User).where(User.role == "admin").order_by(User.created_at)
    )
    admin = result.scalars().first()
    if admin is None:
        result = await session.execute(select(User).order_by(User.created_at))
        admin = result.scalars().first()
    return admin


# ---------------------------------------------------------------------------
# 各数据类型迁移函数（均幂等：已存在则跳过）
# ---------------------------------------------------------------------------


async def migrate_users(
    session: AsyncSession, data: dict, admin_username: str
) -> int:
    """迁移用户账户，返回新增数量。

    首个账户或 username 匹配 config.admin_username 的账户设为 admin；
    现有用户默认激活（status='active'）；password_hash/salt 原样复制，
    后续登录时自动升级为 bcrypt。
    """
    count = 0
    for index, (username, info) in enumerate(data.items()):
        if not isinstance(info, dict):
            print(f"  [警告] 账户数据格式异常，跳过: {username}")
            continue
        if not username or len(username) > 50:
            print(f"  [警告] 用户名无效（超长或为空），跳过: {username!r}")
            continue
        # 幂等：用户名已存在则跳过
        if await get_user_by_username(session, username):
            print(f"  跳过已存在用户: {username}")
            continue

        is_admin = index == 0 or username == admin_username
        user = User(
            username=username,
            password_hash=info.get("password_hash", ""),
            salt=info.get("salt", ""),
            balance=Decimal(str(info.get("balance", 0))),
            role="admin" if is_admin else "user",
            status="active",
        )
        created_at = parse_datetime(info.get("created_at"))
        if created_at:
            user.created_at = created_at
        session.add(user)
        await session.flush()  # 立即分配 id，供后续迁移关联
        print(f"  新增用户: {username} (role={user.role})")
        count += 1
    return count


async def migrate_auth_sessions(session: AsyncSession, data: dict) -> int:
    """迁移登录 token，跳过已过期与已存在的记录，返回新增数量。"""
    count = 0
    now = datetime.now(tz=timezone.utc)
    for token, info in data.items():
        if not isinstance(info, dict):
            print(f"  [警告] 会话数据格式异常，跳过: {str(token)[:16]}...")
            continue
        if not token or len(token) > 128:
            print("  [警告] token 无效（超长或为空），跳过")
            continue

        expires_at = parse_datetime(info.get("expires_at"))
        if expires_at is None:
            print(f"  [警告] token 缺少有效 expires_at，跳过: {token[:16]}...")
            continue
        if expires_at <= now:
            print(f"  跳过已过期 token: {token[:16]}...")
            continue

        # 幂等：token 已存在则跳过
        result = await session.execute(
            select(Session).where(Session.token == token)
        )
        if result.scalar_one_or_none():
            print(f"  跳过已存在 token: {token[:16]}...")
            continue

        user = await get_user_by_username(session, info.get("username", ""))
        if user is None:
            print(f"  [警告] token 关联的用户不存在，跳过: {info.get('username')}")
            continue

        session.add(Session(token=token, user_id=user.id, expires_at=expires_at))
        count += 1
    return count


async def migrate_baselines(session: AsyncSession, data: dict) -> int:
    """迁移计费基线（每用户一条），返回新增数量。"""
    count = 0
    for username, baseline in data.items():
        user = await get_user_by_username(session, username)
        if user is None:
            print(f"  [警告] 基线关联的用户不存在，跳过: {username}")
            continue
        # 幂等：该用户已有基线则跳过
        result = await session.execute(
            select(BillingBaseline).where(BillingBaseline.user_id == user.id)
        )
        if result.scalar_one_or_none():
            print(f"  跳过已存在基线: {username}")
            continue
        session.add(
            BillingBaseline(
                user_id=user.id, baseline_usd=Decimal(str(baseline))
            )
        )
        print(f"  新增基线: {username} -> {baseline}")
        count += 1
    return count


async def migrate_chat_sessions(
    session: AsyncSession, data: list, admin_id: UUID
) -> int:
    """迁移聊天会话，全部关联到 admin 用户，session id 保持原样，返回新增数量。"""
    count = 0
    for item in data:
        if not isinstance(item, dict):
            print("  [警告] 会话数据格式异常，跳过")
            continue
        session_id = str(item.get("id", "")).strip()
        if not session_id or len(session_id) > 12:
            print(f"  [警告] 会话 id 无效（需为 12 位以内字符串），跳过: {session_id!r}")
            continue

        # 幂等：会话 id 已存在则跳过
        result = await session.execute(
            select(ChatSession).where(ChatSession.id == session_id)
        )
        if result.scalar_one_or_none():
            print(f"  跳过已存在会话: {session_id}")
            continue

        chat = ChatSession(
            id=session_id,
            user_id=admin_id,
            title=item.get("title") or "新对话",
            pinned=bool(item.get("pinned", False)),
        )
        created_at = parse_datetime(item.get("created_at"))
        if created_at:
            chat.created_at = created_at
        updated_at = parse_datetime(item.get("updated_at"))
        if updated_at:
            chat.updated_at = updated_at
        session.add(chat)
        count += 1
    return count


async def migrate_chat_messages(session: AsyncSession, storage_dir: Path) -> int:
    """迁移全部 {session_id}_chat.json 聊天消息，返回新增数量。

    消息表无唯一约束，幂等方式为：按会话统计已导入条数，
    跳过已导入的前缀，仅插入新增部分（聊天日志只会追加不会改写）。
    """
    total = 0
    chat_files = sorted(storage_dir.glob("*_chat.json"))
    if not chat_files:
        print("  未找到聊天消息文件，跳过")
        return 0

    for chat_file in chat_files:
        session_id = chat_file.stem[: -len("_chat")]

        # 外键约束：父会话必须已存在
        result = await session.execute(
            select(ChatSession).where(ChatSession.id == session_id)
        )
        if result.scalar_one_or_none() is None:
            print(
                f"  [警告] 会话 {session_id} 不存在于 chat_sessions，"
                f"跳过文件: {chat_file.name}"
            )
            continue

        messages = load_json_file(chat_file)
        if not isinstance(messages, list):
            if messages is not None:
                print(f"  [警告] 消息文件格式非数组，跳过: {chat_file.name}")
            continue

        # 幂等：查询该会话已导入的消息条数
        result = await session.execute(
            select(func.count())
            .select_from(ChatMessage)
            .where(ChatMessage.session_id == session_id)
        )
        existing_count = result.scalar_one()
        if existing_count >= len(messages):
            print(f"  会话 {session_id}: 已导入 {existing_count} 条，跳过")
            continue

        pending = messages[existing_count:]
        for msg in pending:
            if not isinstance(msg, dict):
                continue
            record = ChatMessage(
                session_id=session_id,
                role=str(msg.get("role", "user"))[:20],
                content=msg.get("content") or "",
                images=normalize_images(msg),
            )
            ts = parse_datetime(msg.get("timestamp") or msg.get("created_at"))
            if ts:
                record.timestamp = ts
            session.add(record)
        print(f"  会话 {session_id}: 新增 {len(pending)} 条消息")
        total += len(pending)
    return total


async def migrate_memory_profile(
    session: AsyncSession, profile_file: Path, admin_id: UUID
) -> int:
    """迁移用户记忆画像到 admin 用户（每用户一条），返回新增数量。"""
    data = load_json_file(profile_file)
    if not isinstance(data, dict):
        if data is not None:
            print("  [警告] 画像文件格式非对象，跳过")
        return 0

    # 幂等：该用户已有画像则跳过
    result = await session.execute(
        select(MemoryProfile).where(MemoryProfile.user_id == admin_id)
    )
    if result.scalar_one_or_none():
        print("  跳过已存在的记忆画像")
        return 0

    session.add(MemoryProfile(user_id=admin_id, profile_data=data))
    print("  新增记忆画像")
    return 1


async def migrate_memory_summaries(
    session: AsyncSession, memory_dir: Path, admin_id: UUID
) -> int:
    """迁移全部 {session_id}_summary.json 记忆摘要，返回新增数量。"""
    count = 0
    if not memory_dir.exists():
        print(f"  [警告] 目录不存在，跳过: {memory_dir.relative_to(PROJECT_ROOT)}")
        return 0
    summary_files = sorted(memory_dir.glob("*_summary.json"))
    if not summary_files:
        print("  未找到记忆摘要文件，跳过")
        return 0

    for summary_file in summary_files:
        session_id = summary_file.stem[: -len("_summary")]

        data = load_json_file(summary_file)
        if not isinstance(data, dict):
            continue
        summary_text = data.get("summary")
        if not summary_text:
            print(f"  [警告] 摘要内容为空，跳过: {summary_file.name}")
            continue

        # 外键约束：摘要关联的会话必须存在
        result = await session.execute(
            select(ChatSession).where(ChatSession.id == session_id)
        )
        if result.scalar_one_or_none() is None:
            print(
                f"  [警告] 摘要关联的会话 {session_id} 不存在，"
                f"跳过: {summary_file.name}"
            )
            continue

        # 幂等：同一用户同一会话的摘要已存在则跳过
        result = await session.execute(
            select(MemorySummary).where(
                MemorySummary.user_id == admin_id,
                MemorySummary.session_id == session_id,
            )
        )
        if result.scalar_one_or_none():
            print(f"  跳过已存在摘要: {session_id}")
            continue

        record = MemorySummary(
            user_id=admin_id, session_id=session_id, summary=summary_text
        )
        updated_at = parse_datetime(data.get("updated_at"))
        if updated_at:
            record.updated_at = updated_at
        session.add(record)
        print(f"  新增摘要: {session_id}")
        count += 1
    return count


async def migrate_usage_stats(
    session: AsyncSession, usage_file: Path, admin_id: UUID
) -> int:
    """聚合 tavily_usage.json 全部 key 的总用量，插入一条 search 汇总记录。"""
    data = load_json_file(usage_file)
    if not isinstance(data, dict):
        if data is not None:
            print("  [警告] 用量文件格式非对象，跳过")
        return 0

    total_count = 0
    for entry in data.values():
        if isinstance(entry, dict):
            try:
                total_count += int(entry.get("count", 0) or 0)
            except (TypeError, ValueError):
                continue
    if total_count <= 0:
        print("  用量为 0，跳过")
        return 0

    # 幂等：该用户已存在 search 用量记录则跳过
    result = await session.execute(
        select(UsageStat).where(
            UsageStat.user_id == admin_id, UsageStat.api_type == "search"
        )
    )
    if result.scalars().first():
        print("  跳过已存在的 search 用量汇总")
        return 0

    session.add(
        UsageStat(
            user_id=admin_id,
            api_type="search",
            count=total_count,
            cost=Decimal("0"),
        )
    )
    print(f"  新增 search 用量汇总: count={total_count}")
    return 1


# ---------------------------------------------------------------------------
# 主流程：按依赖顺序迁移，单一事务，全部成功才提交
# ---------------------------------------------------------------------------


async def main() -> None:
    config = Config.load(PROJECT_ROOT / "config.yaml")
    if not config.database_url:
        print(
            "错误：DATABASE_URL 未配置"
            "（请在 config.yaml 设置 database_url 或配置环境变量 DATABASE_URL）"
        )
        sys.exit(1)

    engine, async_session = create_engine_and_session(config.database_url)

    try:
        async with async_session() as session:
            try:
                print("开始迁移...")

                # 1. 先迁移用户（其他表依赖 user_id）
                print("[1/8] 迁移用户账户...")
                accounts = load_json_file(STORAGE_DIR / "auth" / "accounts.json")
                if isinstance(accounts, dict):
                    count = await migrate_users(
                        session, accounts, config.admin_username
                    )
                    print(f"用户迁移完成，新增 {count} 个")

                # admin 用户是后续多类数据的归属方
                admin = await get_admin_user(session)
                if admin is None:
                    raise RuntimeError(
                        "users 表中没有可用用户，无法继续迁移关联数据"
                    )
                print(f"关联数据归属用户: {admin.username} (id={admin.id})")

                # 2. 登录会话（依赖 users）
                print("[2/8] 迁移登录会话...")
                auth_sessions = load_json_file(
                    STORAGE_DIR / "auth" / "sessions.json"
                )
                if isinstance(auth_sessions, dict):
                    count = await migrate_auth_sessions(session, auth_sessions)
                    print(f"登录会话迁移完成，新增 {count} 个")

                # 3. 计费基线（依赖 users）
                print("[3/8] 迁移计费基线...")
                baselines = load_json_file(
                    STORAGE_DIR / "billing" / "baselines.json"
                )
                if isinstance(baselines, dict):
                    count = await migrate_baselines(session, baselines)
                    print(f"计费基线迁移完成，新增 {count} 个")

                # 4. 聊天会话（依赖 users）
                print("[4/8] 迁移聊天会话...")
                chat_sessions = load_json_file(STORAGE_DIR / "sessions.json")
                if isinstance(chat_sessions, list):
                    count = await migrate_chat_sessions(
                        session, chat_sessions, admin.id
                    )
                    print(f"聊天会话迁移完成，新增 {count} 个")

                # 5. 聊天消息（依赖 chat_sessions）
                print("[5/8] 迁移聊天消息...")
                count = await migrate_chat_messages(session, STORAGE_DIR)
                print(f"聊天消息迁移完成，新增 {count} 条")

                # 6. 用户记忆画像（依赖 users）
                print("[6/8] 迁移用户记忆画像...")
                count = await migrate_memory_profile(
                    session, STORAGE_DIR / "memory" / "profile.json", admin.id
                )
                print(f"记忆画像迁移完成，新增 {count} 条")

                # 7. 会话记忆摘要（依赖 users + chat_sessions）
                print("[7/8] 迁移会话记忆摘要...")
                count = await migrate_memory_summaries(
                    session, STORAGE_DIR / "memory", admin.id
                )
                print(f"记忆摘要迁移完成，新增 {count} 条")

                # 8. API 用量统计（依赖 users）
                print("[8/8] 迁移 API 用量统计...")
                count = await migrate_usage_stats(
                    session, TAVILY_USAGE_FILE, admin.id
                )
                print(f"用量统计迁移完成，新增 {count} 条")

                # 单一事务：全部成功才提交
                await session.commit()
                print("全部迁移完成！")
            except Exception:
                # 任一环节失败则整体回滚
                await session.rollback()
                print("迁移失败，已回滚全部变更")
                raise
    finally:
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
