from __future__ import annotations

import asyncio
import base64
import ipaddress
import logging
import os
import time
import uuid
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse

import httpx

from services.key_pool import KeyPool

logger = logging.getLogger(__name__)


class ImageService:
    """图片生成与编辑服务。

    封装 yunwu.ai Images API 的文生图和图生图功能。
    支持 b64_json 返回格式，直接返回 bytes（不再落地临时文件）。
    内部复用惰性创建的共享 httpx.AsyncClient，由 FastAPI lifespan 管理生命周期。
    """

    def __init__(self, config):
        """初始化图片服务。

        参数:
            config: 配置对象，需包含 image_api_base, llm_api_keys,
                    image_model, cache_dir 属性
        """
        self.api_base = config.image_api_base
        # yunwu 集成 key：image 与 LLM 共用同一组 key（llm_api_keys）
        keys = getattr(config, "llm_api_keys", []) or []
        self.key_pool = KeyPool(keys)
        self.model = config.image_model
        self.cache_dir = config.cache_dir
        # 惰性创建的共享 AsyncClient（首次调用 _get_client 时初始化）
        self._client: httpx.AsyncClient | None = None
        # 并发锁：防止多个协程同时首次创建 client
        self._lock: asyncio.Lock = asyncio.Lock()

    # ------------------------------------------------------------------
    # 共享 AsyncClient 生命周期管理
    # ------------------------------------------------------------------

    async def _get_client(self) -> httpx.AsyncClient:
        """返回共享的 httpx.AsyncClient（惰性创建，并发安全）。

        首次调用时创建 client，后续复用同一实例，
        避免 generate/edit 每次都新建 TCP 连接。
        使用 asyncio.Lock 防止高并发下多个协程同时创建 client 实例。
        """
        if self._client is not None and not self._client.is_closed:
            return self._client
        async with self._lock:
            # Double-check：获取锁后再次确认，避免排队期间已被其他协程创建
            if self._client is not None and not self._client.is_closed:
                return self._client
            self._client = httpx.AsyncClient(timeout=600)
        return self._client

    async def close(self) -> None:
        """关闭共享 AsyncClient，释放连接池资源。

        应在 FastAPI lifespan shutdown 阶段调用。
        """
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
        self._client = None

    # ------------------------------------------------------------------
    # 文生图
    # ------------------------------------------------------------------

    async def generate_image(
        self,
        prompt: str,
        size: str = "1024x1024",
        quality: str = "auto",
        n: int = 1,
        output_format: str = "png",
        timeout: int = 600,
        max_attempts: Optional[int] = None,
    ) -> dict:
        """文生图：根据文本提示生成图片。

        调用 POST {api_base}/v1/images/generations，
        返回 b64_json 格式的图片数据，直接返回 bytes。

        参数:
            prompt: 图片生成提示词
            size: 图片尺寸，默认 1024x1024
            quality: 图片质量，默认 auto
            n: 生成数量，默认1
            output_format: 输出图片格式，默认 png
            timeout: 请求超时时间（秒），默认600
            max_attempts: 最大尝试次数（默认 None 表示按 KeyPool 冷却策略，至多 3 次）

        返回:
            包含 success, images 字段的结果字典
            images 列表中每项包含 bytes 和 format 字段
        """
        url = f"{self.api_base}/v1/images/generations"

        payload = {
            "model": self.model,
            "prompt": prompt,
            "n": n,
            "size": size,
            "quality": quality,
            "response_format": "b64_json",
        }

        # KeyPool 冷却模式：循环内取 key，失败 mark_failed 触发冷却，
        # 下一轮自动切换到其他可用 key（与 llm_service 重试模式一致）
        last_error = None
        data = None
        # 调用方可收紧尝试次数以控制端到端最坏耗时（如全景链路须小于客户端 600s 超时）
        attempts = self._cooldown_attempts()
        if max_attempts is not None:
            attempts = max(1, min(max_attempts, attempts))
        client = await self._get_client()
        for _ in range(attempts):
            key = self.key_pool.get_next_key()
            headers = {
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json",
            }
            try:
                response = await client.post(url, json=payload, headers=headers, timeout=timeout)
            except httpx.HTTPError as exc:
                # 网络/超时等请求异常：标记 key 失败，换 key 重试
                logger.error("文生图请求异常: %s", exc)
                self.key_pool.mark_failed(key)
                last_error = "图像服务暂时不可用（请求异常）"
                continue
            if response.status_code != 200:
                # 上游错误体可能含内部细节，不原样回传调用方，仅记日志
                logger.error(
                    "文生图 API 错误: HTTP %s body=%s",
                    response.status_code, response.text[:500],
                )
                last_error = f"图像服务暂时不可用（{response.status_code}）"
                if response.status_code in (401, 403, 429) or response.status_code >= 500:
                    # 认证/限流/服务端错误视为 key 故障，冷却后换 key 重试
                    self.key_pool.mark_failed(key)
                    continue
                # 其他 4xx 为请求本身问题，换 key 无意义，直接失败
                return {"success": False, "error": last_error}
            self.key_pool.mark_success(key)
            data = response.json()
            break

        if data is None:
            return {"success": False, "error": last_error or "图像服务暂时不可用"}

        return await self._extract_images(data, output_format)

    def _cooldown_attempts(self) -> int:
        """KeyPool 冷却重试次数：最多 key 数，至少 1 次，封顶 3 次。"""
        return max(1, min(self.key_pool.size or 1, 3))

    @staticmethod
    def _image_mime(path: Path) -> str:
        return {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}.get(path.suffix.lower(), "application/octet-stream")

    # ------------------------------------------------------------------
    # 图生图
    # ------------------------------------------------------------------

    async def edit_image(
        self,
        image_bytes: bytes,
        prompt: str,
        size: Optional[str] = None,
        quality: str = "auto",
        n: int = 1,
        output_format: str = "png",
        mask_bytes: Optional[bytes] = None,
        timeout: int = 600,
        max_attempts: Optional[int] = None,
    ) -> dict:
        """图生图：基于原图 bytes 和提示词编辑图片。

        调用 POST {api_base}/v1/images/edits，
        使用 multipart/form-data 上传原图 bytes（及可选蒙版 bytes），
        返回 b64_json 格式的编辑结果，直接返回 bytes。

        参数:
            image_bytes: 源图片二进制数据
            prompt: 编辑提示词
            size: 输出图片尺寸，None 表示使用默认
            quality: 图片质量，默认 auto
            n: 生成数量，默认1
            output_format: 输出图片格式，默认 png
            mask_bytes: 蒙版图片二进制数据（可选）
            timeout: 请求超时时间（秒），默认600
            max_attempts: 最大尝试次数（默认 None 表示按 KeyPool 冷却策略，至多 3 次）

        返回:
            包含 success, images 字段的结果字典
            images 列表中每项包含 bytes 和 format 字段
        """
        url = f"{self.api_base}/v1/images/edits"

        files = [
            ("image", ("image.png", image_bytes, "image/png")),
        ]

        # 可选蒙版数据
        if mask_bytes:
            files.append(
                ("mask", ("mask.png", mask_bytes, "image/png"))
            )

        form_data = {
            "model": self.model,
            "prompt": prompt,
            "n": str(n),
            "response_format": "b64_json",
            "quality": quality,
        }

        if size:
            form_data["size"] = size

        # KeyPool 冷却模式：循环内取 key，失败 mark_failed 触发冷却，
        # 下一轮自动切换到其他可用 key（与 llm_service 重试模式一致）
        last_error = None
        data = None
        # 调用方可收紧尝试次数以控制端到端最坏耗时（如全景链路须小于客户端 600s 超时）
        attempts = self._cooldown_attempts()
        if max_attempts is not None:
            attempts = max(1, min(max_attempts, attempts))
        client = await self._get_client()
        for _ in range(attempts):
            key = self.key_pool.get_next_key()
            headers = {"Authorization": f"Bearer {key}"}
            try:
                response = await client.post(
                    url, data=form_data, files=files, headers=headers, timeout=timeout
                )
            except httpx.HTTPError as exc:
                # 网络/超时等请求异常：标记 key 失败，换 key 重试
                logger.error("图生图请求异常: %s", exc)
                self.key_pool.mark_failed(key)
                last_error = "图像服务暂时不可用（请求异常）"
                continue
            if response.status_code != 200:
                # 上游错误体可能含内部细节，不原样回传调用方，仅记日志
                logger.error(
                    "图生图 API 错误: HTTP %s body=%s",
                    response.status_code, response.text[:500],
                )
                last_error = f"图像服务暂时不可用（{response.status_code}）"
                if response.status_code in (401, 403, 429) or response.status_code >= 500:
                    # 认证/限流/服务端错误视为 key 故障，冷却后换 key 重试
                    self.key_pool.mark_failed(key)
                    continue
                # 其他 4xx 为请求本身问题，换 key 无意义，直接失败
                return {"success": False, "error": last_error}
            self.key_pool.mark_success(key)
            data = response.json()
            break

        if data is None:
            return {"success": False, "error": last_error or "图像服务暂时不可用"}

        return await self._extract_images(data, output_format)

    # ------------------------------------------------------------------
    # 结果提取（不再落地临时文件，直接返回 bytes）
    # ------------------------------------------------------------------

    @staticmethod
    def _is_safe_url(url: str) -> bool:
        """检查 URL 是否安全（SSRF 防护）。

        拒绝非 http/https 协议、以及指向私有/环回/链路本地地址的 URL。
        """
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https"):
            return False
        try:
            ip = ipaddress.ip_address(parsed.hostname)
            if ip.is_private or ip.is_loopback or ip.is_link_local:
                return False
        except ipaddress.AddressValueError:
            pass  # hostname 是域名，不是 IP，允许通过
        return True

    async def _extract_images(self, data: dict, fmt: str) -> dict:
        """从 API 返回数据中提取图片 bytes。

        支持 b64_json（base64 解码）和 url（下载）两种格式。
        不再写入磁盘，直接返回 bytes 供调用方使用。

        参数:
            data: API 返回的 JSON 数据
            fmt: 输出图片格式

        返回:
            成功: {"success": True, "images": [{"bytes": ..., "format": ...}]}
            失败: {"success": False, "error": "错误信息"}
        """
        try:
            images = []
            for item in data.get("data", []):
                if "b64_json" in item:
                    # base64 解码为 bytes
                    image_bytes = base64.b64decode(item["b64_json"])
                    images.append({"bytes": image_bytes, "format": fmt})

                elif "url" in item:
                    # SSRF 防护：拒绝指向内网/环回地址的 URL
                    if not self._is_safe_url(item["url"]):
                        raise ValueError(f"拒绝下载不安全的 URL（SSRF 防护）: {item['url'][:100]}")
                    # 下载远程图片（限制超时与大小）
                    max_bytes = 30 * 1024 * 1024  # 30MB
                    client = await self._get_client()
                    img_response = await client.get(item["url"], timeout=30)
                    img_response.raise_for_status()
                    content_length = img_response.headers.get("Content-Length")
                    if content_length and int(content_length) > max_bytes:
                        raise ValueError("远程图片超过 30MB 限制，已拒绝下载")
                    if len(img_response.content) > max_bytes:
                        raise ValueError("远程图片超过 30MB 限制，已拒绝保存")
                    images.append({"bytes": img_response.content, "format": fmt})

            return {"success": True, "images": images}

        except Exception as ex:
            logger.error("提取图片失败: %s", ex, exc_info=True)
            return {"success": False, "error": "图片提取失败"}
