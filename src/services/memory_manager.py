"""三层混合记忆系统（PostgreSQL 多租户版）。

参考 LangChain ConversationSummaryBufferMemory + Letta self-editing memory 设计：
- 短期记忆：滑动窗口，保留最近 N 条对话原文（进程内存，按 (user_id, session_id) 隔离）
- 中期记忆：窗口溢出时调用 LLM 压缩为摘要（memory_summaries 表，按 (user_id, session_id) 隔离）
- 长期记忆：用户画像/关键事实（memory_profiles 表，按 user_id 隔离）

相比旧版 JSON 文件存储的变更：
- 删除全局共享的 profile.json 与各会话 {session_id}_summary.json 文件读写
- 所有持久化数据走 PostgreSQL（INSERT ... ON CONFLICT DO UPDATE）
- 多租户隔离：画像按 user_id，摘要按 (user_id, session_id)，
  短期记忆按 (user_id, session_id) 元组作为 key
- 公开方法全部改为 async；数据库 / LLM 异常只记录日志，不中断聊天主流程
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections import OrderedDict, deque
from typing import Any, Deque, Dict, List, Optional

import httpx
from sqlalchemy import delete, func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert

# 兼容两种包布局：项目根在 sys.path（src.*）或 src 目录在 sys.path（*）
try:
    from src.db.models import MemoryProfile, MemorySummary
except ImportError:  # pragma: no cover
    from db.models import MemoryProfile, MemorySummary

try:
    from services.key_pool import KeyPool
    from utils.llm_helpers import extract_response_text
except ImportError:  # pragma: no cover
    from .key_pool import KeyPool
    from ..utils.llm_helpers import extract_response_text

logger = logging.getLogger(__name__)


class MemoryManager:
    """三层记忆统一管理器（PostgreSQL 持久化 + 多租户隔离）。

    调度逻辑：
    - init_session：加载画像（长期）+ 摘要（中期），并从历史构建短期窗口
    - get_context：组装 {profile, summary, short_term} 注入 LLM prompt
    - add_message：追加短期记忆；窗口满时异步触发摘要压缩
    - generate_summary / update_profile：UPSERT 到 PostgreSQL
    """

    SUMMARY_PROMPT = (
        "你是对话摘要助手。请将以下内容整合为一段简洁的摘要。\n"
        "保留：用户的设计需求、偏好、重要决策、项目信息。\n"
        "省略：寒暄、重复内容、无关细节。\n"
        "摘要应该用第三人称，简洁客观。\n\n"
        "现有摘要：\n{existing_summary}\n\n"
        "待压缩对话：\n{dialog}\n\n"
        "请输出更新后的摘要（不超过300字）："
    )

    #: 进程内缓存的短期记忆会话数上限（LRU 逐出，防内存膨胀）
    MAX_CACHED_SESSIONS = 1000

    def __init__(
        self,
        db_session_factory,
        api_base: str,
        api_keys: List[str],
        model: str,
        max_short_term: int = 20,
    ):
        self.db = db_session_factory
        self.api_base = api_base
        self.api_keys = api_keys if isinstance(api_keys, list) else ([api_keys] if api_keys else [])
        self.key_pool = KeyPool(self.api_keys)
        self.model = model
        self.max_short_term = max_short_term

        # 短期记忆（内存）：(user_id, session_id) -> deque[message]
        # OrderedDict 用于 LRU：命中时移到末尾，超出容量逐出最久未用
        self._short_term: "OrderedDict[tuple, Deque[Dict[str, Any]]]" = OrderedDict()

        # 读缓存（写穿透）：减少 get_context 的 DB 往返
        # OrderedDict + LRU（与 _short_term 同模式）：上限 MAX_CACHED_SESSIONS，
        # 命中移到末尾，超出容量逐出最久未用，防内存膨胀
        self._profile_cache: "OrderedDict[str, Dict[str, Any]]" = OrderedDict()  # user_id -> profile_data
        self._summary_cache: "OrderedDict[tuple, str]" = OrderedDict()           # (user_id, session_id) -> summary

        # 后台任务强引用集合：防止 asyncio.create_task 创建的摘要压缩任务
        # 被 GC 提前回收（event loop 仅持有弱引用）
        self._bg_tasks: set = set()
        # 惰性创建的共享 AsyncClient（首次调用 _get_client 时初始化）
        self._client: httpx.AsyncClient | None = None
        # 并发锁：防止多个协程同时首次创建 client
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------------
    # 内部工具
    # ------------------------------------------------------------------
    @staticmethod
    def _to_uuid(user_id: Any) -> Optional[uuid.UUID]:
        """将 str / UUID 形式的 user_id 规范化为 UUID，非法时返回 None。"""
        if isinstance(user_id, uuid.UUID):
            return user_id
        try:
            return uuid.UUID(str(user_id))
        except (ValueError, AttributeError, TypeError):
            logger.warning("MemoryManager: 非法 user_id: %r", user_id)
            return None

    @staticmethod
    def _cache_get(cache: "OrderedDict", key: Any) -> Any:
        """LRU 读缓存：命中时移到末尾（最近使用），未命中返回 None。"""
        try:
            value = cache[key]
        except KeyError:
            return None
        cache.move_to_end(key)
        return value

    def _cache_put(self, cache: "OrderedDict", key: Any, value: Any) -> None:
        """LRU 写缓存：插入/更新并移到末尾，超出容量逐出最久未用。"""
        cache[key] = value
        cache.move_to_end(key)
        while len(cache) > self.MAX_CACHED_SESSIONS:
            evicted, _ = cache.popitem(last=False)
            logger.debug("读缓存 LRU 逐出: %s", evicted)

    def _get_short_term(self, user_id: str, session_id: str) -> Deque[Dict[str, Any]]:
        """获取（或创建）指定租户的短期记忆窗口，并做 LRU 维护。"""
        key = (str(user_id), session_id)
        stm = self._short_term.get(key)
        if stm is None:
            stm = deque(maxlen=self.max_short_term)
            self._short_term[key] = stm
            # LRU 逐出最久未使用的会话窗口
            while len(self._short_term) > self.MAX_CACHED_SESSIONS:
                evicted, _ = self._short_term.popitem(last=False)
                logger.debug("短期记忆 LRU 逐出: %s", evicted)
        else:
            self._short_term.move_to_end(key)
        return stm

    async def _load_profile(self, user_id: str) -> Dict[str, Any]:
        """从 memory_profiles 表加载用户画像（带 LRU 读缓存，失败返回 {}）。"""
        uid = str(user_id)
        cached = self._cache_get(self._profile_cache, uid)
        if cached is not None:
            return cached

        uid_uuid = self._to_uuid(user_id)
        if uid_uuid is None:
            return {}
        try:
            async with self.db() as session:
                row = await session.execute(
                    select(MemoryProfile.profile_data).where(
                        MemoryProfile.user_id == uid_uuid
                    )
                )
                data = row.scalar_one_or_none() or {}
        except Exception as e:
            logger.warning("加载用户画像失败(user_id=%s): %s", uid, e)
            data = {}

        self._cache_put(self._profile_cache, uid, data)
        return data

    async def _load_summary(self, user_id: str, session_id: str) -> str:
        """从 memory_summaries 表加载会话摘要（带 LRU 读缓存，失败返回 ""）。"""
        key = (str(user_id), session_id)
        cached = self._cache_get(self._summary_cache, key)
        if cached is not None:
            return cached

        uid_uuid = self._to_uuid(user_id)
        if uid_uuid is None:
            return ""
        try:
            async with self.db() as session:
                row = await session.execute(
                    select(MemorySummary.summary).where(
                        MemorySummary.user_id == uid_uuid,
                        MemorySummary.session_id == session_id,
                    )
                )
                text = row.scalar_one_or_none() or ""
        except Exception as e:
            logger.warning(
                "加载会话摘要失败(user_id=%s, session_id=%s): %s",
                user_id, session_id, e,
            )
            text = ""

        self._cache_put(self._summary_cache, key, text)
        return text

    async def _upsert_summary(
        self, uid_uuid: uuid.UUID, session_id: str, summary: str
    ) -> bool:
        """UPSERT 会话摘要到 memory_summaries（ON CONFLICT (user_id, session_id)）。"""
        stmt = pg_insert(MemorySummary).values(
            user_id=uid_uuid,
            session_id=session_id,
            summary=summary,
        )
        stmt = stmt.on_conflict_do_update(
            constraint="uq_memory_summaries_user_session",
            set_={"summary": stmt.excluded.summary, "updated_at": func.now()},
        )
        try:
            async with self.db() as session:
                await session.execute(stmt)
                await session.commit()
            return True
        except Exception as e:
            logger.warning(
                "写入会话摘要失败(user_id=%s, session_id=%s): %s",
                uid_uuid, session_id, e,
            )
            return False

    async def _get_client(self) -> httpx.AsyncClient:
        """返回共享的 httpx.AsyncClient（惰性创建，并发安全）。

        首次调用时创建 client，后续复用同一实例，
        避免 _call_llm 每次都新建 TCP 连接。
        使用 asyncio.Lock 防止高并发下多个协程同时创建 client 实例。
        """
        if self._client is not None and not self._client.is_closed:
            return self._client
        async with self._lock:
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

    async def _call_llm(self, prompt: str) -> Optional[str]:
        """调用 LLM API，失败返回 None（不影响主流程）。"""
        api_key = self.key_pool.get_next_key()
        if not api_key:
            logger.warning("MemoryManager: 无可用 API key，跳过 LLM 调用")
            return None

        payload = {
            "model": self.model,
            "input": [{"role": "user", "content": prompt}],
            "stream": False,
        }
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }
        try:
            client = await self._get_client()
            resp = await client.post(
                f"{self.api_base}/v1/responses",
                json=payload, headers=headers,
            )
            resp.raise_for_status()
            data = resp.json()
            text = extract_response_text(data)
            return text.strip() if text else None
        except Exception as e:
            # 标记该 key 失败进入冷却（KeyPool.COOLDOWN_SECONDS），
            # 避免后续 LLM 调用继续打到故障 key 上
            self.key_pool.mark_failed(api_key)
            logger.warning("MemoryManager LLM 调用失败: %s", e)
            return None

    async def _compress_and_store(
        self, user_id: str, session_id: str, messages: List[Dict[str, Any]]
    ) -> Optional[str]:
        """将给定的消息列表压缩进摘要，并 UPSERT + 更新缓存。"""
        if not messages:
            return None

        existing = await self._load_summary(user_id, session_id)
        dialog = "\n".join(
            f"{'用户' if m.get('role') == 'user' else '团团'}: {m.get('content', '')}"
            for m in messages
        )
        prompt = self.SUMMARY_PROMPT.format(
            existing_summary=existing or "（无）",
            dialog=dialog,
        )

        text = await self._call_llm(prompt)
        if not text:
            return None

        uid_uuid = self._to_uuid(user_id)
        if uid_uuid is None:
            return None
        if await self._upsert_summary(uid_uuid, session_id, text):
            self._cache_put(self._summary_cache, (str(user_id), session_id), text)
            return text
        return None

    # ------------------------------------------------------------------
    # 公开接口
    # ------------------------------------------------------------------
    async def init_session(
        self,
        user_id: str,
        session_id: str,
        history: List[Dict[str, Any]],
    ) -> None:
        """初始化会话记忆。

        - 从 memory_profiles 表加载该用户的画像（长期记忆）
        - 从 memory_summaries 表加载该会话的摘要（中期记忆）
        - 短期记忆从 history 的最后 N 条构建
        """
        # 长期 / 中期记忆预热到缓存（DB 异常时降级为空，不影响聊天）
        await asyncio.gather(
            self._load_profile(user_id),
            self._load_summary(user_id, session_id),
        )

        # 短期记忆：从历史记录的最后 max_short_term 条重建窗口
        stm = self._get_short_term(user_id, session_id)
        if history:
            stm.clear()
            for msg in history[-self.max_short_term:]:
                stm.append({
                    "role": msg.get("role", "user"),
                    "content": msg.get("content", ""),
                })

    async def get_context(self, user_id: str, session_id: str) -> Dict[str, Any]:
        """获取完整的记忆上下文（用于注入 LLM prompt）。

        返回:
            {
                "profile": {...},    # 长期记忆（用户画像）
                "summary": "...",    # 中期记忆（会话摘要）
                "short_term": [...]  # 短期记忆（最近对话原文）
            }
        """
        profile, summary = await asyncio.gather(
            self._load_profile(user_id),
            self._load_summary(user_id, session_id),
        )
        stm = self._get_short_term(user_id, session_id)
        return {
            "profile": profile,
            "summary": summary,
            "short_term": list(stm),
        }

    async def add_message(
        self,
        user_id: str,
        session_id: str,
        role: str,
        content: str,
    ) -> None:
        """添加一条消息到短期记忆。

        窗口满（达到 max_short_term）时，异步触发最早一轮对话的摘要压缩，
        随后将该轮从窗口中移除（滑动窗口语义）。
        """
        stm = self._get_short_term(user_id, session_id)
        stm.append({"role": role, "content": content})

        if len(stm) >= self.max_short_term and len(stm) >= 2:
            # 快照最早一轮（2 条），交给后台任务压缩，不阻塞聊天
            overflow = [stm[0], stm[1]]
            # 保存任务强引用，防止 event loop 仅持弱引用导致任务被 GC 回收；
            # 任务完成后经 done_callback 自动从集合中移除
            t = asyncio.create_task(
                self._compress_and_store(user_id, session_id, overflow)
            )
            self._bg_tasks.add(t)
            t.add_done_callback(self._bg_tasks.discard)
            stm.popleft()
            stm.popleft()

    async def generate_summary(self, user_id: str, session_id: str) -> Optional[str]:
        """调用 LLM 生成会话摘要（中期记忆）。

        - 读取短期记忆中的当前对话
        - 与现有摘要合并后调用 LLM 生成新摘要
        - UPSERT 到 memory_summaries 表
        - 成功返回摘要文本；失败（LLM/DB 异常）返回 None
        """
        stm = self._get_short_term(user_id, session_id)
        if not stm:
            return None
        return await self._compress_and_store(user_id, session_id, list(stm))

    async def update_profile(
        self,
        user_id: str,
        profile_data: Dict[str, Any],
    ) -> None:
        """更新用户画像（长期记忆），UPSERT 到 memory_profiles 表。"""
        uid_uuid = self._to_uuid(user_id)
        if uid_uuid is None:
            return

        stmt = pg_insert(MemoryProfile).values(
            user_id=uid_uuid,
            profile_data=profile_data,
        )
        stmt = stmt.on_conflict_do_update(
            index_elements=["user_id"],
            set_={
                "profile_data": stmt.excluded.profile_data,
                "updated_at": func.now(),
            },
        )
        try:
            async with self.db() as session:
                await session.execute(stmt)
                await session.commit()
            self._cache_put(self._profile_cache, str(user_id), profile_data)
        except Exception as e:
            logger.warning("更新用户画像失败(user_id=%s): %s", user_id, e)

    async def clear_profile(self, user_id: str) -> bool:
        """清空用户画像（删除 memory_profiles 中该用户的记录）。"""
        uid_uuid = self._to_uuid(user_id)
        if uid_uuid is None:
            return False
        try:
            async with self.db() as session:
                await session.execute(
                    delete(MemoryProfile).where(MemoryProfile.user_id == uid_uuid)
                )
                await session.commit()
            self._profile_cache.pop(str(user_id), None)
            return True
        except Exception as e:
            logger.warning("清空用户画像失败(user_id=%s): %s", user_id, e)
            return False

    async def clear_session_memory(self, user_id: str, session_id: str) -> bool:
        """清空指定会话的记忆。

        - 删除 memory_summaries 表中该 (user_id, session_id) 的记录
        - 清空对应的短期记忆与读缓存
        """
        key = (str(user_id), session_id)
        self._short_term.pop(key, None)
        self._summary_cache.pop(key, None)

        uid_uuid = self._to_uuid(user_id)
        if uid_uuid is None:
            return False
        try:
            async with self.db() as session:
                await session.execute(
                    delete(MemorySummary).where(
                        MemorySummary.user_id == uid_uuid,
                        MemorySummary.session_id == session_id,
                    )
                )
                await session.commit()
            return True
        except Exception as e:
            logger.warning(
                "清空会话记忆失败(user_id=%s, session_id=%s): %s",
                user_id, session_id, e,
            )
            return False

    async def get_profile(self, user_id: str) -> Optional[Dict[str, Any]]:
        """获取用户画像；不存在或查询失败时返回 None。"""
        profile = await self._load_profile(user_id)
        return profile or None

    async def get_summary(self, user_id: str, session_id: str) -> Optional[str]:
        """获取会话摘要；不存在或查询失败时返回 None。"""
        summary = await self._load_summary(user_id, session_id)
        return summary or None
