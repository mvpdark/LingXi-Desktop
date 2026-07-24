"""画质增强服务，基于 yunwu.ai gpt-image-2 API。"""
from __future__ import annotations
import asyncio
import logging
from typing import Optional

logger = logging.getLogger(__name__)

# 增强模式对应的 prompt
ENHANCE_PROMPTS = {
    "super_resolution": "Enhance this image to higher resolution and sharpness. Preserve all original details, colors, and composition. Make the image clearer and more defined without changing any content.",
    "denoise": "Remove noise and grain from this image while preserving all details, textures, and colors. Produce a clean, smooth result that maintains the original sharpness.",
    "sharpen": "Sharpen this image by enhancing edges and fine details. Make it crisper and more defined while keeping the natural look and not introducing artifacts.",
    "face_restore": "Restore and enhance facial details in this image. Improve skin texture naturally, enhance facial features, and reduce blemishes while maintaining the person's identity and natural appearance.",
    "color_enhance": "Enhance the colors and contrast of this image. Make the colors more vibrant and balanced, improve the dynamic range, while keeping the natural look.",
}

class EnhanceService:
    """画质增强服务，基于 yunwu.ai gpt-image-2 API。

    与 ImageService 一致，返回 {"success": True, "images": [{"bytes": ..., "format": ...}]}，
    base64 编码由 API 层处理。
    """

    def __init__(self, image_service=None, enabled: bool = True):
        self.image_service = image_service
        self.enabled = enabled

    async def enhance(self, image_bytes: bytes, mode: str = "super_resolution", scale: int = 2) -> dict:
        if not self.enabled or self.image_service is None:
            return {"success": False, "error": "画质增强服务未启用"}

        prompt = ENHANCE_PROMPTS.get(mode, ENHANCE_PROMPTS["super_resolution"])

        try:
            result = await self.image_service.edit_image(
                image_bytes=image_bytes,
                prompt=prompt,
                quality="high",
                output_format="png",
                timeout=300,
                max_attempts=2,
            )

            if result.get("success") and result.get("images"):
                # 直接透传 ImageService 返回的 bytes 结果
                return {"success": True, "images": result["images"]}
            else:
                return {"success": False, "error": result.get("error", "画质增强失败")}
        except Exception as ex:
            logger.error("画质增强异常: %s", ex)
            return {"success": False, "error": f"画质增强异常: {ex}"}
