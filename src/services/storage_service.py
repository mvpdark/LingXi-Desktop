"""多会话存储服务（PostgreSQL 版，多租户隔离）。

所有会话与聊天消息均绑定 user_id：
    - 读操作：查询一律带 user_id 过滤条件，用户之间数据互不可见
    - 写操作：先验证会话归属（_is_owner），越权时返回 False / []，不抛异常
    - 删除会话：先删除 chat_messages，再删除 chat_sessions
      （数据库外键 ondelete='CASCADE' 作为兜底，见 src/db/models.py）

历史版本基于 JSON 文件（cache/storage/sessions.json、{session_id}_chat.json），
相关文件 IO、文件锁与原子写入逻辑已全部移除，所有数据走 PostgreSQL；
旧数据可执行 src/db/migrate_from_json.py 一次性导入。

约定：
    - user_id 为字符串形式的 UUID（与 JWT sub / request.state.user_id 一致）
    - 返回的时间字段统一为 ISO 8601 字符串（前端兼容，
      chat.js 排序逻辑同时支持 number 与 ISO 字符串）
    - 数据库异常记录日志后返回 False / [] / {}，不向外抛异常
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from sqlalchemy import delete, desc, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

# 兼容两种包布局：项目根在 sys.path（src.*）或 src 目录在 sys.path（*）
try:
    from src.db.models import ChatMessage, ChatSession
except ImportError:  # pragma: no cover
    from db.models import ChatMessage, ChatSession

logger = logging.getLogger(__name__)


class StorageService:
    """会话与聊天消息存储服务（PostgreSQL 版）。

    参数:
        db_session_factory: async_sessionmaker 实例（见 src/db/database.py）

    所有公开方法均为异步；user_id 为字符串形式的 UUID。
    """

    def __init__(self, db_session_factory: async_sessionmaker):
        self.db = db_session_factory

    # ------------------------------------------------------------------
    # 会话管理
    # ------------------------------------------------------------------

    async def list_sessions_async(self, user_id: str) -> List[Dict[str, Any]]:
        """获取用户的所有会话列表（置顶在前，同组按更新时间倒序）。

        只返回会话元数据（id/title/pinned/created_at/updated_at），
        不加载消息内容。
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            return []
        try:
            async with self.db() as session:
                result = await session.execute(
                    select(ChatSession)
                    .where(ChatSession.user_id == uid)
                    .order_by(
                        desc(ChatSession.pinned),
                        desc(ChatSession.updated_at),
                    )
                )
                return [self._session_to_dict(s) for s in result.scalars().all()]
        except Exception as exc:
            logger.error("获取会话列表失败: user_id=%s err=%s", user_id, exc)
            return []

    async def create_session_async(
        self, user_id: str, title: str = "新对话"
    ) -> Dict[str, Any]:
        """创建新会话并关联 user_id，返回会话信息。

        session_id 保持现有的 12 位 hex 格式；created_at/updated_at 由
        数据库默认值生成（INSERT 经 RETURNING 回填）。
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            return {}
        try:
            async with self.db() as session:
                chat = ChatSession(
                    id=self._generate_session_id(),
                    user_id=uid,
                    title=(title or "新对话")[:200],
                )
                session.add(chat)
                await session.commit()
                return self._session_to_dict(chat)
        except Exception as exc:
            logger.error("创建会话失败: user_id=%s err=%s", user_id, exc)
            return {}

    async def delete_session_async(self, user_id: str, session_id: str) -> bool:
        """删除会话及其全部消息（级联删除）。

        会话不存在或不属于该用户时返回 False。
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        try:
            async with self.db() as session:
                if not await self._is_owner(session, uid, session_id):
                    return False
                # 先显式删除消息（不依赖外键约束是否已生效；
                # models.py 中 ondelete='CASCADE' 作为兜底）
                await session.execute(
                    delete(ChatMessage).where(
                        ChatMessage.session_id == session_id
                    )
                )
                # WHERE 中再带 user_id，防御性保证多租户隔离
                result = await session.execute(
                    delete(ChatSession).where(
                        ChatSession.id == session_id,
                        ChatSession.user_id == uid,
                    )
                )
                await session.commit()
                return result.rowcount > 0
        except Exception as exc:
            logger.error(
                "删除会话失败: user_id=%s session_id=%s err=%s",
                user_id, session_id, exc,
            )
            return False

    async def pin_session_async(self, user_id: str, session_id: str) -> bool:
        """置顶会话（保持 updated_at 不变，避免置顶操作改变列表排序）。"""
        return await self._update_session_fields(
            user_id,
            session_id,
            pinned=True,
            updated_at=ChatSession.updated_at,
        )

    async def unpin_session_async(self, user_id: str, session_id: str) -> bool:
        """取消置顶（保持 updated_at 不变）。"""
        return await self._update_session_fields(
            user_id,
            session_id,
            pinned=False,
            updated_at=ChatSession.updated_at,
        )

    async def rename_session_async(
        self, user_id: str, session_id: str, title: str
    ) -> bool:
        """重命名会话（同时刷新 updated_at）。"""
        return await self._update_session_fields(
            user_id,
            session_id,
            title=(title or "新对话")[:200],
            updated_at=func.now(),
        )

    async def touch_session_async(self, user_id: str, session_id: str) -> bool:
        """更新会话的 updated_at 时间戳。"""
        return await self._update_session_fields(
            user_id, session_id, updated_at=func.now()
        )

    # ------------------------------------------------------------------
    # 聊天消息管理
    # ------------------------------------------------------------------

    async def load_chat_history_async(
        self, user_id: str, session_id: str
    ) -> List[Dict[str, Any]]:
        """加载会话的聊天历史（按 timestamp 正序）。

        会话不存在或不属于该用户时返回 []。
        返回 [{role, content, images, image_url, timestamp}, ...]，
        其中 image_url 为旧格式单图字段（取第一张图），保留以兼容现有前端。
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            return []
        try:
            async with self.db() as session:
                if not await self._is_owner(session, uid, session_id):
                    return []
                result = await session.execute(
                    select(ChatMessage)
                    .where(ChatMessage.session_id == session_id)
                    .order_by(ChatMessage.timestamp.asc())
                )
                return [self._message_to_dict(m) for m in result.scalars().all()]
        except Exception as exc:
            logger.error(
                "加载聊天历史失败: user_id=%s session_id=%s err=%s",
                user_id, session_id, exc,
            )
            return []

    async def save_chat_history_async(
        self,
        user_id: str,
        session_id: str,
        history: List[Dict[str, Any]],
    ) -> bool:
        """保存聊天历史（全量覆盖：删除旧消息后批量插入）。

        适用于历史完整覆盖场景（调用方传入完整历史）。消息自带的
        timestamp（Unix 时间戳或 ISO 字符串）会被保留；缺失时基于当前
        时间按序递增分配，保证批量插入后的时间顺序稳定（同一事务内
        func.now() 恒定，不能依赖数据库默认值维持插入顺序）。
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        try:
            async with self.db() as session:
                if not await self._is_owner(session, uid, session_id):
                    return False
                await session.execute(
                    delete(ChatMessage).where(
                        ChatMessage.session_id == session_id
                    )
                )
                base = datetime.now(timezone.utc)
                records = []
                for index, msg in enumerate(history):
                    if not isinstance(msg, dict):
                        continue
                    ts = self._parse_timestamp(msg.get("timestamp"))
                    if ts is None:
                        ts = base + timedelta(microseconds=index)
                    records.append(
                        ChatMessage(
                            session_id=session_id,
                            role=str(msg.get("role") or "user")[:20],
                            content=msg.get("content") or "",
                            images=self._normalize_images(msg),
                            timestamp=ts,
                        )
                    )
                session.add_all(records)
                await session.commit()
                return True
        except Exception as exc:
            logger.error(
                "保存聊天历史失败: user_id=%s session_id=%s err=%s",
                user_id, session_id, exc,
            )
            return False

    async def append_message_async(
        self,
        user_id: str,
        session_id: str,
        role: str,
        content: str,
        images: Optional[List[str]] = None,
    ) -> bool:
        """追加单条消息（比 save_chat_history_async 全量覆盖更高效）。

        会话不存在或不属于该用户时返回 False。
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        try:
            async with self.db() as session:
                if not await self._is_owner(session, uid, session_id):
                    return False
                session.add(
                    ChatMessage(
                        session_id=session_id,
                        role=(role or "user")[:20],
                        content=content or "",
                        images=list(images) if images else None,
                        timestamp=datetime.now(timezone.utc),
                    )
                )
                await session.commit()
                return True
        except Exception as exc:
            logger.error(
                "追加消息失败: user_id=%s session_id=%s err=%s",
                user_id, session_id, exc,
            )
            return False

    async def clear_chat_history_async(
        self, user_id: str, session_id: str
    ) -> bool:
        """清空会话的所有消息（保留会话本身）。"""
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        try:
            async with self.db() as session:
                if not await self._is_owner(session, uid, session_id):
                    return False
                await session.execute(
                    delete(ChatMessage).where(
                        ChatMessage.session_id == session_id
                    )
                )
                await session.commit()
                return True
        except Exception as exc:
            logger.error(
                "清空聊天历史失败: user_id=%s session_id=%s err=%s",
                user_id, session_id, exc,
            )
            return False

    # ------------------------------------------------------------------
    # 内部方法
    # ------------------------------------------------------------------

    @staticmethod
    async def _is_owner(
        session: AsyncSession, uid: uuid.UUID, session_id: str
    ) -> bool:
        """在既有数据库会话内验证会话归属（避免重复获取连接）。"""
        result = await session.execute(
            select(ChatSession.id).where(
                ChatSession.id == session_id,
                ChatSession.user_id == uid,
            )
        )
        return result.scalar_one_or_none() is not None

    async def _update_session_fields(
        self, user_id: str, session_id: str, **values: Any
    ) -> bool:
        """按 (session_id, user_id) 条件更新会话字段。

        多租户隔离直接在 UPDATE 的 WHERE 子句中保证；
        会话不存在或不属于该用户时 rowcount 为 0，返回 False。
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        try:
            async with self.db() as session:
                result = await session.execute(
                    update(ChatSession)
                    .where(
                        ChatSession.id == session_id,
                        ChatSession.user_id == uid,
                    )
                    .values(**values)
                )
                await session.commit()
                return result.rowcount > 0
        except Exception as exc:
            logger.error(
                "更新会话失败: user_id=%s session_id=%s fields=%s err=%s",
                user_id, session_id, sorted(values), exc,
            )
            return False

    @staticmethod
    def _to_uuid(user_id: Any) -> Optional[uuid.UUID]:
        """将字符串形式的 user_id 转为 UUID，非法输入返回 None。"""
        try:
            return uuid.UUID(str(user_id))
        except (ValueError, AttributeError, TypeError):
            return None

    @staticmethod
    def _generate_session_id() -> str:
        """生成12位hex session_id（保持现有格式）。"""
        return uuid.uuid4().hex[:12]

    @staticmethod
    def _iso(dt: Optional[datetime]) -> Optional[str]:
        """datetime 序列化为 ISO 8601 字符串（None 安全）。"""
        return dt.isoformat() if dt else None

    @classmethod
    def _session_to_dict(cls, s: ChatSession) -> Dict[str, Any]:
        """会话 ORM 对象序列化为对外字典（时间字段为 ISO 字符串）。"""
        return {
            "id": s.id,
            "title": s.title,
            "pinned": bool(s.pinned),
            "created_at": cls._iso(s.created_at),
            "updated_at": cls._iso(s.updated_at),
        }

    @classmethod
    def _message_to_dict(cls, m: ChatMessage) -> Dict[str, Any]:
        """消息 ORM 对象序列化为对外字典。

        images 为数组格式；image_url 为旧格式单图字段（取第一张图），
        保留以兼容现有前端渲染与 LLM 上下文构造。

        id 字段必填：客户端 LazyColumn 以 message.id 作为 key，
        缺失会导致所有消息 key 相同（空串）从而引发重复 key 崩溃。
        """
        images = m.images if isinstance(m.images, list) else []
        return {
            "id": str(m.id),
            "role": m.role,
            "content": m.content,
            "images": images,
            "image_url": images[0] if images else "",
            "timestamp": cls._iso(m.timestamp),
        }

    @staticmethod
    def _normalize_images(msg: Dict[str, Any]) -> Optional[List[str]]:
        """统一 images 字段为数组格式。

        列表原样保留；字符串包一层数组；兼容旧 image_url 单图字段；
        无图片返回 None（与 src/db/migrate_from_json.py 的规则一致）。
        """
        images = msg.get("images")
        if isinstance(images, list):
            return images or None
        if isinstance(images, str) and images:
            return [images]
        image_url = msg.get("image_url")
        if isinstance(image_url, str) and image_url:
            return [image_url]
        return None

    @staticmethod
    def _parse_timestamp(value: Any) -> Optional[datetime]:
        """将消息中的时间值转换为带时区 datetime。

        兼容 Unix 时间戳（int/float，旧 JSON 数据格式）与 ISO 8601
        字符串；无法解析时返回 None（由调用方分配当前时间）。
        """
        if value is None:
            return None
        if isinstance(value, (int, float)):
            return datetime.fromtimestamp(value, tz=timezone.utc)
        if isinstance(value, str):
            text = value.strip()
            if not text:
                return None
            try:
                dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
                return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
            except ValueError:
                return None
        return None
