"""计费服务（PostgreSQL 版）。

管理用户账户余额与 yunwu API 消耗的计费关系。

计费模型：
- 计费比例由配置注入（billing_rate，默认 2.0）—— yunwu API 每消耗 1 美分，
  账户扣减 billing_rate 元人民币
- 通过查询 yunwu 的 billing 接口获取实际消耗（美分），与基准值对比计算增量

yunwu API 接口（已验证可用）：
- GET /v1/dashboard/billing/subscription → {"hard_limit_usd": 101.53, "token_name": "image2"}
- GET /v1/dashboard/billing/usage → {"total_usage": 1526.68}（单位：美分，直接作为计费基数）

特性：
- 多 key 轮询：通过 KeyPool 管理多个 yunwu key，失败自动切换并冷却
- 基准线持久化：每个用户的 baseline 存于 PostgreSQL billing_baselines 表，
  通过 INSERT ... ON CONFLICT DO UPDATE 原子 UPSERT，替代旧的 baselines.json
- 用户标识：全部使用 user_id（UUID 字符串），余额查询/扣减走新版 AuthService
- 并发安全：charge 在进程内用 asyncio.Lock 串行化，余额扣减由
  AuthService.update_balance 的单条 UPDATE 原子完成，baseline 用数据库 UPSERT
- 金额精度：使用 Decimal 计算，人民币金额量化到分，避免浮点误差

设计决策（全局消耗归属）：
- yunwu 的消耗统计是账户级（key 级）的，无法按租户（用户）归因——
  所有用户共享同一批 yunwu key，usage 接口只返回 key 的总消耗
- 因此全局消耗仅由主账户（role == "admin"）承担：
  charge 对非 admin 用户直接跳过扣费（返回 skipped=True）
- 普通用户的余额由主账户手动管理（充值/扣减走 AuthService 余额接口）
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timezone
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Dict, List, Optional

import httpx
from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from .key_pool import KeyPool

# 兼容两种包布局：项目根在 sys.path（src.*）或 src 目录在 sys.path（*）
try:
    from src.db.models import BillingBaseline, UsageStat, User
except ImportError:  # pragma: no cover
    from db.models import BillingBaseline, UsageStat, User

logger = logging.getLogger(__name__)


class BillingService:
    """计费服务（PostgreSQL 版）。

    负责查询 yunwu API 消耗、计算增量、扣减用户余额、记录用量统计。

    使用示例::

        billing = BillingService(
            db_session_factory=async_session_factory,
            auth_service=auth,
            api_keys=["sk-aaa", "sk-bbb"],
            billing_rate=config.billing_rate,
        )
        await billing.init_baseline(user_id)                   # 初始化基准
        summary = await billing.get_billing_summary(user_id)   # 查看计费摘要
        result = await billing.charge(user_id)                 # 执行计费扣款
    """

    #: 计费比例默认值（100 美分 yunwu 消耗 = 2 元人民币，即 1 美元 = 2 元）。
    #: 仅为类级缺省，实例化时会被构造参数 billing_rate 覆盖；
    #: 保留类属性是为了兼容 ``BillingService.RATE`` 的外部访问（如 server.py 日志）。
    RATE: Decimal = Decimal("2.0")

    #: yunwu API 查询超时时间（秒）
    REQUEST_TIMEOUT = 15

    #: 人民币金额精度（分）
    _CENT = Decimal("0.01")

    def __init__(
        self,
        db_session_factory,
        auth_service,
        api_keys: List[str],
        api_base: str = "https://yunwu.ai",
        billing_rate: float = 2.0,
    ):
        """初始化计费服务。

        参数:
            db_session_factory: async_sessionmaker 实例（见 src/db/database.py）
            auth_service: 新版 AuthService 实例（async 方法，接受 user_id）
            api_keys: yunwu API key 列表
            api_base: yunwu API 基础地址
            billing_rate: 计费比例（1 美分 = 多少人民币），从配置读取，不再硬编码
        """
        self.db = db_session_factory
        self.auth = auth_service
        self.api_base = api_base.rstrip("/")
        # 计费比例：用 Decimal 保证精度
        self.RATE = Decimal(str(billing_rate))

        # 保留原始 key 列表（去空白），用于遍历查询所有 key 的消耗
        self.api_keys: List[str] = [
            k.strip() for k in (api_keys or []) if k and k.strip()
        ]
        self._api_keys = self.api_keys
        # KeyPool 用于失败标记与冷却管理
        self.key_pool = KeyPool(self.api_keys)

        # 进程内异步锁：串行化 charge 的临界区（跨进程由数据库原子操作保证）
        self._charge_lock = asyncio.Lock()

        # 惰性创建的共享 AsyncClient（首次调用 _get_client 时初始化）
        self._client: httpx.AsyncClient | None = None
        # 并发锁：防止多个协程同时首次创建 client
        self._lock = asyncio.Lock()

        if not self.api_keys:
            logger.warning("BillingService 初始化时 api_keys 为空，无法查询 yunwu 消耗")

    # ------------------------------------------------------------------
    # 内部工具
    # ------------------------------------------------------------------
    @staticmethod
    def _to_uuid(user_id: str) -> Optional[uuid.UUID]:
        """将字符串形式的 user_id 转为 UUID，非法输入返回 None。"""
        try:
            return uuid.UUID(str(user_id))
        except (ValueError, AttributeError, TypeError):
            return None

    def _to_rmb(self, cents: Decimal) -> Decimal:
        """美分消耗按 RATE 折算人民币，量化到分（四舍五入）。

        计费基数是 yunwu API 返回的 total_usage（美分），
        先转美元（/100），再乘 RATE 得到人民币。
        即 100 美分(1美元) × 2.0 = 2.00 元人民币。
        """
        usd = cents / Decimal("100")
        return (usd * self.RATE).quantize(self._CENT, rounding=ROUND_HALF_UP)

    async def _get_baseline_cents(self, session, uid: uuid.UUID) -> Optional[Decimal]:
        """在指定会话中查询用户的 baseline（美分），不存在返回 None。"""
        result = await session.execute(
            select(BillingBaseline.baseline_cents).where(
                BillingBaseline.user_id == uid
            )
        )
        return result.scalar_one_or_none()

    async def _upsert_baseline(
        self, session, uid: uuid.UUID, baseline_cents: Decimal
    ) -> datetime:
        """UPSERT 用户 baseline（存在则更新，不存在则插入），返回写入的时间戳。"""
        now = datetime.now(timezone.utc)
        stmt = pg_insert(BillingBaseline).values(
            user_id=uid,
            baseline_cents=baseline_cents,
            updated_at=now,
        ).on_conflict_do_update(
            index_elements=["user_id"],
            set_={"baseline_cents": baseline_cents, "updated_at": now},
        )
        await session.execute(stmt)
        return now

    # ------------------------------------------------------------------
    # yunwu API 查询（外部 API 调用，与存储无关，保持现有实现）
    # ------------------------------------------------------------------
    async def _get_client(self) -> httpx.AsyncClient:
        """返回共享的 httpx.AsyncClient（惰性创建，并发安全）。

        首次调用时创建 client，后续复用同一实例，
        避免 query_key_usage 每次都新建 TCP 连接。
        使用 asyncio.Lock 防止高并发下多个协程同时创建 client 实例。
        """
        if self._client is not None and not self._client.is_closed:
            return self._client
        async with self._lock:
            if self._client is not None and not self._client.is_closed:
                return self._client
            self._client = httpx.AsyncClient(timeout=self.REQUEST_TIMEOUT)
        return self._client

    async def close(self) -> None:
        """关闭共享 AsyncClient，释放连接池资源。

        应在 FastAPI lifespan shutdown 阶段调用。
        """
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
        self._client = None

    async def query_key_usage(self, api_key: str) -> Optional[Dict[str, Any]]:
        """查询单个 key 的消耗。

        依次调用 yunwu 的 usage 与 subscription 接口，汇总消耗信息。
        查询失败时会调用 key_pool.mark_failed 触发冷却。

        参数:
            api_key: yunwu API key

        返回:
            {
                "total_usage_cents": float,  # 总消耗（美分，直接作为计费基数）
                "hard_limit_usd": float,     # 额度上限（美元，仅展示用）
                "token_name": str,           # token 名称
                "ok": bool,                  # 是否成功
                "error": str,                # 错误信息（失败时）
            }
        """
        result = {
            "total_usage_cents": 0.0,
            "hard_limit_usd": 0.0,
            "token_name": "",
            "ok": False,
            "error": "",
        }

        if not api_key:
            result["error"] = "api_key 为空"
            return result

        headers = {"Authorization": f"Bearer {api_key}"}
        # 脱敏后的 key 标识，用于日志
        key_mask = f"{api_key[:6]}...{api_key[-4:]}" if len(api_key) > 10 else "***"

        try:
            client = await self._get_client()
            # 查询消耗（单位：美分，直接作为计费基数，不再 /100 转美元）
            resp = await client.get(
                f"{self.api_base}/v1/dashboard/billing/usage",
                headers=headers,
            )
            resp.raise_for_status()
            usage_data = resp.json()
            total_usage_cents = float(usage_data.get("total_usage", 0))

            # 查询额度与 token 信息
            resp2 = await client.get(
                f"{self.api_base}/v1/dashboard/billing/subscription",
                headers=headers,
            )
            resp2.raise_for_status()
            sub_data = resp2.json()
            hard_limit_usd = float(sub_data.get("hard_limit_usd", 0))
            token_name = str(sub_data.get("token_name", ""))

            result.update({
                "total_usage_cents": total_usage_cents,
                "hard_limit_usd": hard_limit_usd,
                "token_name": token_name,
                "ok": True,
            })
            # 查询成功，标记 key 恢复（清除可能的失败冷却状态）
            self.key_pool.mark_success(api_key)
            logger.info(
                "key %s 消耗查询成功：%.2f 美分，额度 %.2f 美元",
                key_mask, total_usage_cents, hard_limit_usd,
            )
        except httpx.HTTPStatusError as e:
            result["error"] = f"HTTP {e.response.status_code}: {e.response.text[:200]}"
            self.key_pool.mark_failed(api_key)
            logger.warning("key %s 查询失败（HTTP 错误）: %s", key_mask, result["error"])
        except httpx.HTTPError as e:
            # 涵盖 RequestError、TimeoutException 等所有 httpx 异常
            result["error"] = f"请求异常: {type(e).__name__}: {e}"
            self.key_pool.mark_failed(api_key)
            logger.warning("key %s 查询失败（请求异常）: %s", key_mask, result["error"])
        except (ValueError, KeyError, TypeError) as e:
            # 响应解析失败（如 JSONDecodeError 是 ValueError 子类）
            result["error"] = f"响应解析失败: {type(e).__name__}: {e}"
            self.key_pool.mark_failed(api_key)
            logger.warning("key %s 查询失败（解析异常）: %s", key_mask, result["error"])

        return result

    async def get_all_keys_usage(self) -> Dict[str, Any]:
        """查询所有 key 的消耗汇总。

        并发查询所有 key，汇总总消耗。

        返回:
            {
                "keys": [...],              # 各 key 的查询结果列表
                "total_usage_cents": float, # 所有 key 总消耗（美分）
                "ok": bool,                 # 是否至少有一个 key 查询成功
            }
        """
        if not self._api_keys:
            logger.warning("无可用 key，无法查询 yunwu 消耗")
            return {"keys": [], "total_usage_cents": 0.0, "ok": False}

        # 并发查询所有 key，提升效率
        tasks = [self.query_key_usage(k) for k in self._api_keys]
        results = await asyncio.gather(*tasks)

        total_usage_cents = sum(r.get("total_usage_cents", 0.0) for r in results)
        any_ok = any(r.get("ok", False) for r in results)

        return {
            "keys": results,
            "total_usage_cents": total_usage_cents,
            "ok": any_ok,
        }

    # ------------------------------------------------------------------
    # baseline 管理（PostgreSQL UPSERT）
    # ------------------------------------------------------------------
    async def init_baseline(self, user_id: str) -> Dict[str, Any]:
        """为用户初始化 yunwu 消耗基准。

        记录当前所有 key 的总消耗作为 baseline，后续计费基于此基准计算增量。
        存在则更新，不存在则插入（UPSERT）。

        参数:
            user_id: 用户 ID（UUID 字符串）

        返回:
            {"user_id": str, "baseline_cents": float, "updated_at": str, "ok": bool}
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            logger.warning("初始化 baseline 失败：非法 user_id %r", user_id)
            return {
                "user_id": user_id,
                "baseline_cents": 0.0,
                "updated_at": None,
                "ok": False,
            }

        usage = await self.get_all_keys_usage()
        baseline_cents = Decimal(str(usage.get("total_usage_cents", 0.0)))

        async with self.db() as session:
            async with session.begin():
                updated_at = await self._upsert_baseline(
                    session, uid, baseline_cents
                )

        logger.info(
            "用户 %s 初始化 baseline: %.2f 美分", user_id, float(baseline_cents)
        )
        return {
            "user_id": user_id,
            "baseline_cents": float(baseline_cents),
            "updated_at": updated_at.isoformat(),
            "ok": usage.get("ok", False),
        }

    # ------------------------------------------------------------------
    # 计费摘要与扣款
    # ------------------------------------------------------------------
    async def get_billing_summary(self, user_id: str) -> Dict[str, Any]:
        """获取计费摘要。

        返回用户余额、基准消耗、当前消耗、增量、应扣金额等信息。
        如果用户没有 baseline，会自动调用 init_baseline 初始化。

        参数:
            user_id: 用户 ID（UUID 字符串）

        返回:
            {
                "user_id": str,
                "balance_rmb": float,          # 当前账户余额（人民币）
                "baseline_cents": float,       # 基准消耗（美分）
                "current_usage_cents": float,  # 当前消耗（美分）
                "delta_cents": float,          # 增量消耗（美分）
                "delta_rmb": float,            # 应扣金额（人民币）
                "rate": float,                 # 计费比例
                "keys_count": int,             # yunwu key 数量
                "ok": bool,
            }
        """
        # 从 users 表查询余额（经由 AuthService）
        account = await self.auth.get_user_by_id(user_id)
        if account is None:
            logger.warning("获取计费摘要失败：用户 %s 不存在", user_id)
            return {
                "user_id": user_id,
                "balance_rmb": 0.0,
                "baseline_cents": 0.0,
                "current_usage_cents": 0.0,
                "delta_cents": 0.0,
                "delta_rmb": 0.0,
                "rate": float(self.RATE),
                "keys_count": len(self._api_keys),
                "ok": False,
            }
        balance_rmb = Decimal(str(account.get("balance", 0.0)))

        uid = self._to_uuid(user_id)

        # 从 billing_baselines 表查询 baseline，没有则自动初始化
        async with self.db() as session:
            baseline_cents = await self._get_baseline_cents(session, uid)
        if baseline_cents is None:
            init_result = await self.init_baseline(user_id)
            baseline_cents = Decimal(str(init_result.get("baseline_cents", 0.0)))

        # 查询当前总消耗
        usage = await self.get_all_keys_usage()
        current_cents = Decimal(str(usage.get("total_usage_cents", 0.0)))

        # 计算增量与应扣金额
        delta_cents = current_cents - baseline_cents
        # 消耗不可能减少，若 delta < 0 则视为 0（异常保护）
        if delta_cents < 0:
            logger.warning(
                "用户 %s delta_cents=%.2f < 0（消耗减少，异常），本次按 0 计算",
                user_id, float(delta_cents),
            )
            delta_cents = Decimal("0")
        delta_rmb = self._to_rmb(delta_cents)

        return {
            "user_id": user_id,
            "balance_rmb": float(balance_rmb),
            "baseline_cents": float(baseline_cents),
            "current_usage_cents": float(current_cents),
            "delta_cents": float(delta_cents),
            "delta_rmb": float(delta_rmb),
            "rate": float(self.RATE),
            "keys_count": len(self._api_keys),
            "ok": True,
        }

    async def charge(self, user_id: str) -> Dict[str, Any]:
        """执行计费：计算增量消耗并扣减余额（仅主账户承担全局消耗）。

        流程：
        1. 校验用户角色：非 admin（主账户）直接跳过，不承担全局消耗
        2. 查询当前 yunwu 总消耗
        3. 计算 delta = current - baseline
        4. delta_rmb = delta * RATE（量化到分）
        5. 先 UPSERT 推进 baseline 为当前消耗
        6. 再调用 auth.update_balance(user_id, -delta_rmb) 原子扣款

        步骤 5/6 的顺序是刻意的：先推进 baseline 再扣款，若扣款失败
        （如余额不足被 update_balance 拒绝），最坏结果是漏收（本次增量
        已计入 baseline，下次不再重复计），而非重收（baseline 未推进
        导致下次对同一增量重复扣费）。

        若 delta_cents < 0（消耗减少，不可能），不扣费但仍刷新 baseline。
        若用户没有 baseline，自动调用 init_baseline 初始化。

        参数:
            user_id: 用户 ID（UUID 字符串）

        返回:
            非主账户跳过：{"charged": 0, "skipped": True, "reason": ...}
            否则：
            {
                "user_id": str,
                "charged_rmb": float,         # 本次扣减金额（人民币）
                "delta_cents": float,         # 本次增量消耗（美分）
                "new_balance": float,         # 扣减后新余额（人民币）
                "new_baseline_cents": float,  # 新基准消耗（美分）
                "ok": bool,
            }
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            logger.warning("计费扣款失败：非法 user_id %r", user_id)
            return {
                "user_id": user_id,
                "charged_rmb": 0.0,
                "delta_cents": 0.0,
                "new_balance": 0.0,
                "new_baseline_cents": 0.0,
                "ok": False,
            }

        # P0-2 全局消耗仅由主账户承担：yunwu 消耗是账户（key）级统计，
        # 无法按租户归因，故非 admin 用户跳过全局扣费逻辑；
        # 普通用户余额由主账户手动管理（见文件头 docstring 设计决策）
        async with self.db() as session:
            result = await session.execute(
                select(User.role).where(User.id == uid)
            )
            role = result.scalar_one_or_none()
        if role != "admin":
            logger.info(
                "用户 %s 非主账户（role=%s），跳过全局消耗扣费", user_id, role
            )
            return {
                "charged": 0,
                "skipped": True,
                "reason": "非主账户不承担全局消耗",
            }

        # 进程内串行化扣费临界区；余额与 baseline 的数据库操作各自原子
        async with self._charge_lock:
            # 读取 baseline，没有则自动初始化
            async with self.db() as session:
                baseline_cents = await self._get_baseline_cents(session, uid)
            if baseline_cents is None:
                init_result = await self.init_baseline(user_id)
                baseline_cents = Decimal(str(init_result.get("baseline_cents", 0.0)))

            # 查询当前总消耗
            usage = await self.get_all_keys_usage()
            current_cents = Decimal(str(usage.get("total_usage_cents", 0.0)))

            # 计算增量
            delta_cents = current_cents - baseline_cents
            # 消耗不可能减少，若 delta < 0 不扣费
            if delta_cents < 0:
                logger.warning(
                    "用户 %s delta_cents=%.2f < 0（消耗减少，异常），本次不扣费",
                    user_id, float(delta_cents),
                )
                delta_cents = Decimal("0")

            delta_rmb = self._to_rmb(delta_cents)

            # 扣款原子性：baseline UPSERT 与余额扣减在同一事务中执行。
            # 若扣款失败（余额不足或用户不存在），整个事务回滚，
            # baseline 也不推进——下次会对同一增量重试。
            # 这比"先提交 baseline 再扣款（漏收）"更安全：重收可追回，漏收不可追。
            balance_ok = True
            async with self.db() as session:
                # 1. UPSERT 推进 baseline
                await self._upsert_baseline(session, uid, current_cents)

                # 2. 扣减余额（仅当有实际扣费金额时）
                if delta_rmb > 0:
                    stmt = (
                        update(User)
                        .where(User.id == uid)
                        .where(User.balance >= Decimal(str(delta_rmb)))
                        .values(balance=User.balance - Decimal(str(delta_rmb)))
                    )
                    result = await session.execute(stmt)
                    if result.rowcount:
                        await session.commit()
                    else:
                        # 余额不足或用户不存在，回滚整个事务（baseline 也不提交）
                        await session.rollback()
                        balance_ok = False
                else:
                    # 无实际扣费金额，仅提交 baseline
                    await session.commit()

            if not balance_ok:
                logger.warning(
                    "计费扣款失败（用户不存在或余额不足，baseline 未推进，下次重试）: 用户 %s",
                    user_id,
                )
                return {
                    "user_id": user_id,
                    "charged_rmb": 0.0,
                    "delta_cents": float(delta_cents),
                    "new_balance": 0.0,
                    "new_baseline_cents": float(baseline_cents),
                    "ok": False,
                }

        # 查询扣费后的最新余额
        account = await self.auth.get_user_by_id(user_id)
        new_balance = (
            Decimal(str(account.get("balance", 0.0)))
            if account is not None
            else Decimal("0")
        )

        logger.info(
            "用户 %s 计费完成：delta=%.2f 美分，扣减 %.2f 元，新余额 %.2f，新 baseline=%.2f 美分",
            user_id, float(delta_cents), float(delta_rmb),
            float(new_balance), float(current_cents),
        )

        return {
            "user_id": user_id,
            "charged_rmb": float(delta_rmb),
            "delta_cents": float(delta_cents),
            "new_balance": float(new_balance),
            "new_baseline_cents": float(current_cents),
            "ok": account is not None,
        }

    # ------------------------------------------------------------------
    # 用量统计（usage_stats 表）
    # ------------------------------------------------------------------
    async def get_user_usage_stats(
        self, user_id: str, api_type: Optional[str] = None
    ) -> List[Dict]:
        """查询用户的用量统计。

        参数:
            user_id: 用户 ID（UUID 字符串）
            api_type: 可选过滤（llm / image / search / rembg / enhance）

        返回:
            按时间倒序的最近 100 条记录，每条：
            {"id", "api_type", "count", "cost", "timestamp"}
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            logger.warning("查询用量统计失败：非法 user_id %r", user_id)
            return []

        stmt = select(UsageStat).where(UsageStat.user_id == uid)
        if api_type:
            stmt = stmt.where(UsageStat.api_type == api_type)
        stmt = stmt.order_by(UsageStat.timestamp.desc()).limit(100)

        async with self.db() as session:
            result = await session.execute(stmt)
            rows = result.scalars().all()

        return [
            {
                "id": str(row.id),
                "api_type": row.api_type,
                "count": row.count,
                "cost": float(row.cost),
                "timestamp": (
                    row.timestamp.isoformat() if row.timestamp else None
                ),
            }
            for row in rows
        ]

    async def record_usage(
        self,
        user_id: str,
        api_type: str,
        count: int = 1,
        cost: float = 0.0,
    ) -> None:
        """记录一次 API 调用到 usage_stats 表。

        参数:
            user_id: 用户 ID（UUID 字符串）
            api_type: 接口类型（llm / image / search / rembg / enhance）
            count: 调用次数
            cost: 本次调用成本（美元）
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            logger.warning("记录用量失败：非法 user_id %r", user_id)
            return

        stat = UsageStat(
            user_id=uid,
            api_type=api_type,
            count=int(count),
            cost=Decimal(str(cost)),
            timestamp=datetime.now(timezone.utc),
        )
        async with self.db() as session:
            async with session.begin():
                session.add(stat)

        logger.debug(
            "记录用量: user_id=%s api_type=%s count=%d cost=%.6f",
            user_id, api_type, count, cost,
        )
