"""TDD tests for EnhanceService - should return bytes, not base64 data URLs.

The service layer should return raw bytes (consistent with ImageService),
leaving base64 encoding to the API layer.
"""
import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from services.enhance_service import EnhanceService


class EnhanceServiceBytesResultTest(unittest.TestCase):
    """TDD: EnhanceService should return bytes, not base64 data URLs."""

    def _make_service(self, edit_result=None):
        """Create an EnhanceService with a mocked image_service."""
        fake = MagicMock()
        fake.edit_image = AsyncMock(return_value=edit_result or {
            "success": True,
            "images": [{"bytes": b"fake-png-bytes", "format": "png"}],
        })
        return EnhanceService(image_service=fake, enabled=True)

    def test_enhance_returns_bytes_not_data_url(self):
        service = self._make_service()
        result = asyncio.run(service.enhance(b"input", mode="super_resolution"))
        self.assertTrue(result["success"])
        self.assertIn("images", result)
        self.assertNotIn("image", result)
        self.assertIn("bytes", result["images"][0])

    def test_enhance_does_not_base64_encode(self):
        service = self._make_service()
        result = asyncio.run(service.enhance(b"input"))
        self.assertNotIn("data:image", repr(result))
        self.assertNotIn("base64", repr(result))

    def test_enhance_preserves_format(self):
        service = self._make_service()
        result = asyncio.run(service.enhance(b"input"))
        self.assertEqual(result["images"][0]["format"], "png")

    def test_enhance_disabled_returns_error(self):
        service = EnhanceService(image_service=None, enabled=False)
        result = asyncio.run(service.enhance(b"input"))
        self.assertFalse(result["success"])

    def test_enhance_failure_propagates(self):
        service = self._make_service(edit_result={
            "success": False,
            "error": "upstream error",
        })
        result = asyncio.run(service.enhance(b"input"))
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "upstream error")

    def test_enhance_exception_returns_error(self):
        fake = MagicMock()
        fake.edit_image = AsyncMock(side_effect=RuntimeError("boom"))
        service = EnhanceService(image_service=fake, enabled=True)
        result = asyncio.run(service.enhance(b"input"))
        self.assertFalse(result["success"])
        self.assertIn("error", result)


if __name__ == "__main__":
    unittest.main()
