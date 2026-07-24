import asyncio
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from utils.ws_task_manager import WebSocketTaskManager


class WebSocketTaskManagerTest(unittest.TestCase):
    """TDD: WebSocketTaskManager cancels old tasks when new ones arrive."""

    def test_starts_with_no_current_task(self):
        mgr = WebSocketTaskManager()
        self.assertIsNone(mgr.current_task)

    def test_cancel_current_noop_when_no_task(self):
        mgr = WebSocketTaskManager()
        asyncio.run(mgr.cancel_current())
        self.assertIsNone(mgr.current_task)

    def test_cancel_current_cancels_running_task(self):
        mgr = WebSocketTaskManager()

        async def long_running():
            await asyncio.sleep(100)

        async def run_test():
            task = asyncio.create_task(long_running())
            mgr.set_current(task)
            await mgr.cancel_current()
            self.assertTrue(task.cancelled())

        asyncio.run(run_test())

    def test_cancel_current_safe_on_completed_task(self):
        mgr = WebSocketTaskManager()

        async def quick():
            return 42

        async def run_test():
            task = asyncio.create_task(quick())
            mgr.set_current(task)
            await task  # let it complete
            await mgr.cancel_current()  # should not raise
            self.assertIsNone(mgr.current_task)

        asyncio.run(run_test())

    def test_set_current_stores_task(self):
        mgr = WebSocketTaskManager()

        async def dummy():
            pass

        async def run_test():
            task = asyncio.create_task(dummy())
            mgr.set_current(task)
            self.assertIs(mgr.current_task, task)
            await task

        asyncio.run(run_test())

    def test_cancel_current_resets_to_none(self):
        mgr = WebSocketTaskManager()

        async def long():
            await asyncio.sleep(100)

        async def run_test():
            task = asyncio.create_task(long())
            mgr.set_current(task)
            await mgr.cancel_current()
            self.assertIsNone(mgr.current_task)

        asyncio.run(run_test())


if __name__ == '__main__':
    unittest.main()
