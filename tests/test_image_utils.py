import base64
import io
import unittest

from PIL import Image

from src.utils.image_utils import (
    MAX_IMAGE_PIXELS,
    SUPPORTED_FORMATS,
    compress_image,
    parse_data_url,
    prepare_image_bytes,
    validate_image,
)


def make_png(size):
    buffer = io.BytesIO()
    Image.new("RGB", size, "red").save(buffer, format="PNG")
    return buffer.getvalue()


def make_image(fmt="PNG", size=(16, 16)):
    buffer = io.BytesIO()
    Image.new("RGB", size, "red").save(buffer, format=fmt)
    return buffer.getvalue()


class CompressImageTest(unittest.TestCase):
    def test_returns_original_bytes_when_longest_side_is_at_most_2048(self):
        content = make_png((2048, 1024))
        self.assertIs(compress_image(content), content)

    def test_scales_down_proportionally_to_2048_by_default(self):
        content = make_png((3000, 1500))
        result = compress_image(content)
        with Image.open(io.BytesIO(result)) as image:
            self.assertEqual(image.size, (2048, 1024))


class CompressImagePixelLimitTest(unittest.TestCase):
    """compress_image 应在打开图片后立即检查像素限制。"""

    def test_rejects_image_over_forty_million_pixels(self):
        # 7000 * 6000 = 42,000,000 > 40,000,000
        content = make_png((7000, 6000))
        with self.assertRaises(ValueError):
            compress_image(content)

    def test_accepts_image_just_under_forty_million_pixels(self):
        # 6324 * 6324 = 39,992,976 < 40,000,000
        content = make_png((6324, 6324))
        result = compress_image(content)
        self.assertIsInstance(result, bytes)


class CompressImageFormatValidationTest(unittest.TestCase):
    """compress_image 应校验真实格式，拒绝非图片和不支持的格式。"""

    def test_rejects_non_image_data(self):
        with self.assertRaises(ValueError):
            compress_image(b"not an image at all")

    def test_rejects_gif_format(self):
        content = make_image("GIF", (100, 100))
        with self.assertRaises(ValueError):
            compress_image(content)

    def test_rejects_bmp_format(self):
        content = make_image("BMP", (100, 100))
        with self.assertRaises(ValueError):
            compress_image(content)

    def test_accepts_jpeg(self):
        content = make_image("JPEG", (100, 100))
        result = compress_image(content)
        self.assertIsInstance(result, bytes)

    def test_accepts_webp(self):
        content = make_image("WEBP", (100, 100))
        result = compress_image(content)
        self.assertIsInstance(result, bytes)


class ValidateImageTest(unittest.TestCase):
    """validate_image 返回 (format, width, height) 或抛出 ValueError。"""

    def test_returns_format_and_dimensions_for_png(self):
        fmt, w, h = validate_image(make_png((100, 200)))
        self.assertEqual(fmt, "PNG")
        self.assertEqual((w, h), (100, 200))

    def test_returns_format_and_dimensions_for_jpeg(self):
        content = make_image("JPEG", (300, 400))
        fmt, w, h = validate_image(content)
        self.assertEqual(fmt, "JPEG")
        self.assertEqual((w, h), (300, 400))

    def test_returns_format_and_dimensions_for_webp(self):
        content = make_image("WEBP", (50, 60))
        fmt, w, h = validate_image(content)
        self.assertEqual(fmt, "WEBP")
        self.assertEqual((w, h), (50, 60))

    def test_rejects_non_image(self):
        with self.assertRaises(ValueError):
            validate_image(b"not an image")

    def test_rejects_unsupported_format(self):
        content = make_image("GIF", (100, 100))
        with self.assertRaises(ValueError):
            validate_image(content)

    def test_rejects_over_forty_million_pixels(self):
        content = make_png((7000, 6000))
        with self.assertRaises(ValueError):
            validate_image(content)

    def test_max_image_pixels_constant(self):
        self.assertEqual(MAX_IMAGE_PIXELS, 40_000_000)

    def test_supported_formats_constant(self):
        self.assertEqual(SUPPORTED_FORMATS, {"JPEG", "PNG", "WEBP"})


class PrepareImageBytesTest(unittest.TestCase):
    """prepare_image_bytes 校验 + 压缩，返回 (bytes, mime)。"""

    def test_compresses_large_png_and_returns_jpeg_mime(self):
        content = make_png((3000, 1500))
        result_bytes, mime = prepare_image_bytes(content)
        self.assertEqual(mime, "image/jpeg")
        with Image.open(io.BytesIO(result_bytes)) as img:
            self.assertEqual(img.size, (2048, 1024))

    def test_preserves_small_png_original_mime(self):
        content = make_png((100, 100))
        result_bytes, mime = prepare_image_bytes(content)
        self.assertEqual(mime, "image/png")
        self.assertIs(result_bytes, content)

    def test_preserves_small_webp_mime(self):
        content = make_image("WEBP", (100, 100))
        result_bytes, mime = prepare_image_bytes(content)
        self.assertEqual(mime, "image/webp")
        self.assertIs(result_bytes, content)

    def test_preserves_small_jpeg_mime(self):
        content = make_image("JPEG", (100, 100))
        result_bytes, mime = prepare_image_bytes(content)
        self.assertEqual(mime, "image/jpeg")
        self.assertIs(result_bytes, content)

    def test_rejects_non_image(self):
        with self.assertRaises(ValueError):
            prepare_image_bytes(b"not an image")

    def test_rejects_over_forty_million_pixels(self):
        content = make_png((7000, 6000))
        with self.assertRaises(ValueError):
            prepare_image_bytes(content)


class ParseDataUrlTest(unittest.TestCase):
    """parse_data_url 从 data URL 中提取 (bytes, mime)。"""

    def test_extracts_jpeg_mime(self):
        b64 = base64.b64encode(b"fake-jpeg").decode()
        url = "data:image/jpeg;base64," + b64
        image_bytes, mime = parse_data_url(url)
        self.assertEqual(mime, "image/jpeg")
        self.assertEqual(image_bytes, b"fake-jpeg")

    def test_extracts_png_mime(self):
        b64 = base64.b64encode(b"fake-png").decode()
        url = "data:image/png;base64," + b64
        image_bytes, mime = parse_data_url(url)
        self.assertEqual(mime, "image/png")
        self.assertEqual(image_bytes, b"fake-png")

    def test_extracts_webp_mime(self):
        b64 = base64.b64encode(b"fake-webp").decode()
        url = "data:image/webp;base64," + b64
        image_bytes, mime = parse_data_url(url)
        self.assertEqual(mime, "image/webp")
        self.assertEqual(image_bytes, b"fake-webp")

    def test_defaults_to_jpeg_when_mime_missing(self):
        b64 = base64.b64encode(b"data").decode()
        url = "data:base64," + b64
        image_bytes, mime = parse_data_url(url)
        self.assertEqual(mime, "image/jpeg")


if __name__ == "__main__":
    unittest.main()
