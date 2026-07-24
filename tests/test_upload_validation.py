import io
import unittest

from fastapi import HTTPException, UploadFile
from PIL import Image

from src.utils.upload_validation import (
    MAX_BATCH_BYTES,
    MAX_IMAGE_SIDE,
    MAX_IMAGE_PIXELS,
    MAX_UPLOAD_BYTES,
    PILLOW_SEMAPHORE,
    ValidatedImage,
    prepare_image,
    read_validated_image,
    read_validated_images,
)


def make_image(fmt="PNG", size=(16, 16)):
    buffer = io.BytesIO()
    Image.new("RGB", size, "red").save(buffer, format=fmt)
    return buffer.getvalue()


def upload(content, content_type="image/jpeg", filename="photo.jpg"):
    return UploadFile(
        filename=filename,
        file=io.BytesIO(content),
        headers={"content-type": content_type},
    )


class UploadValidationTest(unittest.IsolatedAsyncioTestCase):
    def test_uses_fixed_image_limits_and_pillow_concurrency(self):
        self.assertEqual(MAX_UPLOAD_BYTES, 20 * 1024 * 1024)
        self.assertEqual(MAX_IMAGE_PIXELS, 40_000_000)
        self.assertEqual(MAX_BATCH_BYTES, 100 * 1024 * 1024)
        self.assertEqual(MAX_IMAGE_SIDE, 2048)
        self.assertEqual(PILLOW_SEMAPHORE._value, 2)

    async def test_detects_real_format_instead_of_trusting_declared_mime(self):
        result = await read_validated_image(upload(make_image("PNG")))
        self.assertEqual(result.mime, "image/png")
        self.assertEqual(result.format, "PNG")

    async def test_rejects_non_image_with_allowed_declared_mime(self):
        with self.assertRaises(HTTPException) as raised:
            await read_validated_image(upload(b"not an image"))
        self.assertEqual(raised.exception.status_code, 415)

    async def test_rejects_image_over_forty_million_pixels(self):
        content = make_image("PNG", (8000, 5001))
        with self.assertRaises(HTTPException) as raised:
            await read_validated_image(upload(content, "image/png", "large.png"))
        self.assertEqual(raised.exception.status_code, 413)

    async def test_rejects_batch_over_one_hundred_megabytes(self):
        files = [upload(make_image())]
        files[0].size = MAX_BATCH_BYTES + 1
        with self.assertRaises(HTTPException) as raised:
            await read_validated_images(files)
        self.assertEqual(raised.exception.status_code, 413)

    async def test_returns_validated_images_for_batch(self):
        results = await read_validated_images([
            upload(make_image("PNG"), "image/jpeg"),
            upload(make_image("WEBP"), "image/png", "photo.png"),
        ])
        self.assertEqual([item.mime for item in results], ["image/png", "image/webp"])
        self.assertTrue(all(isinstance(item, ValidatedImage) for item in results))


    async def test_prepare_image_resizes_to_2048_and_reports_output_mime(self):
        result = await prepare_image(upload(make_image('PNG', (3000, 1500))))
        self.assertEqual(result.mime, 'image/jpeg')
        self.assertEqual((result.width, result.height), (2048, 1024))
        with Image.open(io.BytesIO(result.content)) as image:
            self.assertEqual(image.size, (2048, 1024))

    async def test_prepare_image_preserves_small_image_real_mime(self):
        result = await prepare_image(upload(make_image('WEBP'), 'image/png'))
        self.assertEqual(result.mime, 'image/webp')
        self.assertEqual((result.width, result.height), (16, 16))


if __name__ == "__main__":
    unittest.main()
