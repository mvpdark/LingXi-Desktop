"""通用 API Key 轮询器。

支持多个 key 的 round-robin 轮询，失败自动切换。
用于 yunwu（LLM + 图片）等服务的多 key 负载均衡。

特性：
- Round-robin 轮询：每次请求使用下一个 key，均匀分摊负载
- 线程安全：使用 threading.Lock 保护内部状态
- 失败冷却：key 出错时临时跳过（冷却期 60 秒），过后自动恢复
- 向后兼容：单 key 时退化为固定 key，不影响现有逻辑
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Optional

logger = logging.getLogger(__name__)


class KeyPool:
    """多 Key 轮询池。

    使用示例::

        pool = KeyPool(["sk-aaa", "sk-bbb", "sk-ccc"])
        key = pool.get_next_key()          # round-robin 获取
        header = pool.get_auth_header()     # 直接拿 {"Authorization": "Bearer sk-xxx"}
        pool.mark_failed(key)               # 出错时标记，临时跳过
    """

    #: key 失败后的冷却时间（秒）
    COOLDOWN_SECONDS = 60

    def __init__(self, keys: list[str] | None):
        """初始化 KeyPool。

        参数:
            keys: API key 列表，自动过滤空值和空白
        """
        self._keys: list[str] = [
            k.strip() for k in (keys or []) if k and k.strip()
        ]
        self._index = 0
        self._lock = threading.Lock()
        # key -> 失败时间戳
        self._failed: dict[str, float] = {}
        # 最近一次通过 get_next_key()/get_auth_header() 发出的 key，
        # 供调用方在请求失败时定位需要 mark_failed 的 key
        self._last_key: Optional[str] = None

        if not self._keys:
            logger.warning("KeyPool 初始化时 key 列表为空")

    # ------------------------------------------------------------------
    # 属性
    # ------------------------------------------------------------------
    @property
    def available(self) -> bool:
        """是否有可用的 key。"""
        return len(self._keys) > 0

    @property
    def size(self) -> int:
        """key 总数。"""
        return len(self._keys)

    @property
    def current_key(self) -> Optional[str]:
        """最近一次发出的 key（get_next_key/get_auth_header）。

        供调用方在请求失败后对该 key 调用 ``mark_failed``，
        无需改变 get_auth_header 的既有返回签名（向后兼容）。
        尚未发出过 key 时返回 None。
        """
        with self._lock:
            return self._last_key

    # ------------------------------------------------------------------
    # 内部方法
    # ------------------------------------------------------------------
    def _clean_expired_failures(self) -> None:
        """清理过期的失败标记（内部调用，需持锁）。"""
        now = time.time()
        expired = [
            k for k, t in self._failed.items()
            if now - t > self.COOLDOWN_SECONDS
        ]
        for k in expired:
            del self._failed[k]
            logger.info("🔄 key %s...%s 冷却完成，重新启用", k[:6], k[-4:])

    # ------------------------------------------------------------------
    # 公开接口
    # ------------------------------------------------------------------
    def get_next_key(self) -> Optional[str]:
        """获取下一个可用 key（round-robin 轮询）。

        线程安全，自动跳过处于冷却期的 key。
        如果所有 key 都在冷却中，降级返回第一个 key。

        返回:
            API key 字符串；如果没有 key 则返回 None
        """
        if not self._keys:
            return None

        with self._lock:
            self._clean_expired_failures()

            # 尝试找一个未失败的 key
            for _ in range(len(self._keys)):
                key = self._keys[self._index]
                self._index = (self._index + 1) % len(self._keys)

                if key not in self._failed:
                    self._last_key = key
                    return key

            # 所有 key 都在冷却中，降级使用第一个
            logger.warning("⚠️ 所有 key 都在冷却中，降级使用第一个 key")
            self._last_key = self._keys[0]
            return self._keys[0]

    def get_auth_header(self) -> dict:
        """获取 Authorization 请求头。

        等价于::

            key = pool.get_next_key()
            {"Authorization": f"Bearer {key}"}

        返回:
            包含 ``Authorization`` 的字典；如果没有 key 则返回空字典
        """
        key = self.get_next_key()
        if key:
            return {"Authorization": f"Bearer {key}"}
        return {}

    def mark_failed(self, key: str) -> None:
        """标记某个 key 失败，触发冷却期。

        在请求返回 401 / 429 / 5xx 等错误时调用，
        该 key 会在 ``COOLDOWN_SECONDS`` 内被跳过。

        参数:
            key: 失败的 API key
        """
        if not key:
            return

        with self._lock:
            self._failed[key] = time.time()
            remaining = len(self._keys) - len(self._failed)
            logger.warning(
                "⚠️ key %s...%s 标记失败（冷却 %d 秒），剩余可用: %d/%d",
                key[:6], key[-4:], self.COOLDOWN_SECONDS,
                remaining, len(self._keys),
            )

    def mark_success(self, key: str) -> None:
        """标记某个 key 成功，清除失败标记。

        参数:
            key: 成功的 API key
        """
        if not key:
            return

        with self._lock:
            if key in self._failed:
                del self._failed[key]
                logger.info("✅ key %s...%s 恢复正常", key[:6], key[-4:])

    def get_stats(self) -> dict:
        """获取 key 池状态统计。

        返回:
            ``{"total": int, "available": int, "failed": int}``
        """
        with self._lock:
            self._clean_expired_failures()
            return {
                "total": len(self._keys),
                "available": len(self._keys) - len(self._failed),
                "failed": len(self._failed),
            }
