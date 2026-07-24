"""TDD tests: LLMService should reuse a shared httpx.AsyncClient.

Currently chat_stream() and chat() create a new AsyncClient per call,
which wastes TCP connections. These tests verify the shared client pattern.
"""
import asyncio
import inspect
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from services.llm_service import LLMService


def make_mock_config():
    """Create a minimal mock config for LLMService."""
    cfg = MagicMock()
    cfg.llm_api_base = "https://api.example.com"
    cfg.llm_api_keys = ["key1", "key2"]
    cfg.llm_api_key = ""
    cfg.llm_model = "test-model"
    return cfg


class LLMServiceSharedClientTest(unittest.TestCase):
    """TDD: LLMService should reuse a shared httpx.AsyncClient."""

    def test_client_starts_none(self):
        svc = LLMService(make_mock_config())
        self.assertIsNone(svc._client)

    def test_get_client_creates_lazy_instance(self):
        svc = LLMService(make_mock_config())
        client = asyncio.run(svc._get_client())
        self.assertIsNotNone(client)
        self.assertFalse(client.is_closed)

    def test_get_client_returns_same_instance(self):
        svc = LLMService(make_mock_config())
        c1 = asyncio.run(svc._get_client())
        c2 = asyncio.run(svc._get_client())
        self.assertIs(c1, c2)

    def test_close_resets_client_to_none(self):
        svc = LLMService(make_mock_config())
        asyncio.run(svc._get_client())
        asyncio.run(svc.close())
        self.assertIsNone(svc._client)

    def test_close_is_safe_when_client_never_created(self):
        svc = LLMService(make_mock_config())
        asyncio.run(svc.close())
        self.assertIsNone(svc._client)


class LLMServiceChatStreamUsesSharedClientTest(unittest.TestCase):
    """TDD: chat_stream must use the shared client, not create a new one."""

    def test_chat_stream_does_not_create_async_client_context(self):
        source = inspect.getsource(LLMService.chat_stream)
        self.assertNotIn(
            "httpx.AsyncClient(timeout",
            source,
            "chat_stream should use shared _get_client(), not create a new AsyncClient",
        )

    def test_chat_does_not_create_async_client_context(self):
        source = inspect.getsource(LLMService.chat)
        self.assertNotIn(
            "httpx.AsyncClient(timeout",
            source,
            "chat should use shared _get_client(), not create a new AsyncClient",
        )


class LLMServiceChatStreamIntegrationTest(unittest.TestCase):
    """Integration test: chat_stream uses shared client for HTTP calls."""

    def test_chat_stream_uses_shared_client_instance(self):
        svc = LLMService(make_mock_config())
        captured = {}

        async def fake_get_client():
            client = MagicMock()
            captured['client'] = client
            mock_response = AsyncMock()
            mock_response.raise_for_status = MagicMock()

            async def fake_aiter_lines():
                yield 'data: {"type":"response.output_text.delta","delta":"hello"}'
                yield 'data: [DONE]'

            mock_response.aiter_lines = fake_aiter_lines

            class FakeStreamCM:
                async def __aenter__(self):
                    return mock_response
                async def __aexit__(self, *args):
                    return False

            client.stream = MagicMock(return_value=FakeStreamCM())
            return client

        svc._get_client = fake_get_client

        async def run():
            chunks = []
            async for chunk in svc.chat_stream("hi", [], timeout=5, max_retries=1):
                chunks.append(chunk)
            return chunks

        result = asyncio.run(run())
        self.assertIn('client', captured)
        self.assertTrue(len(result) > 0)


if __name__ == "__main__":
    unittest.main()
