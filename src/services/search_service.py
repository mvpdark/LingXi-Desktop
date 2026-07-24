"""Tavily 搜索服务（PostgreSQL 多租户版）— 多 Key 智能均衡 + 用量记账。

阶段 3 多租户改造要点：
- 用户级用量持久化到 PostgreSQL ``usage_stats`` 表
  （api_type='tavily'，period='YYYY-MM' 当月字符串），通过
  INSERT ... ON CONFLICT DO UPDATE 原子 upsert 累加 count；
  旧版 JSON 文件持久化（cache/tavily_usage.json）已完全移除
- user_id 约定：字符串形式的 UUID（与 JWT sub / request.state.user_id
  一致）。user_id 为 None（匿名 / 服务内部调用）或非法 UUID 时，
  搜索功能正常执行，仅跳过记账
- Key 级用量计数保留在进程内存中（仅用于均衡选 Key），进程重启后
  从零开始重新计数；用户级历史用量不受影响，始终可从 usage_stats
  聚合查询。这是因为 Key 级额度只是选 Key 的启发式依据，重启后各 Key
  从同一起点重新均衡，影响可接受
- 所有数据库操作使用构造时传入的 db_session_factory（async_sessionmaker），
  模式：``async with self.db() as session: ...``；
  数据库异常只记日志并返回安全默认值，不向外抛异常

保留旧版核心机制：
1. 新 session 分配 Key 时，优先选本月剩余额度最多的（智能均衡，非简单轮询）
2. 同一次对话（同 session_id）始终用同一个 Key
3. Key 出错时自动切换重试
4. Key 级内存计数每月自动重置（跨月视为满额）

用法::

    search = SearchService(async_session_factory, api_keys=["key1", "key2"])
    envelope = await search.search("北欧风格客厅", user_id=uid, session_id="abc")
    # envelope == {"results": [...], "images": [...]}
    await search.record_usage(uid)                 # 通常由 search 内部调用
    stats = await search.get_usage_stats(uid)      # 单用户
    stats = await search.get_usage_stats()         # 全局（管理员视角）
"""

from __future__ import annotations

import asyncio
import logging
import random
import threading
import uuid
from datetime import datetime, timezone
from typing import Any, Optional

import httpx
from sqlalchemy import func, select
try:
    from sqlalchemy.dialects.postgresql import insert as pg_insert
except ImportError:
    pg_insert = None  # SQLite fallback

# 兼容两种包布局：项目根在 sys.path（src.*）或 src 目录在 sys.path（*）
try:
    from src.db.models import UsageStat
except ImportError:  # pragma: no cover
    from db.models import UsageStat

logger = logging.getLogger(__name__)

#: usage_stats.api_type 取值（Tavily 搜索）
SERVICE_TYPE = "tavily"


