"""
WebSocketTaskManager: per-connection task lifecycle management.

When a new chat message arrives, the previous processing task is cancelled
so the user gets fresh results without waiting for stale output to finish.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Optional

logger = logging.getLogger(__name__)


class WebSocketTaskManager:
    """Manages the current processing task for a single WebSocket connection.

    Usage in WebSocket handler::

        mgr = WebSocketTaskManager()
        while True:
            msg = await ws.receive_json()
            await mgr.cancel_current()          # cancel stale task
            task = asyncio.create_task(handle(msg))
            mgr.set_current(task)
    """

    def __init__(self) -> None:
        self._current_task: Optional[asyncio.Task] = None

    @property
    def current_task(self) -> Optional[asyncio.Task]:
        """The currently active processing task (or None)."""
        return self._current_task

    def set_current(self, task: asyncio.Task) -> None:
        """Register a new processing task as the current one."""
        self._current_task = task

    async def cancel_current(self) -> None:
        """Cancel the current task if it is still running, then reset.

        Safe to call when no task exists or when the task already completed.
        Swallows CancelledError so the caller does not need a try/except.
        """
        task = self._current_task
        self._current_task = None
        if task is None or task.done():
            return
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        except Exception:
            # Task raised a non-cancellation exception during shutdown;
            # log and swallow so the receive loop stays alive.
            logger.debug("Cancelled task raised exception during cleanup", exc_info=True)
