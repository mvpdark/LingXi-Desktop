"""TDD tests for BillingService shared httpx.AsyncClient.

BillingService.query_key_usage previously created a per-call
``async with httpx.AsyncClient(...)`` on every billing query.
These tests verify the lazy shared-client pattern (matching
ImageService / LLMService) before the implementation lands.
"""

import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from services.billing_service import BillingService


class BillingServiceSharedClientTest(unittest.TestCase):
    """TDD: BillingService should reuse a shared httpx.AsyncClient."""

    def test_client_starts_none(self):
        service = object.__new__(BillingService)
        service._client = None
        self.assertIsNone(service._client)

    def test_get_client_creates_lazy_instance(self):
        service = object.__new__(BillingService)
        service._client = None
        client = asyncio.run(service._get_client())
        self.assertIsNotNone(client)
        self.assertFalse(client.is_closed)

    def test_get_client_returns_same_instance(self):
        service = object.__new__(BillingService)
        service._client = None
        client1 = asyncio.run(service._get_client())
        client2 = asyncio.run(service._get_client())
        self.assertIs(client1, client2)

    def test_close_resets_client_to_none(self):
        service = object.__new__(BillingService)
        service._client = None
        asyncio.run(service._get_client())
        asyncio.run(service.close())
        self.assertIsNone(service._client)

    def test_close_is_safe_when_never_created(self):
        service = object.__new__(BillingService)
        service._client = None
        asyncio.run(service.close())
        self.assertIsNone(service._client)

    def test_close_idempotent(self):
        service = object.__new__(BillingService)
        service._client = None
        asyncio.run(service._get_client())
        asyncio.run(service.close())
        asyncio.run(service.close())
        self.assertIsNone(service._client)

    def test_get_client_recreates_after_close(self):
        service = object.__new__(BillingService)
        service._client = None
        client1 = asyncio.run(service._get_client())
        asyncio.run(service.close())
        client2 = asyncio.run(service._get_client())
        self.assertIsNotNone(client2)
        self.assertFalse(client2.is_closed)
        self.assertIsNot(client1, client2)

    def test_query_key_usage_uses_shared_client(self):
        service = object.__new__(BillingService)
        service._client = None
        service.REQUEST_TIMEOUT = 15
        service.api_base = "https://yunwu.ai"
        service.key_pool = MagicMock()
        service.key_pool.mark_success = MagicMock()
        service.key_pool.mark_failed = MagicMock()

        mock_usage_resp = MagicMock()
        mock_usage_resp.raise_for_status = MagicMock()
        mock_usage_resp.json.return_value = {"total_usage": 100.0}

        mock_sub_resp = MagicMock()
        mock_sub_resp.raise_for_status = MagicMock()
        mock_sub_resp.json.return_value = {
            "hard_limit_usd": 50.0,
            "token_name": "test",
        }

        mock_client = AsyncMock()
        mock_client.get = AsyncMock(
            side_effect=[mock_usage_resp, mock_sub_resp]
        )
        mock_client.is_closed = False

        async def _run():
            service._get_client = AsyncMock(return_value=mock_client)
            result = await service.query_key_usage("sk-fake-key-1234567890")
            service._get_client.assert_awaited()
            self.assertTrue(result["ok"])
            self.assertEqual(mock_client.get.await_count, 2)

        asyncio.run(_run())


if __name__ == "__main__":
    unittest.main()
