"""TDD tests for SearchService shared httpx.AsyncClient.

SearchService._call_tavily previously created a per-call
``async with httpx.AsyncClient(...)`` on every search request.
These tests verify the lazy shared-client pattern (matching
ImageService / LLMService) before the implementation lands.
"""

import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from services.search_service import SearchService


class SearchServiceSharedClientTest(unittest.TestCase):
    """TDD: SearchService should reuse a shared httpx.AsyncClient."""

    def test_client_starts_none(self):
        service = object.__new__(SearchService)
        service._client = None
        self.assertIsNone(service._client)

    def test_get_client_creates_lazy_instance(self):
        service = object.__new__(SearchService)
        service._client = None
        client = asyncio.run(service._get_client())
        self.assertIsNotNone(client)
        self.assertFalse(client.is_closed)

    def test_get_client_returns_same_instance(self):
        service = object.__new__(SearchService)
        service._client = None
        client1 = asyncio.run(service._get_client())
        client2 = asyncio.run(service._get_client())
        self.assertIs(client1, client2)

    def test_close_resets_client_to_none(self):
        service = object.__new__(SearchService)
        service._client = None
        asyncio.run(service._get_client())
        asyncio.run(service.close())
        self.assertIsNone(service._client)

    def test_close_is_safe_when_never_created(self):
        service = object.__new__(SearchService)
        service._client = None
        asyncio.run(service.close())
        self.assertIsNone(service._client)

    def test_close_idempotent(self):
        service = object.__new__(SearchService)
        service._client = None
        asyncio.run(service._get_client())
        asyncio.run(service.close())
        asyncio.run(service.close())
        self.assertIsNone(service._client)

    def test_get_client_recreates_after_close(self):
        service = object.__new__(SearchService)
        service._client = None
        client1 = asyncio.run(service._get_client())
        asyncio.run(service.close())
        client2 = asyncio.run(service._get_client())
        self.assertIsNotNone(client2)
        self.assertFalse(client2.is_closed)
        self.assertIsNot(client1, client2)

    def test_call_tavily_uses_shared_client(self):
        service = object.__new__(SearchService)
        service._client = None

        mock_resp = MagicMock()
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = {"results": [], "images": []}

        mock_client = AsyncMock()
        mock_client.post = AsyncMock(return_value=mock_resp)
        mock_client.is_closed = False

        async def _run():
            service._get_client = AsyncMock(return_value=mock_client)
            await service._call_tavily("fake-key", "test query", 5)
            service._get_client.assert_awaited()
            mock_client.post.assert_awaited_once()

        asyncio.run(_run())


if __name__ == "__main__":
    unittest.main()
