"""
图片处理工具 — 压缩/缩放到指定尺寸，含像素限制与真实格式校验。

阶段一后端优化：
- 单图最大像素 40,000,000（宽 x 高）
- 真实格式校验（仅接受 JPEG / PNG / WebP）
- 最长边超过 2048px 时等比缩放到 2048px；否则保持原始字节
- VLM 内容统一编码为 JPEG 时，MIME 也统一传 image/jpeg
"""
from __future__ import annotations

import base64
import io

from PIL import Image, ImageOps

MAX_IMAGE_SIZE = 2048  # 默认最大边长
MAX_IMAGE_PIXELS = 40_000_000  # 单图最大像素数（宽 x 高）
SUPPORTED_FORMATS = {"JPEG", "PNG", "WEBP"}
FORMAT_MIME = {
    "JPEG": "image/jpeg",
    "PNG": "image/png",
    "WEBP": "image/webp",
}


def validate_image(content: bytes) -> tuple[str, int, int]:
    """校验图片真实格式与像素限制。

    返回 ``(format, width, height)``。
    无效图片、不支持的格式或超过 4000 万像素时抛出 ``ValueError``。
    """
    try:
        with Image.open(io.BytesIO(content)) as img:
            fmt = (img.format or "").upper()
            if fmt not in SUPPORTED_FORMATS:
                raise ValueError("不支持的图片格式: {}".format(fmt))
            w, h = img.size
            if w <= 0 or h <= 0:
                raise ValueError("图片尺寸无效")
            if w * h > MAX_IMAGE_PIXELS:
                raise ValueError("图片超过 4000 万像素限制")
            return fmt, w, h
    except ValueError:
        raise
    except Exception:
        raise ValueError("无效图片数据")


def compress_image(content: bytes, max_size: int = MAX_IMAGE_SIZE) -> bytes:
    """
    将图片压缩到 max_size 像素以内（最长边）。
    超过 max_size 时返回 JPEG 格式的 bytes（质量85%）。
    如果原图最长边不超过 max_size，原字节返回，不重新编码。
    无效图片数据抛出 ValueError，由调用方转换为 400 响应。

    阶段一新增：
    - 打开图片后立即校验真实格式（JPEG/PNG/WebP）
    - 打开图片后立即检查像素限制（4000 万像素）
    """
    try:
        img = Image.open(io.BytesIO(content))
        fmt = (img.format or "").upper()
        if fmt not in SUPPORTED_FORMATS:
            raise ValueError("不支持的图片格式: {}".format(fmt))
        w, h = img.size
        if w * h > MAX_IMAGE_PIXELS:
            raise ValueError("图片超过 4000 万像素限制")
        # 修正手机照片 EXIF 方向
        img = ImageOps.exif_transpose(img)
    except ValueError:
        raise
    except Exception:
        raise ValueError("无效图片数据")
    # 转为 RGB（去掉 alpha 通道）
    if img.mode in ('RGBA', 'P'):
        img = img.convert('RGB')

    w, h = img.size
    if max(w, h) <= max_size:
        return content
    if max(w, h) > max_size:
        if w >= h:
            new_w = max_size
            new_h = int(h * max_size / w)
        else:
            new_h = max_size
            new_w = int(w * max_size / h)
        img = img.resize((new_w, new_h), Image.LANCZOS)

    buf = io.BytesIO()
    img.save(buf, format='JPEG', quality=85)
    return buf.getvalue()


def prepare_image_bytes(content: bytes, max_size: int = MAX_IMAGE_SIZE) -> tuple[bytes, str]:
    """校验 + 压缩图片，返回 ``(bytes, mime)``。

    最长边超过 ``max_size`` 时等比缩放并重新编码为 JPEG，
    ``mime`` 为 ``image/jpeg``。
    否则保持原始字节，``mime`` 为原始格式对应的 MIME。
    """
    fmt, _w, _h = validate_image(content)
    compressed = compress_image(content, max_size)
    if compressed is content:
        return content, FORMAT_MIME.get(fmt, "image/jpeg")
    return compressed, "image/jpeg"


def parse_data_url(data_url: str) -> tuple[bytes, str]:
    """解析 data URL，返回 ``(image_bytes, mime)``。

    支持标准格式 ``data:image/png;base64,...``。
    无法解析 MIME 时默认返回 ``image/jpeg``。
    """
    header, b64part = data_url.split(",", 1)
    mime = "image/jpeg"
    if ":" in header and ";" in header:
        # header 形如 "data:image/png;base64"
        mime = header.split(":")[1].split(";")[0]
    image_bytes = base64.b64decode(b64part)
    return image_bytes, mime


def compress_image_png(content: bytes, max_size: int = MAX_IMAGE_SIZE) -> bytes:
    """同上但保持 PNG 格式（用于需要透明通道的场景）"""
    img = Image.open(io.BytesIO(content))
    w, h = img.size
    if max(w, h) > max_size:
        if w >= h:
            new_w = max_size
            new_h = int(h * max_size / w)
        else:
            new_h = max_size
            new_w = int(w * max_size / h)
        img = img.resize((new_w, new_h), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format='PNG')
    return buf.getvalue()
