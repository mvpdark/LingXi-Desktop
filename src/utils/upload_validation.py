from __future__ import annotations

import asyncio
import io
from dataclasses import dataclass
from typing import Iterable

from fastapi import HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError

from .image_utils import compress_image

MAX_IMAGE_PIXELS = 40_000_000
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MAX_BATCH_BYTES = 100 * 1024 * 1024
MAX_IMAGE_SIDE = 2048

FORMAT_MIME = {
    "JPEG": "image/jpeg",
    "PNG": "image/png",
    "WEBP": "image/webp",
}

PILLOW_SEMAPHORE = asyncio.Semaphore(2)


@dataclass(frozen=True)
class ValidatedImage:
    content: bytes
    format: str
    mime: str
    width: int
    height: int


def _inspect(content: bytes) -> tuple[str, int, int]:
    try:
        with Image.open(io.BytesIO(content)) as image:
            fmt = (image.format or "").upper()
            width, height = image.size
            if fmt not in FORMAT_MIME:
                raise HTTPException(415, "仅支持 JPG/PNG/WebP 图片")
            if width <= 0 or height <= 0:
                raise HTTPException(400, "图片尺寸无效")
            if width * height > MAX_IMAGE_PIXELS:
                raise HTTPException(413, "图片超过 4000 万像素限制")
            image.verify()
            return fmt, width, height
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError):
        raise HTTPException(415, "文件不是有效的 JPG/PNG/WebP 图片")


async def read_validated_image(file: UploadFile) -> ValidatedImage:
    if file.size is not None and file.size > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "图片超过 20MB 限制")
    content = await file.read(MAX_UPLOAD_BYTES + 1)
    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "图片超过 20MB 限制")
    if not content:
        raise HTTPException(400, "文件内容为空")
    async with PILLOW_SEMAPHORE:
        fmt, width, height = await asyncio.to_thread(_inspect, content)
    return ValidatedImage(content, fmt, FORMAT_MIME[fmt], width, height)


async def read_validated_images(files: Iterable[UploadFile]) -> list[ValidatedImage]:
    uploads = list(files)
    declared_total = sum(file.size or 0 for file in uploads)
    if declared_total > MAX_BATCH_BYTES:
        raise HTTPException(413, "批量图片总量超过 100MB 限制")

    results = []
    actual_total = 0
    for file in uploads:
        image = await read_validated_image(file)
        actual_total += len(image.content)
        if actual_total > MAX_BATCH_BYTES:
            raise HTTPException(413, "批量图片总量超过 100MB 限制")
        results.append(image)
    return results


async def prepare_image(file: UploadFile) -> ValidatedImage:
    image = await read_validated_image(file)
    async with PILLOW_SEMAPHORE:
        content = await asyncio.to_thread(compress_image, image.content, MAX_IMAGE_SIDE)
    if content is image.content:
        return image
    with Image.open(io.BytesIO(content)) as processed:
        width, height = processed.size
        fmt = (processed.format or "JPEG").upper()
    return ValidatedImage(content, fmt, FORMAT_MIME.get(fmt, "image/jpeg"), width, height)