class SearchService:
    """Tavily 搜索服务（PostgreSQL 多租户版），多 Key 智能均衡。

    参数:
        db_session_factory: async_sessionmaker 实例（见 src/db/database.py）
        api_keys: Tavily API Key 列表
        monthly_quota: 每个 Key 每月免费额度（默认 MONTHLY_QUOTA）
    """

    #: 每个 Key 每月免费额度
    MONTHLY_QUOTA = 1000

    #: _session_keys 容量上限：防止长进程内会话绑定表无限增长。
    #: 超出时随机逐出一个既有绑定（会话 Key 绑定只是均衡启发式，
    #: 被逐出的会话下次搜索会重新智能分配，不影响正确性）。
    MAX_SESSION_BINDINGS = 5000

    def __init__(
        self,
        db_session_factory,
        api_keys: list[str] | None = None,
        monthly_quota: int = MONTHLY_QUOTA,
    ):
        self.db = db_session_factory
        self._keys: list[str] = [
            k.strip() for k in (api_keys or []) if k and k.strip()
        ]
        # "user_id:session_id" → 绑定的 Key（键含 user_id 前缀，
        # 防止不同租户使用相同 session_id 时共享绑定，见 _session_key_name）
        self._session_keys: dict[str, str] = {}
        self._lock = threading.Lock()
        self._quota = monthly_quota

        # Key 级用量计数（仅进程内存）：
        # { key: {"count": 50, "month": "2026-07"}, ... }
        # 进程重启后从零开始；跨月自动重置
        self._usage: dict[str, dict] = {}
        for key in self._keys:
            self._usage[key] = {"count": 0, "month": self._current_month()}

        if self._keys:
            logger.info(
                "🔍 SearchService 初始化 | %d 个 Key | 月额度 %d/Key",
                len(self._keys), self._quota,
            )
        else:
            logger.info("🔍 SearchService 未配置 Key，搜索功能禁用")

        # 惰性创建的共享 AsyncClient（首次调用 _get_client 时初始化）
        self._client: httpx.AsyncClient | None = None
        # 并发锁：防止多个协程同时首次创建 client（与 self._lock threading.Lock 区分）
        self._client_lock = asyncio.Lock()

    # ================================================================
    # 内部工具
    # ================================================================

    @staticmethod
    def _to_uuid(user_id: Any) -> Optional[uuid.UUID]:
        """将字符串形式的 user_id 转为 UUID，非法输入返回 None。"""
        try:
            return uuid.UUID(str(user_id))
        except (ValueError, AttributeError, TypeError):
            return None

    @staticmethod
    def _current_month() -> str:
        """当前月份（UTC），格式 '2026-07'。

        统一使用 UTC 月份作为用量记账/额度重置口径：
        服务器可能部署在不同时区，UTC 保证跨月边界全局一致，
        避免本地时区导致同一时刻不同实例算出不同 period。
        """
        return datetime.now(timezone.utc).strftime("%Y-%m")

    @staticmethod
    def _session_key_name(user_id: Any, session_id: str) -> str:
        """生成会话绑定表的键：``{user_id 或 'anon'}:{session_id}``。

        键中含 user_id 前缀，防止不同租户使用相同 session_id 时
        共享同一条 Key 绑定（跨租户串绑会导致用量归因错误）。
        """
        return f"{user_id or 'anon'}:{session_id}"

    @staticmethod
    def _mask_key(key: str) -> str:
        """Key 掩码显示：只保留前 8 位 + '...'，不泄露完整 Key。"""
        return f"{key[:8]}..."

    # ================================================================
    # Key 级用量计数（进程内存，仅用于均衡选 Key）
    # ================================================================

    def _record_key_usage(self, key: str) -> None:
        """记录一次 Key 级调用（内存计数，跨月自动重置）。"""
        with self._lock:
            current = self._current_month()
            if key not in self._usage:
                self._usage[key] = {"count": 0, "month": current}
            # 月份变了自动重置
            if self._usage[key]["month"] != current:
                self._usage[key] = {"count": 0, "month": current}
            self._usage[key]["count"] += 1

    def _get_remaining(self, key: str) -> int:
        """获取某个 Key 本月剩余额度。"""
        info = self._usage.get(key, {"count": 0, "month": self._current_month()})
        # 如果月份不对，说明是新的月，剩余满额
        if info.get("month") != self._current_month():
            return self._quota
        return max(0, self._quota - info["count"])

    # ================================================================
    # 用量统计（usage_stats 表，按 user_id 多租户隔离）
    # ================================================================

    async def record_usage(self, user_id: str, count: int = 1) -> None:
        """累加该用户当月 Tavily 用量。

        对 usage_stats 表中 (user_id, api_type='tavily', period=当前
        'YYYY-MM') 的记录执行 count += count，使用 PostgreSQL
        INSERT ... ON CONFLICT DO UPDATE 原子 upsert（记录不存在则插入）。

        user_id 为 None 或非法 UUID 时记 warning 并跳过（不记账）。
        数据库异常只记日志，不向外抛异常。
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            logger.warning("记录搜索用量失败：非法 user_id %r，已跳过", user_id)
            return

        period = self._current_month()
        now = datetime.now(timezone.utc)  # 与 _current_month 统一 UTC 口径
        try:
            stmt = pg_insert(UsageStat).values(
                user_id=uid,
                api_type=SERVICE_TYPE,
                period=period,
                count=int(count),
                timestamp=now,
            )
            stmt = stmt.on_conflict_do_update(
                index_elements=["user_id", "api_type", "period"],
                set_={
                    "count": UsageStat.count + stmt.excluded.count,
                    "timestamp": now,
                },
            )
            async with self.db() as session:
                await session.execute(stmt)
                await session.commit()
            logger.debug(
                "搜索用量已记录: user_id=%s period=%s count+=%d",
                user_id, period, count,
            )
        except Exception as exc:
            logger.error(
                "记录搜索用量失败: user_id=%s period=%s err=%s",
                user_id, period, exc,
            )

    async def get_usage_stats(self, user_id: str | None = None) -> dict:
        """获取用量统计。

        - user_id 为 None：全局统计（管理员视角），返回各 Key 的
          进程内用量，Key 以掩码显示：
          ``{key 掩码: {'used': n, 'quota': 1000, 'remaining': m}, ...}``
        - user_id 合法：只返回该用户当月 Tavily 用量（查 usage_stats 表）：
          ``{'tavily': {'used': n, 'period': '2026-07'}}``

        user_id 非法（非 None 但非 UUID）时记 warning 并按单用户场景
        返回 0 用量。数据库异常时返回安全默认值。
        """
        if user_id is None:
            return self._global_key_stats()

        uid = self._to_uuid(user_id)
        period = self._current_month()
        if uid is None:
            logger.warning("查询搜索用量失败：非法 user_id %r", user_id)
            return {SERVICE_TYPE: {"used": 0, "period": period}}

        try:
            async with self.db() as session:
                result = await session.execute(
                    select(func.coalesce(func.sum(UsageStat.count), 0)).where(
                        UsageStat.user_id == uid,
                        UsageStat.api_type == SERVICE_TYPE,
                        UsageStat.period == period,
                    )
                )
                used = int(result.scalar_one())
            return {SERVICE_TYPE: {"used": used, "period": period}}
        except Exception as exc:
            logger.error(
                "查询搜索用量失败: user_id=%s period=%s err=%s",
                user_id, period, exc,
            )
            return {SERVICE_TYPE: {"used": 0, "period": period}}

    def _global_key_stats(self) -> dict:
        """全局 Key 级用量统计（进程内存计数，Key 掩码显示）。"""
        current = self._current_month()
        stats: dict = {}
        for i, key in enumerate(self._keys):
            info = self._usage.get(key, {"count": 0, "month": current})
            count = info["count"] if info.get("month") == current else 0
            mask = self._mask_key(key)
            # 极端情况下两个 Key 前 8 位相同，追加序号避免掩码撞名
            if mask in stats:
                mask = f"{mask}#{i + 1}"
            stats[mask] = {
                "used": count,
                "quota": self._quota,
                "remaining": max(0, self._quota - count),
            }
        return stats

    # ================================================================
    # Key 分配（智能均衡）
    # ================================================================

    @property
    def available(self) -> bool:
        """是否有可用的 Key（至少一个还有额度）。"""
        return any(self._get_remaining(k) > 0 for k in self._keys)

    def _pick_best_key(self) -> Optional[str]:
        """选择剩余额度最多的 Key（智能均衡）。

        如果所有 Key 额度用完，返回 None。
        """
        with self._lock:
            return self._pick_best_key_unlocked()

    def _pick_best_key_unlocked(
        self, exclude: Optional[str] = None
    ) -> Optional[str]:
        """内部方法：不加锁（调用方已持锁）。

        exclude: 可选，排除某个 Key（用于出错切换时避开刚失败的 Key）。
        """
        available = [(k, self._get_remaining(k)) for k in self._keys
                     if k != exclude and self._get_remaining(k) > 0]
        if not available:
            return None
        # 按剩余额度降序，额度相同则按已用次数少的排前面
        available.sort(
            key=lambda x: (-x[1], self._usage.get(x[0], {}).get("count", 0))
        )
        return available[0][0]

    def _bind_session_key(self, bind_name: str, key: str) -> None:
        """写入会话→Key 绑定（内部方法，调用方需已持锁）。

        超出 MAX_SESSION_BINDINGS 容量上限时随机逐出一个既有绑定：
        绑定只是均衡启发式，被逐出的会话下次搜索会重新智能分配，
        不影响正确性，仅可能产生一次 Key 切换日志。
        """
        if bind_name not in self._session_keys:
            while len(self._session_keys) >= self.MAX_SESSION_BINDINGS:
                evicted = random.choice(list(self._session_keys))
                del self._session_keys[evicted]
                logger.debug("🔍 会话绑定表已满，逐出绑定 %s", evicted[:16])
        self._session_keys[bind_name] = key

    def _get_key_for_session(self, session_id: str) -> Optional[str]:
        """获取 session 绑定的 Key。

        - 已绑定的 session → 直接返回绑定的 Key
        - 新 session → 选择剩余额度最多的 Key
        - 如果绑定的 Key 本月已用完 → 重新分配
        """
        if not self._keys:
            return None

        # 绑定表键已含 user_id 前缀（由 search() 经 _session_key_name 生成）
        bind_name = session_id
        with self._lock:
            # 已绑定
            if bind_name in self._session_keys:
                bound_key = self._session_keys[bind_name]
                # 检查绑定 Key 是否还有额度
                if self._get_remaining(bound_key) > 0:
                    return bound_key
                # 额度用完了，需要重新分配
                logger.warning(
                    "🔍 Session %s 绑定的 Key 已用完 (%d/%d)，重新分配",
                    bind_name[:8],
                    self._usage.get(bound_key, {}).get("count", 0),
                    self._quota,
                )

            # 新 session 或额度用完 → 智能选择
            best = self._pick_best_key_unlocked()
            if best:
                self._bind_session_key(bind_name, best)
                idx = self._keys.index(best) + 1
                logger.info(
                    "🔑 Session %s 分配 Key #%d (剩余 %d)",
                    bind_name[:8], idx, self._get_remaining(best),
                )
                return best

            return None

    def _rotate_key(
        self, session_id: str, failed_key: Optional[str] = None
    ) -> Optional[str]:
        """当前 Key 出错时，切换到剩余额度最多的其他可用 Key。

        failed_key: 刚失败的 Key，选新 Key 时将其排除，避免又选回
        同一个故障 Key；无可替代 Key 时返回 None。
        """
        with self._lock:
            old_key = failed_key or self._session_keys.get(session_id)
            if old_key and old_key in self._keys:
                old_idx = self._keys.index(old_key)
                logger.warning("🔍 Key #%d 出错，尝试切换", old_idx + 1)

            best = self._pick_best_key_unlocked(exclude=old_key)
            if best:
                self._bind_session_key(session_id, best)
                idx = self._keys.index(best) + 1
                logger.warning(
                    "🔄 切换到 Key #%d (剩余 %d)", idx, self._get_remaining(best)
                )
                return best

            return None

    # ================================================================
    # 搜索
    # ================================================================

    async def search(
        self,
        query: str,
        user_id: str | None = None,
        session_id: str = "",
        max_results: int = 5,
    ) -> dict:
        """执行 Tavily 搜索，返回完整信封。

        参数:
            query: 搜索关键词
            user_id: 用户 ID（字符串 UUID），用于用量统计；
                     为 None 表示匿名 / 服务内部调用，不记账
            session_id: 会话 ID（用于 Key 绑定，同会话始终用同一 Key）
            max_results: 最大结果数

        返回:
            ``{"results": [...], "images": [...]}``
            - results: [{"title", "url", "content", "score"}, ...]
            - images: 已过滤的图片直链列表（来自 Tavily images 字段）
            无可用 Key、额度耗尽或所有 Key 均失败时返回
            ``{"results": [], "images": []}``。
        """
        empty: dict = {"results": [], "images": []}
        if not self._keys:
            logger.warning("🔍 未配置 Tavily Key，搜索功能禁用")
            return empty

        if not self.available:
            logger.warning("🔍 所有 Key 本月额度已用完")
            return empty

        if not session_id:
            session_id = "_default"
        # 绑定表键带 user_id 前缀，防跨租户共享绑定
        bind_name = self._session_key_name(user_id, session_id)

        # 最多尝试所有有额度的 Key
        tried: set[str] = set()
        for attempt in range(len(self._keys)):
            key = self._get_key_for_session(bind_name)
            if not key:
                break
            if key in tried:
                # 轮换后仍拿到已试过的 Key → 无可替代 Key，退出避免空转
                break

            tried.add(key)
            try:
                result = await self._call_tavily(key, query, max_results)
                # 成功 → 记录 Key 级内存计数
                self._record_key_usage(key)
                idx = self._keys.index(key) + 1
                logger.info(
                    "🔍 搜索成功 | Key #%d | 本月已用 %d/%d",
                    idx, self._usage[key]["count"], self._quota,
                )
                # 用户级用量记账（user_id 为 None 时跳过，不影响搜索结果）
                if user_id is not None:
                    await self.record_usage(user_id, count=1)

                return {
                    "results": result.get("results", []),
                    "images": result.get("images", []),
                }
            except Exception as e:
                logger.warning(
                    "🔍 搜索失败 (attempt %d, Key #%s): %s",
                    attempt + 1,
                    self._keys.index(key) + 1 if key in self._keys else "?",
                    e,
                )
                self._rotate_key(bind_name, failed_key=key)

        logger.error("🔍 所有 Key 均搜索失败")
        return empty

    async def _get_client(self) -> httpx.AsyncClient:
        """返回共享的 httpx.AsyncClient（惰性创建，并发安全）。

        首次调用时创建 client，后续复用同一实例，
        避免 _call_tavily 每次都新建 TCP 连接。
        使用 asyncio.Lock 防止高并发下多个协程同时创建 client 实例。
        """
        if self._client is not None and not self._client.is_closed:
            return self._client
        async with self._client_lock:
            if self._client is not None and not self._client.is_closed:
                return self._client
            self._client = httpx.AsyncClient(timeout=30)
        return self._client

    async def close(self) -> None:
        """关闭共享 AsyncClient，释放连接池资源。

        应在 FastAPI lifespan shutdown 阶段调用。
        """
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
        self._client = None

    async def _call_tavily(
        self,
        api_key: str,
        query: str,
        max_results: int,
        search_depth: str = "basic",
        topic: str = "general",
    ) -> dict:
        """单次 Tavily API 调用。"""
        url = "https://api.tavily.com/search"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        }
        payload = {
            "query": query,
            "max_results": max_results,
            "search_depth": search_depth,
            "topic": topic,
            "include_answer": True,
            "include_images": True,
            "include_image_descriptions": True,
        }

        client = await self._get_client()
        resp = await client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        data = resp.json()

        results = []
        for item in data.get("results", []):
            results.append({
                "title": item.get("title", ""),
                "url": item.get("url", ""),
                "content": item.get("content", ""),
                "score": item.get("score", 0),
            })

        # 提取搜索相关图片（Tavily 返回的 images 列表）
        # 过滤：只保留看起来像图片直链的 URL
        _IMG_EXTS = ('.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.svg')
        images = []
        for img in data.get("images", []):
            img_url = img if isinstance(img, str) else img.get("url", "")
            if img_url and any(
                img_url.lower().rstrip('/').endswith(ext) for ext in _IMG_EXTS
            ):
                images.append(img_url)

        answer = data.get("answer", "")

        return {
            "success": True,
            "results": results,
            "images": images,
            "answer": answer,
            "error": "",
        }

    def format_results_for_llm(
        self, search_data: Any, max_chars: int = 3000
    ) -> str:
        """将搜索结果格式化为 LLM 可读的上下文文本。

        兼容两种输入：
        - search() 当前契约的 dict 信封 ``{"results": [...], "images": [...]}``
          （以及旧版带 ``success/answer`` 字段的信封）
        - 旧版直接传入的结果列表 list[dict]
        """
        if isinstance(search_data, dict):
            # 旧版信封带 success 字段且为 False 时视为失败；
            # 新信封（无 success 键）默认成功
            if not search_data.get("success", True):
                return ""
            results = search_data.get("results", [])
            images = search_data.get("images", [])
            answer = search_data.get("answer", "")
        else:
            results = list(search_data or [])
            images = []
            answer = ""

        if not results and not answer:
            return ""

        parts = []
        if answer:
            parts.append(f"🔍 网络搜索摘要：{answer}")

        if results:
            parts.append("相关网页：")
            for i, r in enumerate(results, 1):
                title = r.get("title", "")
                url = r.get("url", "")
                content = r.get("content", "")
                if len(content) > 500:
                    content = content[:500] + "..."
                parts.append(f"{i}. 【{title}】{url}\n   {content}")

        text = "\n\n".join(parts)

        # 如果有搜索到图片，在文本中告知模型
        if images:
            text += (
                f"\n\n【注意】已为用户搜索到 {len(images)} 张相关图片，"
                "图片已直接显示在对话中，请不要说「无法发送图片」。"
            )

        if len(text) > max_chars:
            text = text[:max_chars] + "\n...(更多结果已省略)"

        return text
