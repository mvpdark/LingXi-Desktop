"""TDD tests: MemoryManager should reuse a shared httpx.AsyncClient."""
import asyncio
import inspect
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from services.memory_manager import MemoryManager


def _make_manager(**kwargs):
    defaults = dict(
        db_session_factory=MagicMock(),
        api_base='http://example.com/v1',
        api_keys=['sk-test-key'],
        model='gpt-4o-mini',
    )
    defaults.update(kwargs)
    return MemoryManager(**defaults)


class MemoryManagerSharedClientTest(unittest.TestCase):
    """TDD: MemoryManager should reuse a shared httpx.AsyncClient."""

    def test_client_starts_none(self):
        mgr = _make_manager()
        self.assertIsNone(mgr._client)

    def test_get_client_creates_lazy_instance(self):
        mgr = _make_manager()
        client = asyncio.run(mgr._get_client())
        self.assertIsNotNone(client)
        self.assertFalse(client.is_closed)

    def test_get_client_returns_same_instance(self):
        mgr = _make_manager()
        c1 = asyncio.run(mgr._get_client())
        c2 = asyncio.run(mgr._get_client())
        self.assertIs(c1, c2)

    def test_close_resets_client_to_none(self):
        mgr = _make_manager()
        asyncio.run(mgr._get_client())
        asyncio.run(mgr.close())
        self.assertIsNone(mgr._client)

    def test_close_idempotent_no_client(self):
        mgr = _make_manager()
        asyncio.run(mgr.close())
        self.assertIsNone(mgr._client)

    def test_close_then_get_client_creates_fresh(self):
        mgr = _make_manager()
        c1 = asyncio.run(mgr._get_client())
        asyncio.run(mgr.close())
        c2 = asyncio.run(mgr._get_client())
        self.assertIsNot(c1, c2)
        self.assertFalse(c2.is_closed)

    def test_get_client_is_async(self):
        mgr = _make_manager()
        self.assertTrue(inspect.iscoroutinefunction(mgr._get_client))

    def test_close_is_async(self):
        mgr = _make_manager()
        self.assertTrue(inspect.iscoroutinefunction(mgr.close))


if __name__ == '__main__':
    unittest.main()
