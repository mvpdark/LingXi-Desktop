"""TDD: lifespan shutdown must reference correct variable names.

Verifies that server.py shutdown section calls webdav.close()
(not the non-existent webdav_service.close()) and that
ImageService._get_client is protected by an asyncio.Lock
to prevent concurrent client creation.
"""
import asyncio
import inspect
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from services.image_service import ImageService
from services.llm_service import LLMService


class LifespanShutdownVariableNameTest(unittest.TestCase):
    """The shutdown section must use the correct variable name webdav."""

    def setUp(self):
        server_path = Path(__file__).resolve().parents[1] / "src" / "server.py"
        self.source = server_path.read_text(encoding="utf-8")

    def test_shutdown_does_not_reference_webdav_service(self):
        """webdav_service.close() must not appear."""
        self.assertNotIn("webdav_service.close()", self.source)

    def test_shutdown_calls_webdav_close(self):
        """Shutdown must call webdav.close()."""
        self.assertIn("webdav.close()", self.source)


class ImageServiceClientLockTest(unittest.TestCase):
    """TDD: _get_client must use asyncio.Lock to prevent concurrent creation."""

    def test_get_client_has_lock_attribute(self):
        """ImageService must initialise an asyncio.Lock in __init__."""
        src = inspect.getsource(ImageService.__init__)
        self.assertIn("Lock", src)

    def test_get_client_uses_lock(self):
        """_get_client must acquire a lock before creating the client."""
        src = inspect.getsource(ImageService._get_client)
        self.assertIn("lock", src.lower())

    def test_concurrent_get_client_creates_single_instance(self):
        """Two concurrent _get_client calls must return the same client."""
        service = object.__new__(ImageService)
        service._client = None
        service._lock = asyncio.Lock()

        async def run():
            c1, c2 = await asyncio.gather(
                service._get_client(),
                service._get_client(),
            )
            return c1, c2

        c1, c2 = asyncio.run(run())
        self.assertIs(c1, c2)




class LLMServiceClientLockTest(unittest.TestCase):
    """TDD: _get_client must use asyncio.Lock to prevent concurrent creation.

    LLMService._get_client currently lacks asyncio.Lock (unlike ImageService),
    so concurrent calls can create multiple AsyncClient instances, leaking
    TCP connections under high WebSocket concurrency.
    """

    def test_get_client_has_lock_attribute(self):
        """LLMService must initialise an asyncio.Lock in __init__."""
        src = inspect.getsource(LLMService.__init__)
        self.assertIn("Lock", src)

    def test_get_client_uses_lock(self):
        """_get_client must acquire a lock before creating the client."""
        src = inspect.getsource(LLMService._get_client)
        self.assertIn("lock", src.lower())

    def test_concurrent_get_client_creates_single_instance(self):
        """Two concurrent _get_client calls must return the same client."""
        service = object.__new__(LLMService)
        service._client = None
        service._lock = asyncio.Lock()

        async def run():
            c1, c2 = await asyncio.gather(
                service._get_client(),
                service._get_client(),
            )
            return c1, c2

        c1, c2 = asyncio.run(run())
        self.assertIs(c1, c2)


if __name__ == "__main__":
    unittest.main()
