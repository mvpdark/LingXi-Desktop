"""WebDAV 用户文件存储服务（阶段4：多租户图片迁移）。

设计说明
========

目录约定（WebDAV 远端）：
    {base_url}/{root}/                            用户根目录（root 默认 "users"）
    {base_url}/{root}/{username}/                 单个用户目录
    {base_url}/{root}/{username}/uploads/         用户上传（upload_、mask_ 前缀文件）
    {base_url}/{root}/{username}/generated/       AI 生成（generated_、、
                                                  rembg_ 前缀文件）

URL 映射（对外 Web 访问路径 ↔ 远端路径）：
    /uploads/{username}/{filename}
        ↔ {base_url}/{root}/{username}/{uploads|generated}/{filename}
    子目录由文件名前缀决定（见 WebDAVService.subdir_for）。

本地缓存策略：
    - 写路径：put_file 上传远端成功后同步写 {cache_dir}/{username}/{filename}
    - 读路径：get_file 先查本地缓存，命中直接返回；未命中走 WebDAV GET，
      成功后回填缓存；远端 404 返回 None
    - 删除路径：delete_file 先删远端，再清本地缓存（缓存清除失败不影响结果）
    - cache_dir 由 server.py 传入（_ASSETS_DIR/webdav_cache）；为 None 时
      不启用本地缓存（每次读都回源）

认证：
    默认使用 HTTP Basic Auth（SabreDAV / Nextcloud / AList 等主流
    WebDAV 服务均支持）。test_connection 检测到 401 时自动切换
    Digest Auth 重试一次，成功则在实例上记住选择（self._auth_type），
    后续请求沿用。也可通过 __init__ 参数 auth_type='digest' 强制指定。

错误处理约定：
    - put_file / get_file / delete_file / ensure_user_dirs：
      网络异常、超时、5xx 等记 logger.error 后抛 WebDAVError
    - exists / test_connection：记日志后返回安全值（False / {'ok': False}）
    - 未启用（base_url 为空）：enabled() 返回 False，各操作记日志并
      返回安全值或抛 WebDAVError，调用方可据此区分"未启用"与"故障"
    - filename 含 '..' 或 '/' / '\\' 时抛 ValueError（防目录穿越）

连接管理：
    复用惰性创建的共享 httpx.AsyncClient（_get_client），
    避免每次操作都新建 TCP 连接。auth 类型变化时自动重建 client。
    FastAPI lifespan shutdown 时调用 close() 释放连接池。
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import re
import time
from pathlib import Path
from typing import Optional

import httpx

logger = logging.getLogger(__name__)

# AI 生成文件前缀 → generated/ 子目录
_GENERATED_PREFIXES = ("generated_", "rembg_")

# 用户名合法字符
_USERNAME_SAFE_RE = re.compile(r"[^a-zA-Z0-9_-]")


class WebDAVError(Exception):
    """WebDAV 操作失败（网络/超时/认证/服务端错误）。

    调用方可据此区分"未启用"（enabled() 为 False，不会抛此异常）
    与"故障"（抛 WebDAVError）。
    """


class WebDAVService:
    """WebDAV 用户文件存储服务。

    参数:
        base_url:  WebDAV 服务地址，如 http://192.168.10.8:19798/dav
                   （尾部 /dav 保留，自动去除末尾斜杠；为空表示未启用）
        username:  WebDAV 账号
        password:  WebDAV 密码
        root:      远端用户根目录名，默认 "users"
        cache_dir: 本地缓存目录（None 表示不缓存）
        timeout:   请求超时秒数，默认 60
        auth_type: 'basic'（默认）或 'digest'；test_connection 检测
                   到 401 时自动切 digest 重试并记住选择
        url_secret: HMAC 签名密钥（server 传 jwt_secret）；
                    为空时签名功能关闭（开发兼容）
    """

    def __init__(
        self,
        base_url: str,
        username: str,
        password: str,
        root: str = "users",
        cache_dir: "str | Path | None" = None,
        timeout: int = 60,
        auth_type: str = "basic",
        url_secret: str = "",
    ):
        self.base_url = (base_url or "").rstrip("/")
        self.username = username or ""
        self.password = password or ""
        self.root = (root or "users").strip("/")
        self.cache_dir = Path(cache_dir) if cache_dir else None
        self.timeout = timeout
        self._auth_type = auth_type if auth_type in ("basic", "digest") else "basic"
        self.url_secret = url_secret or ""
        # 惰性创建的共享 AsyncClient（首次调用 _get_client 时初始化）
        self._client: httpx.AsyncClient | None = None
        self._client_auth_type: str | None = None
        # 并发锁：防止多个协程同时首次创建/重建 client
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------------
    # 静态工具
    # ------------------------------------------------------------------

    @staticmethod
    def sanitize_username(username: str) -> str:
        """用户名净化：只保留 [a-zA-Z0-9_-]，其他字符替换为 '_'。

        空字符串或全为非法字符（净化后不含任何合法字符）时返回 'anonymous'。
        合法字符（含首尾下划线/连字符）原样保留。
        """
        text = str(username or "")
        if not text or not re.search(r"[a-zA-Z0-9_-]", text):
            return "anonymous"
        return _USERNAME_SAFE_RE.sub("_", text)

    @staticmethod
    def subdir_for(filename: str) -> str:
        """按文件名前缀返回 'uploads' 或 'generated'。"""
        name = (filename or "").lower()
        return "generated" if name.startswith(_GENERATED_PREFIXES) else "uploads"

    # ------------------------------------------------------------------
    # 状态
    # ------------------------------------------------------------------

    @property
    def enabled(self) -> bool:
        """base_url 非空即启用。"""
        return bool(self.base_url)

    # ------------------------------------------------------------------
    # URL 签名（HMAC-SHA256，server 按同一契约校验）
    # ------------------------------------------------------------------

    def sign_url(self, username: str, filename: str) -> str:
        """计算 /uploads/{username}/{filename} 的访问签名。

        算法：HMAC-SHA256(url_secret, "{username}/{filename}") 取 hex 前 32 位。
        url_secret 为空时返回 ""（签名功能关闭，开发兼容）。
        """
        if not self.url_secret:
            return ""
        return hmac.new(
            self.url_secret.encode(),
            f"{username}/{filename}".encode(),
            hashlib.sha256,
        ).hexdigest()[:32]

    def verify_signature(self, username: str, filename: str, sig: str) -> bool:
        """校验签名（hmac.compare_digest 恒定时间比较，防时序侧信道）。

        url_secret 为空时恒 True（开发兼容）。
        """
        if not self.url_secret:
            return True
        expected = self.sign_url(username, filename)
        return hmac.compare_digest(expected, sig or "")

    # ------------------------------------------------------------------
    # 公开接口
    # ------------------------------------------------------------------

    async def ensure_user_dirs(self, username: str) -> None:
        """MKCOL 创建 users/、users/{username}/ 及其 uploads/、generated/ 子目录。

        MKCOL 对已存在目录返回 405，忽略之；其他错误抛 WebDAVError。
        """
        self._require_enabled("ensure_user_dirs")
        user = self.sanitize_username(username)
        paths = [
            self.root,
            f"{self.root}/{user}",
            f"{self.root}/{user}/uploads",
            f"{self.root}/{user}/generated",
        ]
        client = await self._get_client()
        for path in paths:
            resp = await client.request("MKCOL", self._url(path))
            if resp.status_code in (201, 405):
                # 201 Created / 405 Method Not Allowed（已存在）
                continue
            self._raise_for_status("MKCOL", path, resp)

    async def put_file(
        self, username: str, filename: str, content: bytes, sign: bool = True
    ) -> str:
        """PUT 上传文件（先确保目录存在），同时写本地缓存。

        返回 Web 访问相对 URL：/uploads/{username}/{filename}；
        sign=True 且 url_secret 非空时追加 "?sig=..."（HMAC 签名，
        签名串使用净化后的用户名，与 URL 路径一致）。
        失败抛 WebDAVError。
        """
        self._require_enabled("put_file")
        user = self.sanitize_username(username)
        self._validate_filename(filename)
        await self.ensure_user_dirs(user)
        remote = self._remote_path(user, filename)
        try:
            client = await self._get_client()
            resp = await client.put(self._url(remote), content=content)
            if resp.status_code not in (200, 201, 204):
                self._raise_for_status("PUT", remote, resp)
        except WebDAVError:
            raise
        except Exception as exc:
            logger.error("WebDAV PUT 失败: %s err=%s", remote, exc)
            raise WebDAVError(f"PUT {remote} 失败: {exc}") from exc
        # 图片可达数 MB，同步 Path IO 放到 worker 线程，避免阻塞事件循环
        await asyncio.to_thread(self._write_cache, user, filename, content)
        url = f"/uploads/{user}/{filename}"
        if sign:
            sig = self.sign_url(user, filename)
            if sig:
                url = f"{url}?sig={sig}"
        return url

    async def get_file(self, username: str, filename: str) -> Optional[bytes]:
        """读取文件：先查本地缓存，未命中走 WebDAV GET 并回填缓存。

        远端 404 返回 None；其他故障抛 WebDAVError。
        """
        self._require_enabled("get_file")
        user = self.sanitize_username(username)
        self._validate_filename(filename)

        # 同步 Path IO 放到 worker 线程，避免阻塞事件循环
        cached = await asyncio.to_thread(self._read_cache, user, filename)
        if cached is not None:
            return cached

        remote = self._remote_path(user, filename)
        try:
            client = await self._get_client()
            resp = await client.get(self._url(remote))
            if resp.status_code == 404:
                return None
            if resp.status_code != 200:
                self._raise_for_status("GET", remote, resp)
            content = resp.content
        except WebDAVError:
            raise
        except Exception as exc:
            logger.error("WebDAV GET 失败: %s err=%s", remote, exc)
            raise WebDAVError(f"GET {remote} 失败: {exc}") from exc
        await asyncio.to_thread(self._write_cache, user, filename, content)
        return content

    async def exists(self, username: str, filename: str) -> bool:
        """PROPFIND (Depth: 0) 判断远端文件是否存在；异常时返回 False。"""
        if not self.enabled:
            return False
        user = self.sanitize_username(username)
        try:
            self._validate_filename(filename)
        except ValueError:
            return False
        remote = self._remote_path(user, filename)
        try:
            client = await self._get_client()
            resp = await client.request(
                "PROPFIND",
                self._url(remote),
                headers={"Depth": "0"},
            )
            if resp.status_code == 404:
                return False
            # 207 Multi-Status / 200 均表示存在
            return resp.status_code in (200, 207)
        except Exception as exc:
            logger.error("WebDAV PROPFIND 失败: %s err=%s", remote, exc)
            return False

    async def delete_file(self, username: str, filename: str) -> bool:
        """DELETE 远端文件并清本地缓存。

        远端删除成功（2xx）或不存在（404）均返回 True；其他故障抛 WebDAVError。
        """
        self._require_enabled("delete_file")
        user = self.sanitize_username(username)
        self._validate_filename(filename)
        remote = self._remote_path(user, filename)
        try:
            client = await self._get_client()
            resp = await client.delete(self._url(remote))
            if resp.status_code == 404:
                ok = True
            elif resp.status_code in (200, 202, 204):
                ok = True
            else:
                self._raise_for_status("DELETE", remote, resp)
        except WebDAVError:
            raise
        except Exception as exc:
            logger.error("WebDAV DELETE 失败: %s err=%s", remote, exc)
            raise WebDAVError(f"DELETE {remote} 失败: {exc}") from exc
        # 同步 Path IO 放到 worker 线程，避免阻塞事件循环
        await asyncio.to_thread(self._remove_cache, user, filename)
        return ok

    async def test_connection(self) -> dict:
        """PROPFIND base_url 根目录，检测连通性与认证。

        返回 {'ok': bool, 'status': int, 'error': str | None, 'auth': str}。
        Basic Auth 返回 401 时自动切换 Digest Auth 重试一次，成功则
        在实例上记住该认证方式。
        """
        if not self.enabled:
            return {"ok": False, "status": 0, "error": "webdav 未配置（base_url 为空）",
                    "auth": self._auth_type}
        try:
            result = await self._propfind_root()
            if result["status"] == 401 and self._auth_type == "basic":
                logger.info("WebDAV Basic Auth 返回 401，尝试切换 Digest Auth 重试")
                # benign race：并发调用 test_connection 可能同时读写 _auth_type，
                # 但竞争写入的值一致（均为 basic→digest，失败时均回滚 basic），
                # 且 _new_client 每次实时读取该属性，最坏情况仅多一次 401 重试，
                # 不影响正确性，故不加锁。
                self._auth_type = "digest"
                retry = await self._propfind_root()
                if not retry["ok"]:
                    # 回滚，保持调用方可重试 basic 语义
                    self._auth_type = "basic"
                retry["auth"] = self._auth_type
                return retry
            result["auth"] = self._auth_type
            return result
        except Exception as exc:
            logger.error("WebDAV 连通性测试失败: %s", exc)
            return {"ok": False, "status": 0, "error": str(exc), "auth": self._auth_type}

    # ------------------------------------------------------------------
    # 内部方法
    # ------------------------------------------------------------------

    async def _propfind_root(self) -> dict:
        """对 base_url 根目录发起 PROPFIND Depth:0，返回检测结果字典。"""
        try:
            client = await self._get_client()
            resp = await client.request(
                "PROPFIND", self.base_url + "/", headers={"Depth": "0"}
            )
            ok = resp.status_code in (200, 207)
            return {
                "ok": ok,
                "status": resp.status_code,
                "error": None if ok else f"HTTP {resp.status_code}",
            }
        except Exception as exc:
            return {"ok": False, "status": 0, "error": str(exc)}

    async def _get_client(self) -> httpx.AsyncClient:
        """返回共享的 httpx.AsyncClient（惰性创建，auth 类型变化时重建，并发安全）。

        首次调用时创建 client，后续复用同一实例。
        若 _auth_type 发生变化（如 test_connection 从 basic 切到 digest），
        则关闭旧 client 并创建新的，确保认证方式一致。
        使用 asyncio.Lock 防止高并发下多个协程同时创建/重建 client 实例。
        """
        if (
            self._client is not None
            and not self._client.is_closed
            and self._client_auth_type == self._auth_type
        ):
            return self._client
        async with self._lock:
            if (
                self._client is not None
                and not self._client.is_closed
                and self._client_auth_type == self._auth_type
            ):
                return self._client
            if self._client is not None and not self._client.is_closed:
                await self._client.aclose()
            self._client = self._new_client()
            self._client_auth_type = self._auth_type
        return self._client

    async def close(self) -> None:
        """关闭共享 AsyncClient，释放连接池资源。

        应在 FastAPI lifespan shutdown 阶段调用。
        """
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
        self._client = None
        self._client_auth_type = None

    def _new_client(self) -> httpx.AsyncClient:
        """构造短连接 AsyncClient（每次操作新建，用完即关）。"""
        if self._auth_type == "digest":
            auth: httpx.Auth = httpx.DigestAuth(self.username, self.password)
        else:
            auth = httpx.BasicAuth(self.username, self.password)
        return httpx.AsyncClient(auth=auth, timeout=self.timeout)

    def _url(self, path: str) -> str:
        """拼接远端 URL（path 不含前导斜杠）。"""
        return f"{self.base_url}/{path.lstrip('/')}"

    def _remote_path(self, user: str, filename: str) -> str:
        """远端相对路径：{root}/{user}/{uploads|generated}/{filename}。"""
        return f"{self.root}/{user}/{self.subdir_for(filename)}/{filename}"

    def _validate_filename(self, filename: str) -> None:
        """防目录穿越：filename 不得为空、不得含 '..' 或路径分隔符。"""
        if not filename or ".." in filename or "/" in filename or "\\" in filename:
            raise ValueError(f"非法文件名: {filename!r}")

    def _require_enabled(self, op: str) -> None:
        """未启用时抛 WebDAVError（调用方据此区分未启用与故障）。"""
        if not self.enabled:
            raise WebDAVError(f"WebDAV 未启用（base_url 为空），无法执行 {op}")

    @staticmethod
    def _raise_for_status(op: str, path: str, resp: httpx.Response) -> None:
        """非预期状态码统一转 WebDAVError 并记日志。"""
        logger.error("WebDAV %s %s 失败: HTTP %s", op, path, resp.status_code)
        raise WebDAVError(f"{op} {path} 失败: HTTP {resp.status_code}")

    # ------------------------------------------------------------------
    # 本地缓存
    # ------------------------------------------------------------------

    def _cache_path(self, user: str, filename: str) -> Optional[Path]:
        if self.cache_dir is None:
            return None
        return self.cache_dir / user / filename

    def _read_cache(self, user: str, filename: str) -> Optional[bytes]:
        path = self._cache_path(user, filename)
        if path is None or not path.is_file():
            return None
        try:
            return path.read_bytes()
        except OSError as exc:
            logger.warning("读取本地缓存失败: %s err=%s", path, exc)
            return None

    def _write_cache(self, user: str, filename: str, content: bytes) -> None:
        path = self._cache_path(user, filename)
        if path is None:
            return
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        except OSError as exc:
            # 缓存写入失败不阻断主流程
            logger.warning("写入本地缓存失败: %s err=%s", path, exc)

    def _remove_cache(self, user: str, filename: str) -> None:
        path = self._cache_path(user, filename)
        if path is None:
            return
        try:
            path.unlink(missing_ok=True)
        except OSError as exc:
            logger.warning("清除本地缓存失败: %s err=%s", path, exc)

    # ------------------------------------------------------------------
    # 缓存治理
    # ------------------------------------------------------------------

    async def clean_cache(self, max_age_days: int = 7, max_files: int = 1000) -> int:
        """清理本地缓存：先删超龄文件，仍超限量则按 mtime 最旧逐出。

        参数:
            max_age_days: mtime 超过该天数的缓存文件删除
            max_files:    缓存文件总数上限，超出部分按 mtime 最旧逐出

        返回:
            实际删除的文件数；未启用缓存（cache_dir 为 None）返回 0。
            单个文件删除失败仅记日志，不中断整体清理。
        """
        if self.cache_dir is None:
            return 0
        # 目录遍历与批量删除为同步 IO，放到 worker 线程执行
        return await asyncio.to_thread(self._clean_cache_sync, max_age_days, max_files)

    def _clean_cache_sync(self, max_age_days: int, max_files: int) -> int:
        """clean_cache 的同步实现（经 asyncio.to_thread 在 worker 线程运行）。"""
        try:
            files = [p for p in self.cache_dir.rglob("*") if p.is_file()]
        except OSError as exc:
            logger.warning("扫描缓存目录失败: %s err=%s", self.cache_dir, exc)
            return 0

        now = time.time()
        max_age_secs = max_age_days * 86400
        entries = []  # (mtime, path)
        for p in files:
            try:
                entries.append((p.stat().st_mtime, p))
            except OSError:
                continue

        removed = 0
        remaining = []
        for mtime, p in entries:
            if now - mtime > max_age_secs:
                try:
                    p.unlink()
                    removed += 1
                    continue
                except OSError as exc:
                    logger.warning("删除超龄缓存失败: %s err=%s", p, exc)
            remaining.append((mtime, p))

        if len(remaining) > max_files:
            remaining.sort(key=lambda e: e[0])  # mtime 升序，最旧在前
            for mtime, p in remaining[: len(remaining) - max_files]:
                try:
                    p.unlink()
                    removed += 1
                except OSError as exc:
                    logger.warning("逐出缓存失败: %s err=%s", p, exc)

        if removed:
            logger.info("WebDAV 本地缓存清理完成，删除 %d 个文件", removed)
        return removed
