import asyncio
import base64
import inspect
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from services.image_service import ImageService


class ImageServiceResponseTest(unittest.TestCase):
    def test_edit_upload_uses_real_file_mime(self):
        service = object.__new__(ImageService)
        with tempfile.TemporaryDirectory() as cache_dir:
            image_path = Path(cache_dir) / 'photo.webp'
            image_path.write_bytes(b'content')
            self.assertEqual(service._image_mime(image_path), 'image/webp')

    def test_extract_result_does_not_expose_raw_base64(self):
        """_extract_images returns bytes, not raw base64 or file paths."""
        service = object.__new__(ImageService)
        result = asyncio.run(service._extract_images(
            {"created": 123, "data": [{"b64_json": base64.b64encode(b"image").decode()}]},
            "png"))
        self.assertTrue(result["success"])
        self.assertNotIn("raw", result)
        self.assertNotIn("b64_json", repr(result))


class ImageServiceSharedClientTest(unittest.TestCase):
    """TDD: ImageService should reuse a shared httpx.AsyncClient."""

    def test_client_starts_none(self):
        service = object.__new__(ImageService)
        service._client = None
        service._lock = asyncio.Lock()
        self.assertIsNone(service._client)

    def test_get_client_creates_lazy_instance(self):
        service = object.__new__(ImageService)
        service._client = None
        service._lock = asyncio.Lock()
        client = asyncio.run(service._get_client())
        self.assertIsNotNone(client)
        self.assertFalse(client.is_closed)

    def test_get_client_returns_same_instance(self):
        service = object.__new__(ImageService)
        service._client = None
        service._lock = asyncio.Lock()
        client1 = asyncio.run(service._get_client())
        client2 = asyncio.run(service._get_client())
        self.assertIs(client1, client2)

    def test_close_resets_client_to_none(self):
        service = object.__new__(ImageService)
        service._client = None
        service._lock = asyncio.Lock()
        asyncio.run(service._get_client())
        asyncio.run(service.close())
        self.assertIsNone(service._client)


class ImageServiceBytesResultTest(unittest.TestCase):
    """TDD: _extract_images returns bytes, not file paths."""

    def test_extract_images_returns_bytes_not_path(self):
        service = object.__new__(ImageService)
        result = asyncio.run(service._extract_images(
            {"data": [{"b64_json": base64.b64encode(b"fake-image").decode()}]},
            "png"))
        self.assertTrue(result["success"])
        self.assertIn("bytes", result["images"][0])
        self.assertNotIn("path", result["images"][0])

    def test_extract_images_decodes_base64_correctly(self):
        service = object.__new__(ImageService)
        raw = b"\x89PNG\r\n\x1a\nfake-png-data"
        result = asyncio.run(service._extract_images(
            {"data": [{"b64_json": base64.b64encode(raw).decode()}]},
            "png"))
        self.assertEqual(result["images"][0]["bytes"], raw)

    def test_extract_images_no_disk_write(self):
        """_extract_images must not write any files to disk."""
        service = object.__new__(ImageService)
        with tempfile.TemporaryDirectory() as cache_dir:
            service.cache_dir = cache_dir
            asyncio.run(service._extract_images(
                {"data": [{"b64_json": base64.b64encode(b"image").decode()}]},
                "png"))
            remaining = list(Path(cache_dir).rglob("*"))
            self.assertEqual(remaining, [])


class ImageServiceEditSignatureTest(unittest.TestCase):
    """TDD: edit_image should accept image_bytes, not image_path."""

    def test_edit_image_has_image_bytes_param(self):
        sig = inspect.signature(ImageService.edit_image)
        self.assertIn("image_bytes", sig.parameters)

    def test_edit_image_does_not_have_image_path_param(self):
        sig = inspect.signature(ImageService.edit_image)
        self.assertNotIn("image_path", sig.parameters)

    def test_edit_image_has_mask_bytes_param(self):
        sig = inspect.signature(ImageService.edit_image)
        self.assertIn("mask_bytes", sig.parameters)


if __name__ == "__main__":
    unittest.main()
