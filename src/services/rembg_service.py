"""rembg 背景去除服务。封装 rembg 库的 remove() 函数。

与 ImageService 一致，返回 {"success": True, "images": [{"bytes": ..., "format": ...}]}，
base64 编码由 API 层处理。

支持从项目本地目录加载模型（打包集成），通过 U2NET_HOME 环境变量指定模型路径。
"""
from __future__ import annotations
import asyncio
import logging
import os
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

class RembgService:
    def __init__(self, model: str = "birefnet-portrait", cache_dir: str = "cache", models_dir: str = ""):
        self.model = model
        self.cache_dir = cache_dir
        self.models_dir = models_dir
        self._session = None
        self._semaphore = asyncio.Semaphore(2)

        # 设置本地模型目录：优先使用项目内置的 models/rembg
        if models_dir:
            models_path = Path(models_dir)
            if models_path.exists():
                os.environ["U2NET_HOME"] = str(models_path.resolve())
                logger.info("rembg 模型目录: %s", models_path.resolve())

    def _ensure_session(self):
        if self._session is None:
            from rembg import new_session
            self._session = new_session(self.model)
            logger.info("rembg 模型 %s 加载完成", self.model)
        return self._session

    async def remove_background(self, image_bytes: bytes, alpha_matting: bool = True) -> dict:
        def _remove():
            from rembg import remove
            session = self._ensure_session()
            return remove(
                image_bytes,
                session=session,
                alpha_matting=alpha_matting,
                alpha_matting_foreground_threshold=240,
                alpha_matting_background_threshold=10,
                alpha_matting_erode_size=10,
            )
        try:
            async with self._semaphore:
                result_bytes = await asyncio.to_thread(_remove)
            return {"success": True, "images": [{"bytes": result_bytes, "format": "png"}]}
        except Exception as e:
            logger.error("rembg 失败: %s", e)
            return {"success": False, "error": "背景去除失败，请稍后重试"}
