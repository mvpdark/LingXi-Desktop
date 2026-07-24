"""TDD tests for RembgService - should return bytes, not base64 data URLs.

The service layer should return raw bytes (consistent with ImageService),
leaving base64 encoding to the API layer.
"""
import asyncio
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

# Inject a fake rembg module before importing RembgService
# so tests can run without the real rembg package installed.
_fake_rembg = types.ModuleType("rembg")
_fake_rembg.remove = MagicMock(return_value=b"fake-png-bytes")
_fake_rembg.new_session = MagicMock(return_value=MagicMock())
sys.modules.setdefault("rembg", _fake_rembg)

from services.rembg_service import RembgService  # noqa: E402


class RembgServiceBytesResultTest(unittest.TestCase):
    """TDD: RembgService should return bytes, not base64 data URLs."""

    def test_remove_background_returns_bytes_not_data_url(self):
        service = RembgService()
        _fake_rembg.remove.return_value = b"fake-png-bytes"
        result = asyncio.run(service.remove_background(b"input"))
        self.assertTrue(result["success"])
        self.assertIn("images", result)
        self.assertNotIn("image", result)
        self.assertIn("bytes", result["images"][0])

    def test_remove_background_does_not_base64_encode(self):
        service = RembgService()
        _fake_rembg.remove.return_value = b"fake-png-bytes"
        result = asyncio.run(service.remove_background(b"input"))
        self.assertNotIn("data:image", repr(result))
        self.assertNotIn("base64", repr(result))

    def test_remove_background_includes_format(self):
        service = RembgService()
        _fake_rembg.remove.return_value = b"fake-png-bytes"
        result = asyncio.run(service.remove_background(b"input"))
        self.assertEqual(result["images"][0]["format"], "png")

    def test_remove_background_exception_returns_error(self):
        service = RembgService()
        _fake_rembg.remove.side_effect = RuntimeError("boom")
        result = asyncio.run(service.remove_background(b"input"))
        self.assertFalse(result["success"])
        self.assertIn("error", result)
        _fake_rembg.remove.side_effect = None


class RembgServiceConcurrencyTest(unittest.TestCase):
    """Existing test: fixed concurrency of 2."""

    def test_uses_fixed_concurrency_of_two(self):
        self.assertEqual(RembgService()._semaphore._value, 2)


if __name__ == "__main__":
    unittest.main()
