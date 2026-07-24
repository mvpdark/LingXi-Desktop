"""灵犀 LX — FastAPI 单端口服务器。

一个进程、一个端口，同时提供：
1. REST API（会话管理、图片上传、记忆清除）
2. WebSocket（聊天流式输出 + Agent 事件推送）
3. 静态文件托管（前端 HTML/CSS/JS + 用户上传图片 + AI生成图片 + 字体）
   —— serve_frontend=false 时切换为纯 API 模式（不托管前端静态文件，
      仅保留 REST/WebSocket 与 /uploads 图片代理）

启动：python server.py
访问：http://127.0.0.1:8765 或 http://<局域网IP>:8765
"""

from __future__ import annotations

import asyncio
import base64
import hmac
import io
import json
import logging
import os
import socket
import sys
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import List

import httpx
import uvicorn
from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    HTTPException,
    Request,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
)
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select, text

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 路径设置
# ---------------------------------------------------------------------------
_SRC_DIR = Path(__file__).resolve().parent            # src/
_BASE_DIR = _SRC_DIR.parent                            # 项目根目录
_STATIC_DIR = _SRC_DIR / "static"                     # src/static/
# 支持环境变量 ASSETS_DIR 覆盖默认 assets/ 路径
_ASSETS_DIR = Path(os.environ.get("ASSETS_DIR") or (_BASE_DIR / "assets"))

sys.path.insert(0, str(_SRC_DIR))
# 项目根目录加入 sys.path，支持 src.* 包形式导入（src.db / src.utils）
sys.path.insert(0, str(_BASE_DIR))

# 确保目录存在（_STATIC_DIR 仅在 serve_frontend=True 时才需要，
# 推迟到 config 加载后的静态文件挂载块中创建，避免纯 API 模式下重建空目录）
_ASSETS_DIR.mkdir(parents=True, exist_ok=True)

# 图片处理工具（压缩/缩放）
from utils.image_utils import parse_data_url, prepare_image_bytes
from utils.upload_validation import (
    MAX_UPLOAD_BYTES, PILLOW_SEMAPHORE, prepare_image, read_validated_images,
)
from utils.ws_task_manager import WebSocketTaskManager

# JWT 鉴权体系 ORM 模型与会话类型（阶段2）
# 注：src.db.database / src.utils.dependencies 在导入时依赖 DATABASE_URL
# 环境变量完成模块级初始化，故在下方 config 加载并注入环境变量后再导入
from src.db import models  # noqa: E402
from src.db.models import User  # noqa: E402

# ---------------------------------------------------------------------------
# 导入服务层（原有代码全部保留）
# ---------------------------------------------------------------------------
from config import Config
from services.agent_orchestrator import AgentOrchestrator
from services.auth_service import AuthService
from services.billing_service import BillingService
from services.image_service import ImageService
from services.rembg_service import RembgService
from services.enhance_service import EnhanceService
from services.ffmpeg_enhance_service import FFmpegEnhanceService
from services.llm_service import LLMService
from services.memory_manager import MemoryManager
from services.search_service import SearchService
from services.storage_service import StorageService
from services.webdav_service import WebDAVService

# ---------------------------------------------------------------------------
# 加载配置 & 初始化服务
# ---------------------------------------------------------------------------
config = Config.load(str(_BASE_DIR / "config.yaml"))

# ---------------------------------------------------------------------------
# 数据库引擎与会话工厂（阶段2：PostgreSQL + JWT）
# ---------------------------------------------------------------------------
if not config.database_url:
    raise RuntimeError("DATABASE_URL 未配置，请设置环境变量或 config.yaml")

# src.db.database 在导入时读取 DATABASE_URL 环境变量并完成模块级初始化，
# 提前注入以兼容仅在 config.yaml 中配置 database_url 的部署方式
os.environ.setdefault("DATABASE_URL", config.database_url)

# 直接复用 src.db.database 模块级引擎与会话工厂（pool_size=10/pre_ping/recycle
# 已在该模块配置），dependencies.get_db（require_admin 依赖链）与各服务共享同一连接池
from src.db.database import (  # noqa: E402
    engine as db_engine,
    async_session_factory,
)
from src.utils.dependencies import get_current_user, require_admin  # noqa: E402,F401


def _require_user(request) -> "tuple[str | None, str | None]":
    """返回 (user_id, username)；JWT 认证时均有值，旧 API_TOKEN 服务调用时为 (None, None)。"""
    user_id = getattr(request.state, "user_id", None)
    username = getattr(request.state, "username", None)
    if not user_id:
        return None, None
    return user_id, username


def _unauthorized() -> JSONResponse:
    """用户资源路由对旧 API_TOKEN / 未登录调用的统一 401 响应。"""
    return JSONResponse({"error": "需要用户登录"}, status_code=401)


# ---------------------------------------------------------------------------
# 余额熔断（预扣费模式）
# 防止余额为正但极小时用户无限调用消耗型 API。
# 流程：检查余额 >= 门槛 → 预扣预估费用 → 执行 → 成功后实际扣费/失败后退回
# ---------------------------------------------------------------------------

async def _check_balance_and_precharge(
    user_id: str | None, precharge_amount: float
) -> tuple[bool, JSONResponse | None, float]:
    """检查余额门槛并执行预扣费（REST API 用）。

    余额低于 min_balance 时返回 402，防止余额为正但极小时无限调用。
    预扣 precharge_amount 元（调用 auth.precharge 原子扣款）。
    调用方应在 API 失败时调用 auth.refund 退还预扣金额。

    返回 (ok, error_response, charged_amount)：
    - ok=True, None, charged：余额足够，已预扣 charged 元
    - ok=False, resp, 0：余额不足，resp 为 402 响应
    """
    if not user_id:
        # 旧 API_TOKEN 服务间调用，不检查余额
        return True, None, 0.0

    balance = await auth.get_balance(user_id)
    if balance is None:
        return False, JSONResponse({"error": "用户不存在"}, status_code=404), 0.0

    # 余额必须 >= max(min_balance, precharge_amount) 才放行
    required = max(config.min_balance, precharge_amount)
    if balance < required:
        logger.info(
            "余额熔断: user_id=%s balance=%.2f < 需要 %.2f (门槛=%.2f 预扣=%.2f)",
            user_id, balance, required, config.min_balance, precharge_amount,
        )
        return False, JSONResponse(
            {"error": "余额不足，请充值"},
            status_code=402,
        ), 0.0

    # 执行预扣费
    charged = 0.0
    if precharge_amount > 0:
        ok = await auth.precharge(user_id, precharge_amount)
        if not ok:
            # 并发导致余额变化，扣款失败
            return False, JSONResponse(
                {"error": "余额不足，请充值"},
                status_code=402,
            ), 0.0
        charged = precharge_amount

    return True, None, charged


async def _refund_on_failure(user_id: str | None, amount: float):
    """API 调用失败时退还预扣费。"""
    if not user_id or amount <= 0:
        return
    try:
        await auth.refund(user_id, amount)
    except Exception as e:
        logger.error("退款失败: user_id=%s amount=%g err=%s", user_id, amount, e)


async def _ws_check_balance(ws: WebSocket, user_id: str, precharge_amount: float) -> bool:
    """WebSocket 余额检查（聊天循环内，每次发消息前调用）。

    余额低于门槛时发送错误消息，返回 False。
    """
    balance = await auth.get_balance(user_id)
    if balance is None:
        await ws.send_json({"type": "error", "content": "用户不存在"})
        return False

    required = max(config.min_balance, precharge_amount)
    if balance < required:
        logger.info("WS 余额熔断: user_id=%s balance=%.2f < 需要 %.2f", user_id, balance, required)
        await ws.send_json({
            "type": "error",
            "content": "余额不足，请充值",
            "code": "INSUFFICIENT_BALANCE",
        })
        return False

    return True


storage = StorageService(db_session_factory=async_session_factory)

# 用户认证与计费服务（PostgreSQL + JWT）
if not config.jwt_secret:
    if config.serve_frontend:
        logger.warning(
            "JWT_SECRET 未配置，正在使用开发默认密钥；生产环境必须配置 jwt_secret / JWT_SECRET"
        )
    else:
        raise RuntimeError("生产环境必须配置 JWT_SECRET 环境变量")
auth = AuthService(
    db_session_factory=async_session_factory,
    jwt_secret=config.jwt_secret or ("dev-secret-change-me" if config.serve_frontend else None),
    jwt_algorithm=config.jwt_algorithm,
    access_ttl=config.jwt_access_ttl,
    refresh_ttl=config.jwt_refresh_ttl,
)

# 计费服务：yunwu 集成 key（LLM + Image 共用）
_all_yunwu_keys = list(dict.fromkeys(  # 去重保序
    (config.llm_api_keys or [])
))
billing = BillingService(
    db_session_factory=async_session_factory,
    auth_service=auth,
    api_keys=_all_yunwu_keys,
    api_base=config.llm_api_base,
    billing_rate=config.billing_rate,
)
logger.info(
    "计费服务初始化：billing_rate=%s, yunwu keys=%d",
    config.billing_rate, len(_all_yunwu_keys),
)

memory = MemoryManager(
    db_session_factory=async_session_factory,
    api_base=config.llm_api_base,
    api_keys=config.llm_api_keys,
    model=config.llm_model,
)

llm = LLMService(config)
llm.set_memory_manager(memory)

# Tavily 搜索服务
search_service = SearchService(
    db_session_factory=async_session_factory,
    api_keys=getattr(config.tavily, "api_keys", [])
    if getattr(config, "tavily", None) else [],
)

# WebDAV 图片存储（未配置时 enabled=False，所有图片逻辑回退本地行为）
webdav = WebDAVService(
    base_url=getattr(config, "webdav_url", "") or "",
    username=getattr(config, "webdav_username", "") or "",
    password=getattr(config, "webdav_password", "") or "",
    cache_dir=_ASSETS_DIR / "webdav_cache",
    url_secret=config.jwt_secret or ("dev-secret-change-me" if config.serve_frontend else ""),
)

orchestrator = None
if getattr(config, "agents", None) and config.agents.enabled:
    agent_models = {
        key: sa.model
        for key, sa in config.agents.sub_agents.items()
        if sa.model
    }
    tier_models = {}
    models_cfg = getattr(config.agents, "models", None)
    if models_cfg:
        tier_models = {
            "light": models_cfg.light,
            "standard": models_cfg.standard,
            "heavy": models_cfg.heavy,
        }
    orchestrator = AgentOrchestrator(
        api_base=config.llm_api_base,
        api_keys=config.llm_api_keys,
        main_model=config.llm_model,
        agent_models=agent_models,
        tier_models=tier_models,
        search_service=search_service,
    )
    orchestrator.set_webdav(webdav)
    llm.set_orchestrator(orchestrator)

image_service = ImageService(config)

# rembg 背景去除服务（从项目本地 models/rembg 目录加载模型）
rembg_service = RembgService(
    model=config.rembg_model,
    cache_dir=config.cache_dir,
    models_dir=config.models_dir,
)

# FFmpeg 画质增强服务（自动识别 GPU，核显/独显自动调整策略）
ffmpeg_enhance_service = FFmpegEnhanceService(
    enabled=config.ffmpeg_enhance_enabled,
)

# 画质增强服务（基于 gpt-image-2 API）
enhance_service = EnhanceService(
    image_service=image_service,
    enabled=True,
)

# ---------------------------------------------------------------------------
# FastAPI 应用
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理（启动 / 关闭钩子）。"""
    # ---- Startup ----
    logger.info("应用启动中...")
    logger.info(
        "运行模式: %s (serve_frontend=%s)",
        "前端托管 + API" if config.serve_frontend else "纯 API",
        config.serve_frontend,
    )

    # WebDAV 连通性自检（失败仅 warning，不阻断启动；运行期读写仍有本地缓存兜底）
    if webdav.enabled:
        try:
            wd_status = await webdav.test_connection()
            if wd_status.get("ok"):
                logger.info("WebDAV 连接正常: %s", webdav.base_url)
            else:
                logger.warning(
                    "WebDAV 连接失败（图片读写将降级/报错）: %s", wd_status.get("error")
                )
        except Exception as ex:
            logger.warning("WebDAV 自检异常: %s", ex)

    # 开发环境自动创建表（生产环境使用 Alembic 迁移）
    async with db_engine.begin() as conn:
        await conn.run_sync(models.Base.metadata.create_all)

    # 启动后台任务：每小时清理一次 assets 目录中的过期临时文件
    global _cleanup_task

    async def _cleanup_loop():
        while True:
            try:
                await asyncio.to_thread(_cleanup_old_assets, days=7)
            except Exception as ex:
                logger.warning("[CLEANUP] assets 清理失败: %s", ex)
            await asyncio.sleep(3600)

    _cleanup_task = asyncio.create_task(_cleanup_loop())

    yield

    # ---- Shutdown ----
    logger.info("应用关闭中...")
    if _cleanup_task is not None:
        _cleanup_task.cancel()
        await asyncio.gather(_cleanup_task, return_exceptions=True)
    await db_engine.dispose()
    await image_service.close()
    await webdav.close()
    await memory.close()
    await llm.close()
    await billing.close()
    await search_service.close()
    if orchestrator:
        await orchestrator.close()


app = FastAPI(title="灵犀 LX", docs_url=None, redoc_url=None, lifespan=lifespan)

# ---------------------------------------------------------------------------
# CORS：来源由 config.cors_origins 控制（默认 * 允许所有）
# 规范限制：allow_origins 含 * 时响应不得携带 credentials（浏览器会拒绝），
# 前端使用 Bearer header 鉴权不依赖 cookie，故 * 时 credentials=False 无影响。
# Tauri 生产环境可在 config.yaml 配置 cors_origins 为 tauri://localhost 等源。
# ---------------------------------------------------------------------------
_cors_origins = config.cors_origins or ["*"]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_credentials=("*" not in _cors_origins),
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# GZip 压缩中间件：压缩大型 base64 图片响应（1-5MB），减少带宽消耗
# ---------------------------------------------------------------------------
app.add_middleware(GZipMiddleware, minimum_size=1024)

# ---------------------------------------------------------------------------
# Bearer Token 鉴权（仅校验 /api/* 路径）
# 优先验证用户登录 token（AuthService），降级使用旧 API_TOKEN
# /api/auth/login 无需认证；/ 、/static/* 、/uploads/* 等不做校验
# ---------------------------------------------------------------------------
_API_TOKEN = os.environ.get("API_TOKEN")
if not _API_TOKEN:
    logger.warning("API_TOKEN 未配置，旧服务令牌回退已禁用")

# 无需认证的 API 路径白名单
_AUTH_FREE_PATHS = {
    "/api/auth/login",
    "/api/auth/register",
    "/api/auth/refresh",
    "/api/health",
    "/api/live",
}


@app.middleware("http")
async def bearer_auth_middleware(request, call_next):
    path = request.url.path
    # 只校验 /api/* 路径；放行 CORS 预检 OPTIONS 请求
    if not path.startswith("/api/") or request.method == "OPTIONS":
        return await call_next(request)
    # 白名单放行（登录/注册/刷新接口）
    if path in _AUTH_FREE_PATHS:
        return await call_next(request)

    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        return JSONResponse({"error": "登录已过期，请重新登录"}, status_code=401)

    token = auth_header[len("Bearer "):]

    # 验证 JWT token（返回 {user_id, username, role}，user_id 为 JWT sub 的 UUID 字符串）
    payload = await auth.verify_token(token)
    if payload:
        request.state.user_id = payload.get("user_id")
        request.state.username = payload.get("username")
        request.state.role = payload.get("role")
        return await call_next(request)

    # 兼容：验证旧 API_TOKEN（可选保留，用于服务间调用；未配置时禁用该回退）
    if _API_TOKEN and hmac.compare_digest(token, _API_TOKEN):
        request.state.user_id = None
        request.state.username = None
        request.state.role = "service"
        return await call_next(request)

    return JSONResponse({"error": "登录已过期，请重新登录"}, status_code=401)


# 禁用缓存的中间件（开发环境）—— no-cache 响应头单点负责，
# index 路由与 StaticFiles 不再重复设置
@app.middleware("http")
async def add_cache_control(request, call_next):
    response = await call_next(request)
    # 静态文件和 HTML 禁用缓存
    path = request.url.path
    if (path.endswith(('.html', '.js', '.css')) or path == '/'
            or path.startswith('/static/')):
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response


# ---------------------------------------------------------------------------
# 图片处理统一并发闸门
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# 后台定时清理 assets 临时文件（每小时执行一次，删除 7 天前的文件）
# ---------------------------------------------------------------------------
_cleanup_task: asyncio.Task | None = None  # 保存引用避免被 GC


def _cleanup_old_assets(days: int = 7):
    """删除 assets 根目录下 7 天前的 upload_/mask_/generated_ 文件。

    只处理 assets 根目录下的匹配文件，不触碰 emoji/、fonts/、
    design_refs/ 等子目录。
    """
    now = time.time()
    max_age = days * 86400
    prefixes = ("upload_", "mask_", "generated_")
    count = 0
    for f in _ASSETS_DIR.iterdir():
        if not f.is_file():
            continue
        if not f.name.startswith(prefixes):
            continue
        try:
            if now - f.stat().st_mtime > max_age:
                f.unlink()
                count += 1
        except OSError:
            pass
    if count:
        logger.info("[CLEANUP] 删除了 %d 个 7 天前的临时图片文件", count)


# ---------------------------------------------------------------------------
# 图片 URL 解析（多租户 + WebDAV 兼容助手）
# ---------------------------------------------------------------------------


def _ext_mime(filename: str) -> str:
    """按扩展名推断图片 MIME（默认 jpeg）。"""
    name = (filename or "").lower()
    if name.endswith(".png"):
        return "image/png"
    if name.endswith(".webp"):
        return "image/webp"
    return "image/jpeg"


async def _resolve_image_bytes(image_url: str, current_uname: str) -> "tuple[bytes | None, str]":
    """把前端传来的图片 URL 解析为字节内容（兼容新旧两种 URL）。

    - 新格式 ``/uploads/{username}/{filename}``（strip 后两段）：
      webdav.enabled 时经 WebDAV/本地缓存读取；URL 中的 username 必须等于
      当前用户 sanitize 后的值，否则拒绝（记 warning，按无图处理）。
    - 旧格式 ``/uploads/{filename}`` 或 ``/{filename}``（一段）：从本地
      assets 目录读取（开发兼容）。
    返回 ``(bytes | None, mime)``。
    """
    if not image_url:
        return None, "image/jpeg"
    # put_file(sign=True) 生成的 URL 可能携带 ?sig=<32hex>，解析路径前剥离查询串
    rel = image_url.split("?", 1)[0].lstrip("/")
    if rel.startswith("uploads/"):
        rel = rel[len("uploads/"):]
    parts = rel.split("/")
    if len(parts) == 2 and parts[0] and parts[1]:
        img_user, filename = parts
        if ".." in filename:
            return None, _ext_mime(filename)
        if not webdav.enabled:
            # 未启用 WebDAV 时不存在用户目录格式，按无图处理
            return None, _ext_mime(filename)
        if WebDAVService.sanitize_username(img_user) != current_uname:
            logger.warning(
                "拒绝读取他人图片: url=%s current_user=%s", image_url, current_uname
            )
            return None, _ext_mime(filename)
        try:
            content = await webdav.get_file(img_user, filename)
        except Exception as ex:
            logger.warning("WebDAV 读取图片失败 %s: %s", image_url, ex)
            content = None
        return content, _ext_mime(filename)
    if len(parts) == 1 and parts[0]:
        filename = parts[0]
        if ".." in filename:
            return None, "image/jpeg"
        img_path = _ASSETS_DIR / filename
        if img_path.exists():
            return await asyncio.to_thread(img_path.read_bytes), _ext_mime(filename)
    return None, "image/jpeg"


# === Favicon ===

@app.get("/favicon.ico")
async def favicon():
    """返回简单的 SVG favicon，避免 404。"""
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">'
        '<rect width="32" height="32" rx="8" fill="#7A9B7A"/>'
        '<text x="16" y="22" font-size="18" text-anchor="middle" '
        'fill="white" font-family="sans-serif" font-weight="bold">灵</text>'
        '</svg>'
    )
    return Response(content=svg, media_type="image/svg+xml")


# === REST API: 健康检查（无需认证） ===

@app.get("/api/live")
async def api_live():
    """存活探针（liveness）：进程能响应即存活，不做任何依赖检查。

    用于 Kubernetes / Docker 的 liveness probe：
    只要 uvicorn 进程在运行就返回 200，避免因 DB 短暂抖动导致容器被杀。
    """
    return {"ok": True, "time": time.time()}


@app.get("/api/health")
async def api_health():
    """健康检查：ok 恒为 true（表示进程活着），各组件状态独立汇报。

    docker healthcheck 只看 HTTP 状态码 + ok 字段；任何组件故障时
    HTTP 仍返回 200，对应字段标记为 down。
    """
    # DB 检查（3s 超时保护，异常即 down）
    db_status = "up"
    try:
        async def _ping_db():
            async with async_session_factory() as s:
                await s.execute(text("SELECT 1"))
        await asyncio.wait_for(_ping_db(), timeout=3)
    except Exception as ex:
        logger.warning("health: db 检查失败: %s", ex)
        db_status = "down"

    # WebDAV 检查（未启用返回 disabled；启用时短超时探测）
    if webdav.enabled:
        try:
            wd = await asyncio.wait_for(webdav.test_connection(), timeout=3)
            webdav_status = "up" if wd.get("ok") else "down"
        except Exception as ex:
            logger.warning("health: webdav 检查失败: %s", ex)
            webdav_status = "down"
    else:
        webdav_status = "disabled"

    return {
        "ok": True,
        "time": time.time(),
        "db": db_status,
        "webdav": webdav_status,
    }


# === REST API: 用户认证 ===

from pydantic import BaseModel


class LoginRequest(BaseModel):
    username: str
    password: str


class ChangePasswordRequest(BaseModel):
    old_password: str
    new_password: str


@app.post("/api/auth/login")
async def auth_login(req: LoginRequest):
    """用户登录，返回 token 与账户信息。"""
    result = await auth.login(req.username, req.password)
    if result:
        return {"ok": True, **result}
    return JSONResponse(
        {"ok": False, "error": "用户名或密码错误"},
        status_code=401,
    )


@app.post("/api/auth/logout")
async def auth_logout(request: Request):
    """退出登录，使 token 失效。"""
    auth_header = request.headers.get("Authorization", "")
    token = auth_header[len("Bearer "):] if auth_header.startswith("Bearer ") else ""
    if token:
        await auth.logout(token)
    return {"ok": True}


@app.get("/api/auth/me")
async def auth_me(request: Request):
    """获取当前登录用户信息。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        # 旧 API_TOKEN 服务间调用，返回 guest 信息
        return {"ok": True, "username": "guest", "balance": None}
    account = await auth.get_user_by_id(user_id)
    if account:
        return {"ok": True, **account}
    return JSONResponse({"ok": False, "error": "账户不存在"}, status_code=404)


@app.post("/api/auth/change-password")
async def auth_change_password(request: Request, req: ChangePasswordRequest):
    """修改密码。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    ok = await auth.change_password(user_id, req.old_password, req.new_password)
    if ok:
        return {"ok": True}
    return JSONResponse({"ok": False, "error": "旧密码错误"}, status_code=400)


class RegisterRequest(BaseModel):
    username: str
    password: str


class RefreshRequest(BaseModel):
    refresh_token: str


class ApproveUserRequest(BaseModel):
    user_id: str


@app.post("/api/auth/register")
async def auth_register(req: RegisterRequest):
    """用户注册。首个用户自动成为admin且免审批，其余用户需管理员审批。"""
    result = await auth.register(
        username=req.username,
        password=req.password,
        admin_username=config.admin_username,
    )
    if result["success"]:
        return {"ok": True, **result}
    return JSONResponse({"ok": False, "error": result["message"]}, status_code=400)


@app.post("/api/auth/refresh")
async def auth_refresh(req: RefreshRequest):
    """用refresh_token换取新的access_token。"""
    result = await auth.refresh_token(req.refresh_token)
    if result:
        return {"ok": True, **result}
    return JSONResponse({"ok": False, "error": "refresh_token无效或已过期"}, status_code=401)


@app.get("/api/auth/admin/users")
async def admin_list_users(user: User = Depends(require_admin)):
    """管理员查看所有用户列表。"""
    users = await auth.list_users()
    return {"ok": True, "users": users}


@app.post("/api/auth/admin/approve")
async def admin_approve_user(req: ApproveUserRequest, user: User = Depends(require_admin)):
    """管理员审批用户（pending -> active）。"""
    ok = await auth.approve_user(req.user_id)
    if ok:
        return {"ok": True}
    return JSONResponse({"ok": False, "error": "用户不存在或状态错误"}, status_code=400)


@app.post("/api/auth/admin/suspend")
async def admin_suspend_user(req: ApproveUserRequest, user: User = Depends(require_admin)):
    """管理员封禁用户（active -> suspended）。"""
    ok = await auth.suspend_user(req.user_id)
    if ok:
        return {"ok": True}
    return JSONResponse({"ok": False, "error": "用户不存在或状态错误"}, status_code=400)


@app.post("/api/auth/admin/activate")
async def admin_activate_user(req: ApproveUserRequest, user: User = Depends(require_admin)):
    """管理员解封用户（suspended -> active）。"""
    ok = await auth.activate_user(req.user_id)
    if ok:
        return {"ok": True}
    return JSONResponse({"ok": False, "error": "用户不存在或状态错误"}, status_code=400)


# === REST API: 计费 ===


@app.get("/api/billing/summary")
async def billing_summary(request: Request):
    """获取计费摘要（余额、yunwu 消耗、已扣费等）。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    try:
        summary = await billing.get_billing_summary(user_id)
        return {"ok": True, **summary}
    except Exception as ex:
        logger.error("获取计费摘要失败: %s", ex)
        return JSONResponse({"ok": False, "error": "计费查询失败"}, status_code=500)


@app.post("/api/billing/charge")
async def billing_charge(request: Request):
    """执行计费扣款：计算 yunwu 增量消耗并扣减余额。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    try:
        result = await billing.charge(user_id)
        return {"ok": True, **result}
    except Exception as ex:
        logger.error("计费扣款失败: %s", ex)
        return JSONResponse({"ok": False, "error": "计费扣款失败"}, status_code=500)


@app.get("/api/billing/keys")
async def billing_keys_usage(request: Request, user: User = Depends(require_admin)):
    """查询所有 yunwu key 的消耗详情（仅管理员）。"""
    try:
        usage = await billing.get_all_keys_usage()
        return {"ok": True, **usage}
    except Exception as ex:
        logger.error("查询 key 消耗失败: %s", ex)
        return JSONResponse({"ok": False, "error": "查询失败"}, status_code=500)


@app.post("/api/billing/init-baseline")
async def billing_init_baseline(request: Request):
    """初始化用户的 yunwu 消耗基准。"""
    user_id = getattr(request.state, "user_id", None)
    if not user_id:
        return JSONResponse({"ok": False, "error": "请先登录"}, status_code=401)
    try:
        result = await billing.init_baseline(user_id)
        return {"ok": True, **result}
    except Exception as ex:
        logger.error("初始化基准失败: %s", ex)
        return JSONResponse({"ok": False, "error": "初始化失败"}, status_code=500)


# === REST API: 会话管理 ===

@app.get("/api/sessions")
async def list_sessions(request: Request):
    """获取当前用户的会话列表。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    sessions = await storage.list_sessions_async(user_id)
    return {"sessions": sessions}


@app.post("/api/sessions")
async def create_session(request: Request, title: str = "新对话"):
    """创建新会话。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    session = await storage.create_session_async(user_id, title)
    return session


@app.delete("/api/sessions/{session_id}")
async def delete_session(request: Request, session_id: str):
    """删除会话及其聊天记录。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await storage.delete_session_async(user_id, session_id)
    return {"ok": True}


@app.get("/api/sessions/{session_id}/history")
async def get_history(request: Request, session_id: str):
    """获取指定会话的聊天记录（越权返回空列表）。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    history = await storage.load_chat_history_async(user_id, session_id)
    return {"history": history}


@app.post("/api/sessions/{session_id}/pin")
async def pin_session(request: Request, session_id: str):
    """置顶会话。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await storage.pin_session_async(user_id, session_id)
    return {"ok": True}


@app.post("/api/sessions/{session_id}/unpin")
async def unpin_session(request: Request, session_id: str):
    """取消置顶。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await storage.unpin_session_async(user_id, session_id)
    return {"ok": True}


@app.post("/api/sessions/{session_id}/rename")
async def rename_session(request: Request, session_id: str, title: str):
    """重命名会话。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await storage.rename_session_async(user_id, session_id, title)
    return {"ok": True}


# === REST API: 图片上传 ===

@app.post("/api/upload")
async def upload_image(request: Request, file: UploadFile = File(...)):
    """上传图片，自动压缩到1024px以内。
    架构改造：不再落地存储，直接返回 base64 data URL 供前端本地保存。
    """
    _, username = _require_user(request)
    if not username:
        return _unauthorized()
    validated = await prepare_image(file)
    content = validated.content
    # 自动压缩到 1024px
    # 架构改造：返回 base64 data URL，不再写 WebDAV/assets
    b64 = base64.b64encode(content).decode("utf-8")
    img_id = f"upl_{uuid.uuid4().hex[:12]}"
    return {"success": True, "image": f"data:{validated.mime};base64,{b64}", "id": img_id}


# === REST API: 记忆管理 ===

@app.post("/api/memory/clear")
async def clear_memory(request: Request):
    """清除长期记忆（用户画像）。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    await memory.clear_profile(user_id)
    return {"ok": True}


@app.get("/api/search/usage")
async def get_search_usage(request: Request):
    """获取 Tavily 搜索用量统计（用户视角返回当月用量；服务调用返回全局 Key 用量）。"""
    user_id = getattr(request.state, "user_id", None)  # None 时给全局统计
    stats = await search_service.get_usage_stats(user_id)
    total = sum(s["used"] for s in stats.values())
    return {"keys": stats, "total_used": total}


@app.get("/api/memory/profile")
async def get_memory_profile(request: Request):
    """获取用户画像文本。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    ctx = await memory.get_context(user_id, "_profile")
    return {"profile": LLMService._format_profile_text(ctx["profile"])}


# === REST API: 改图（独立模式，不走 Agent 编排）===

@app.post("/api/image-edit")
async def image_edit_direct(
    request: Request,
    file: UploadFile = File(...),
    prompt: str = Form(""),
    resolution: str = Form("1K"),
    ratio: str = Form("1:1"),
):
    """直接图生图编辑（改图卡片独立模式）。

    接收上传的图片 + 提示词，调用 image_service.edit_image。
    image_service 直接返回 bytes，本端点负责 base64 编码后返回 data URL。
    """
    from utils.size_validator import get_size

    _, username = _require_user(request)
    if not username:
        return _unauthorized()

    # 余额熔断：余额不足返回 402
    user_id = getattr(request.state, "user_id", None)
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    validated = await prepare_image(file)
    content = validated.content
    ext = ".jpg"  # 压缩后统一为 JPEG

    size = get_size(resolution, ratio)

    try:
        result = await image_service.edit_image(
            image_bytes=content,
            prompt=prompt,
            size=size,
            quality="high",
            output_format="png",
        )

        if result.get("success") and result.get("images"):
            img_bytes = result["images"][0]["bytes"]
            b64 = base64.b64encode(img_bytes).decode("utf-8")
            return {
                "success": True,
                "image": f"data:image/png;base64,{b64}",
            }
        else:
            logger.error("image-edit 上游失败: %s", result.get("error"))
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": "生成失败，请稍后重试"},
                status_code=502,
            )
    except httpx.TimeoutException:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "上游图片服务超时"},
            status_code=504,
        )
    except Exception as ex:
        logger.error("image-edit 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "图片编辑服务暂时不可用"},
            status_code=502,
        )


# === REST API: VLM 物体检测（改图标注模式）===

@app.post("/api/vlm-detect")
async def vlm_detect(request: Request, file: UploadFile = File(...)):
    """用 qwen-vl-max 检测图中所有物品的位置区域。

    返回结构化 JSON：
    {
        "objects": [
            {"label": "沙发", "bbox": {"x": 0.1, "y": 0.2, "w": 0.5, "h": 0.3}, "id": 1},
            ...
        ]
    }
    """
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()

    # 余额熔断
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_vlm)
    if not _bal_ok:
        return _bal_err

    validated = await prepare_image(file)
    content = validated.content

    # 编码为 base64
    image_b64 = base64.b64encode(content).decode("utf-8")
    mime = validated.mime

    # 使用编排器的视觉 Agent 进行物体检测
    if orchestrator is None:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "视觉分析服务不可用"},
            status_code=503,
        )

    detect_prompt = (
        "请分析这张摄影照片，识别照片中的关键元素和区域。\n"
        "对每个元素，返回其名称和位置区域（用归一化坐标表示）。\n\n"
        "需要识别的元素类型：\n"
        "- 主体（人物、物体、建筑等）\n"
        "- 背景（天空、墙面、地面等）\n"
        "- 干扰物（杂物、穿帮、瑕疵等）\n\n"
        "返回JSON格式（只返回JSON，不要其他文字）：\n"
        '{\n'
        '  "objects": [\n'
        '    {"label": "元素名称", "bbox": {"x": 0.0-1.0, "y": 0.0-1.0, "w": 0.0-1.0, "h": 0.0-1.0}},\n'
        '    ...\n'
        '  ]\n'
        '}\n'
    )

    try:
        result = await orchestrator.run_vision_agent(
            user_message=detect_prompt,
            image_base64=image_b64,
            image_mime=mime,
        )

        if result.success:
            # 尝试解析 JSON
            text = result.content.strip()
            # 清理 markdown 代码块
            if text.startswith("```"):
                parts = text.split("```")
                if len(parts) >= 2:
                    text = parts[1]
                    if text.startswith("json"):
                        text = text[4:]
                    text = text.strip()

            try:
                data = json.loads(text)
                # 为每个物品添加 id
                for i, obj in enumerate(data.get("objects", []), 1):
                    obj["id"] = i
                # 客户端依赖 success 字段判断检测是否成功，必须显式返回
                data["success"] = True
                return data
            except json.JSONDecodeError:
                return {"success": False, "objects": [], "raw": result.content}
        else:
            logger.error("vlm-detect 上游失败: %s", result.error)
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": "视觉分析服务暂时不可用"},
                status_code=502,
            )
    except httpx.TimeoutException:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "视觉分析服务超时"},
            status_code=504,
        )
    except Exception as ex:
        # 不向前端泄露上游 API 地址等内部细节，仅记录到服务端日志
        logger.error("vlm-detect 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "视觉分析服务暂时不可用"},
            status_code=502,
        )


@app.post("/api/image-edit-annotated")
async def image_edit_annotated(
    request: Request,
    file: UploadFile = File(...),
    prompt: str = Form(""),
    regions: str = Form("[]"),
    resolution: str = Form("1K"),
    ratio: str = Form("1:1"),
):
    """带区域标注的图生图编辑。

    接收图片 + 提示词 + 选中区域列表，
    自动生成 alpha mask，调用 image_service.edit_image。
    输入图/mask 落本地临时文件；结果图在 WebDAV 启用时写入用户目录。
    """
    from io import BytesIO
    from utils.size_validator import get_size

    _, username = _require_user(request)
    if not username:
        return _unauthorized()

    # 余额熔断
    user_id = getattr(request.state, "user_id", None)
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    validated = await prepare_image(file)
    content = validated.content

    try:
        regions_list = json.loads(regions)
    except json.JSONDecodeError:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "regions 参数不是合法 JSON"},
            status_code=422,
        )

    # 保存原图（压缩后统一 JPEG）
    ext = ".jpg"

    size = get_size(resolution, ratio)

    # 生成 mask bytes（在内存中生成，不落地临时文件）
    mask_bytes = None
    if regions_list:
        try:
            from PIL import Image, ImageDraw
            img = Image.open(BytesIO(content))
            w, h = img.size
            mask = Image.new("RGBA", (w, h), (0, 0, 0, 255))
            draw = ImageDraw.Draw(mask)
            for region in regions_list:
                # 矩形 bbox mask
                bbox = region.get("bbox", {})
                x0 = int(bbox.get("x", 0) * w)
                y0 = int(bbox.get("y", 0) * h)
                x1 = int((bbox.get("x", 0) + bbox.get("w", 0)) * w)
                y1 = int((bbox.get("y", 0) + bbox.get("h", 0)) * h)
                # 透明区域 = 可编辑
                draw.rectangle([x0, y0, x1, y1], fill=(0, 0, 0, 0))
            mask_buf = BytesIO()
            mask.save(mask_buf, format="PNG")
            mask_bytes = mask_buf.getvalue()
        except ImportError:
            import logging
            logging.warning("Pillow not installed, skipping mask generation")

    try:
        result = await image_service.edit_image(
            image_bytes=content,
            prompt=prompt,
            size=size,
            quality="high",
            output_format="png",
            mask_bytes=mask_bytes,
        )

        if result.get("success") and result.get("images"):
            img_bytes = result["images"][0]["bytes"]
            b64 = base64.b64encode(img_bytes).decode("utf-8")
            return {"success": True, "image": f"data:image/png;base64,{b64}"}
        else:
            logger.error("image-edit-annotated 上游失败: %s", result.get("error"))
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": "生成失败，请稍后重试"},
                status_code=502,
            )
    except httpx.TimeoutException:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "上游图片服务超时"},
            status_code=504,
        )
    except Exception as ex:
        logger.error("image-edit-annotated 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "图片编辑服务暂时不可用"},
            status_code=502,
        )


# === REST API: 背景去除（rembg）===

@app.post("/api/rembg-remove")
async def rembg_remove(
    request: Request,
    file: UploadFile = File(...),
    alpha_matting: bool = Form(False),
):
    """一键去除背景，返回透明PNG。"""
    _, username = _require_user(request)
    if not username:
        return _unauthorized()

    # 余额熔断：预扣费逻辑与 image-edit 一致
    user_id = getattr(request.state, "user_id", None)
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    validated = await prepare_image(file)
    content = validated.content

    try:
        result = await rembg_service.remove_background(content, alpha_matting=alpha_matting)
        if result.get("success") and result.get("images"):
            img_bytes = result["images"][0]["bytes"]
            b64 = base64.b64encode(img_bytes).decode("utf-8")
            return {"success": True, "image": f"data:image/png;base64,{b64}"}
        else:
            logger.error("rembg-remove 失败: %s", result.get("error"))
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": result.get("error", "背景去除失败")},
                status_code=502,
            )
    except Exception as ex:
        logger.error("rembg-remove 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "背景去除服务暂时不可用"},
            status_code=502,
        )


# === REST API: 批量去背景 ===

@app.post("/api/batch-rembg")
async def batch_remove_background(
    request: Request,
    files: List[UploadFile] = File(...),
    alpha_matting: bool = Form(False),
):
    """批量去除图片背景。支持最多 20 张图片同时处理。"""
    if len(files) > 20:
        return JSONResponse(
            {"success": False, "error": "单次最多处理 20 张图片"},
            status_code=400,
        )

    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()

    # 余额熔断：按图片数量计算预扣费（批量操作部分失败不退款）
    _precharge_total = config.precharge_image * len(files)
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, _precharge_total)
    if not _bal_ok:
        return _bal_err

    validated_images = await read_validated_images(files)

    async def _process(i, file, image):
        result = await rembg_service.remove_background(
            image.content, alpha_matting=alpha_matting
        )
        item = {"filename": file.filename or f"image_{i}.jpg", "success": result.get("success", False)}
        if result.get("success") and result.get("images"):
            img_bytes = result["images"][0]["bytes"]
            b64 = base64.b64encode(img_bytes).decode("utf-8")
            item["image"] = f"data:image/png;base64,{b64}"
        else:
            item["error"] = result.get("error", "去背景失败")
        return item

    results = await asyncio.gather(*(
        _process(i, file, image)
        for i, (file, image) in enumerate(zip(files, validated_images))
    ))
    
    success_count = sum(1 for r in results if r["success"])
    return {
        "success": success_count > 0,
        "total": len(files),
        "success_count": success_count,
        "results": results,
    }


# === REST API: 画质增强（基于 gpt-image-2 API）===

@app.post("/api/enhance")
async def enhance_image(
    request: Request,
    file: UploadFile = File(...),
    mode: str = Form("super_resolution"),
    scale: int = Form(2),
):
    """画质增强（基于 gpt-image-2 API）。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()

    # 余额熔断：预扣费逻辑与 image-edit 一致
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    validated = await prepare_image(file)
    content = validated.content
    try:
        result = await enhance_service.enhance(content, mode=mode, scale=scale)
        if result.get("success") and result.get("images"):
            img_bytes = result["images"][0]["bytes"]
            b64 = base64.b64encode(img_bytes).decode("utf-8")
            return {"success": True, "image": f"data:image/png;base64,{b64}"}
        else:
            logger.error("enhance 失败: %s", result.get("error"))
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(result, status_code=500)
    except Exception as ex:
        logger.error("enhance 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "画质增强服务暂时不可用"},
            status_code=502,
        )


# === REST API: FFmpeg 分辨率提升（自动 GPU 识别）===

@app.get("/api/ffmpeg-gpu-info")
async def ffmpeg_gpu_info(request: Request):
    """获取 ffmpeg GPU 检测信息。"""
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()
    gpu_info = await ffmpeg_enhance_service.get_gpu_info()
    return {"success": True, "gpu": gpu_info}


@app.post("/api/ffmpeg-enhance")
async def ffmpeg_enhance_image(
    request: Request,
    file: UploadFile = File(...),
    mode: str = Form("super_resolution"),
    scale: int = Form(2),
):
    """FFmpeg 分辨率提升（自动识别 GPU 策略）。

    mode: super_resolution / sharpen / denoise / color_enhance
    scale: 放大倍数（仅 super_resolution 有效，2 或 4）
    """
    user_id, _ = _require_user(request)
    if not user_id:
        return _unauthorized()

    # 余额熔断
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    validated = await prepare_image(file)
    content = validated.content
    try:
        result = await ffmpeg_enhance_service.enhance(content, mode=mode, scale=scale)
        if result.get("success") and result.get("images"):
            img_bytes = result["images"][0]["bytes"]
            b64 = base64.b64encode(img_bytes).decode("utf-8")
            return {"success": True, "image": f"data:image/png;base64,{b64}"}
        else:
            logger.error("ffmpeg enhance 失败: %s", result.get("error"))
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(result, status_code=500)
    except Exception as ex:
        logger.error("ffmpeg enhance 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "FFmpeg 画质增强服务暂时不可用"},
            status_code=502,
        )


# ─── 摄影修图预设 ───

PHOTOGRAPHY_PRESETS = [
    {
        "id": "japanese_fresh",
        "name": "日系清新",
        "category": "color",
        "description": "略微过曝、柔和粉彩、淡绿淡蓝色调、降低对比度",
        "prompt": "Apply a Japanese fresh photography style to this image: slightly overexposed, soft pastel colors, light green and blue tones, gentle contrast reduction, bright and airy atmosphere. Preserve all original content and composition.",
        "icon": "🌸",
    },
    {
        "id": "cinematic",
        "name": "电影感调色",
        "category": "color",
        "description": "Teal & Orange 色调、高对比度、电影画幅感",
        "prompt": "Apply a cinematic color grade to this image: teal and orange color scheme, high contrast, deep shadows, warm highlights, film-like color grading with a cinematic atmosphere. Preserve all original content and composition.",
        "icon": "🎬",
    },
    {
        "id": "vintage_film",
        "name": "复古胶片",
        "category": "color",
        "description": "胶片颗粒感、暖色调、轻微褪色",
        "prompt": "Apply a vintage film photography look to this image: add subtle film grain, warm color cast, slight fade and muted tones, nostalgic atmosphere reminiscent of old 35mm film. Preserve all original content and composition.",
        "icon": "🎞️",
    },
    {
        "id": "portrait_retouch",
        "name": "人像精修",
        "category": "retouch",
        "description": "自然磨皮、肤色均匀、五官立体、保留质感",
        "prompt": "Perform natural portrait retouching on this image: smooth skin while preserving natural texture, even out skin tone, enhance facial features subtly, remove blemishes, brighten eyes, maintain the person's natural identity. Do not over-process.",
        "icon": "✨",
    },
    {
        "id": "landscape_enhance",
        "name": "风景增强",
        "category": "enhance",
        "description": "提升动态范围、增强细节、天空和地面层次",
        "prompt": "Enhance this landscape photograph: increase dynamic range, enhance fine details in foreground and background, bring out sky details and cloud definition, improve overall sharpness and depth. Keep the natural look without over-saturation.",
        "icon": "🏔️",
    },
    {
        "id": "black_white",
        "name": "黑白艺术",
        "category": "color",
        "description": "高质量黑白转换、层次丰富、对比考究",
        "prompt": "Convert this image to artistic black and white: rich tonal range from deep blacks to bright whites, excellent mid-tone separation, dramatic contrast, emphasize texture and form. Create a timeless fine-art photography look.",
        "icon": "⚫",
    },
    {
        "id": "warm_sunset",
        "name": "暖阳余晖",
        "category": "color",
        "description": "金色暖调、柔和高光、日落氛围",
        "prompt": "Apply a warm sunset color grade to this image: golden warm tones, soft glowing highlights, orange and amber color cast in highlights, slightly cooler shadows for balance. Create a cozy late-afternoon atmosphere. Preserve all original content.",
        "icon": "🌅",
    },
    {
        "id": "clean_background",
        "name": "纯净背景",
        "category": "retouch",
        "description": "去除杂物、净化背景、突出主体",
        "prompt": "Clean up the background of this image: remove distracting elements and clutter from the background, simplify and purify the background while keeping the main subject sharp and prominent. Maintain natural lighting and shadows on the subject.",
        "icon": "🧹",
    },
    {
        "id": "hdr_enhance",
        "name": "HDR 增强",
        "category": "enhance",
        "description": "高动态范围、暗部提亮、高光压暗、细节丰富",
        "prompt": "Apply HDR-style enhancement to this image: lift shadow details, recover highlight information, increase local contrast and micro-contrast, reveal hidden details in both dark and bright areas. Keep the result natural, not over-processed.",
        "icon": "🌈",
    },
    {
        "id": "night_enhance",
        "name": "夜景优化",
        "category": "enhance",
        "description": "降噪、提亮暗部、保持霓虹灯色彩",
        "prompt": "Enhance this night photography image: reduce noise in dark areas, brighten shadows to reveal details, preserve and enhance neon light colors and city light bokeh, improve overall clarity. Maintain the night atmosphere.",
        "icon": "🌃",
    },
]


@app.get("/api/presets")
async def get_presets(request: Request):
    """获取摄影修图预设列表。"""
    return {"success": True, "presets": PHOTOGRAPHY_PRESETS}


@app.post("/api/preset-apply")
async def apply_preset(
    request: Request,
    file: UploadFile = File(...),
    preset_id: str = Form(...),
):
    """应用摄影修图预设到上传的图片。"""
    # 查找预设
    preset = next((p for p in PHOTOGRAPHY_PRESETS if p["id"] == preset_id), None)
    if preset is None:
        return JSONResponse(
            {"success": False, "error": f"未找到预设: {preset_id}"},
            status_code=400,
        )

    validated = await prepare_image(file)
    content = validated.content

    try:
        result = await image_service.edit_image(
            image_bytes=content,
            prompt=preset["prompt"],
            quality="high",
            output_format="png",
            timeout=300,
            max_attempts=2,
        )

        if result.get("success") and result.get("images"):
            img_bytes = result["images"][0]["bytes"]
            b64 = base64.b64encode(img_bytes).decode("utf-8")
            return {
                "success": True,
                "image": f"data:image/png;base64,{b64}",
                "preset_id": preset_id,
                "preset_name": preset["name"],
            }
        else:
            return JSONResponse(
                {"success": False, "error": result.get("error", "预设应用失败")},
                status_code=500,
            )
    except Exception as ex:
        logger.error("preset-apply 异常: %s", ex)
        return JSONResponse(
            {"success": False, "error": f"预设应用异常: {ex}"},
            status_code=500,
        )


# === REST API: 图片格式导出 ===

@app.post("/api/export")
async def export_image(
    request: Request,
    file: UploadFile = File(...),
    format: str = Form("jpeg"),
    quality: int = Form(90),
):
    """图片格式转换与导出。支持 jpeg/png/webp 格式，可设置质量参数。"""
    from PIL import Image
    import io
    
    validated = await prepare_image(file)
    content = validated.content
    
    try:
        img = await asyncio.to_thread(lambda: Image.open(io.BytesIO(content)))
        
        # 格式映射
        format_map = {"jpeg": "JPEG", "jpg": "JPEG", "png": "PNG", "webp": "WEBP"}
        pil_format = format_map.get(format.lower(), "JPEG")
        
        # JPEG/JPG 不支持透明通道，需要合成白色背景
        if pil_format == "JPEG" and img.mode in ("RGBA", "LA", "P"):
            background = Image.new("RGB", img.size, (255, 255, 255))
            if img.mode == "P":
                img = img.convert("RGBA")
            background.paste(img, mask=img.split()[-1] if img.mode == "RGBA" else None)
            img = background
        elif img.mode != "RGB" and pil_format == "JPEG":
            img = img.convert("RGB")
        
        output = io.BytesIO()
        
        def _save():
            img.save(output, format=pil_format, quality=quality if pil_format != "PNG" else None)
        
        await asyncio.to_thread(_save)
        
        result_bytes = output.getvalue()
        b64 = base64.b64encode(result_bytes).decode("utf-8")
        mime = f"image/{format.lower()}"
        
        return {
            "success": True,
            "image": f"data:{mime};base64,{b64}",
            "format": format.lower(),
            "size": len(result_bytes),
        }
    except Exception as ex:
        logger.error("export 异常: %s", ex)
        return JSONResponse(
            {"success": False, "error": f"图片导出失败: {ex}"},
            status_code=500,
        )


# === REST API: 多Agent协作修图 (P2-1) ===

@app.post("/api/collaborative-edit")
async def collaborative_edit(file: UploadFile = File(...), request: Request = None):
    """多Agent协作修图端点。

    流程：
    1. 照片分析师分析照片
    2. 构图顾问 + 调色专家 + 光线分析师并行给出建议
    3. 图效师综合专家建议执行图片增强

    返回最终的增强图片和各阶段状态。
    """
    if orchestrator is None:
        return JSONResponse(
            {"success": False, "error": "Agent编排系统未启用"},
            status_code=503,
        )

    user_id, username = _require_user(request)
    if not username:
        return _unauthorized()

    # 余额熔断
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    # 读取并处理图片
    validated = await prepare_image(file)
    content_bytes = validated.content

    image_data = base64.b64encode(content_bytes).decode("utf-8")

    try:
        stages = []
        final_image = ""

        async for event in orchestrator.run_collaborative_stream(
            user_message="请对这张照片进行专业级协作修图",
            image_data=image_data,
            image_mime=validated.mime,
            memory_context="",
            username=username,
        ):
            stage = {
                "type": event.type,
                "content": event.content,
                "agent_name": event.agent_name,
                "agent_key": event.agent_key,
                "agent_model": event.agent_model,
                "error": event.error,
            }
            if event.agents_dispatched:
                stage["agents_dispatched"] = event.agents_dispatched
            if event.route_reason:
                stage["route_reason"] = event.route_reason
            stages.append(stage)

            # 提取最终图片结果
            if event.type == "agent_done" and event.agent_key == "image_enhancer":
                if "[IMAGE]" in event.content:
                    final_image = event.content.split("[IMAGE]")[1].split("[/IMAGE]")[0]

        if final_image:
            return {
                "success": True,
                "image": final_image,
                "stages": stages,
            }
        else:
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": "协作修图未能生成图片", "stages": stages},
                status_code=502,
            )
    except httpx.TimeoutException:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "协作修图服务超时"},
            status_code=504,
        )
    except Exception as ex:
        logger.error("collaborative-edit 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "协作修图服务暂时不可用"},
            status_code=502,
        )


# === REST API: 跨照片风格迁移 (P2-3) ===

@app.post("/api/style-transfer")
async def style_transfer(
    target: UploadFile = File(...),
    reference: UploadFile = File(...),
    request: Request = None,
):
    """跨照片风格迁移端点。

    将参考图的风格（色调、对比度、光线风格等）应用到目标图上。

    参数：
    - target: 需要被应用风格的目标图片
    - reference: 风格参考图片
    """
    if orchestrator is None:
        return JSONResponse(
            {"success": False, "error": "Agent编排系统未启用"},
            status_code=503,
        )

    user_id, username = _require_user(request)
    if not username:
        return _unauthorized()

    # 余额熔断
    _bal_ok, _bal_err, _charged = await _check_balance_and_precharge(user_id, config.precharge_image)
    if not _bal_ok:
        return _bal_err

    # 读取并处理目标图
    target_validated = await prepare_image(target)
    target_bytes = target_validated.content
    target_b64 = base64.b64encode(target_bytes).decode("utf-8")

    # 读取并处理参考图
    reference_validated = await prepare_image(reference)
    reference_bytes = reference_validated.content
    reference_b64 = base64.b64encode(reference_bytes).decode("utf-8")

    try:
        result = await orchestrator.run_style_transfer(
            target_image=target_b64,
            target_mime=target_validated.mime,
            reference_image=reference_b64,
            reference_mime=reference_validated.mime,
            user_request="",
            username=username,
        )

        if result.success and "[IMAGE]" in result.content:
            image_url = result.content.split("[IMAGE]")[1].split("[/IMAGE]")[0]
            style_analysis = ""
            if "[STYLE]" in result.content:
                style_analysis = result.content.split("[STYLE]")[1].split("[/STYLE]")[0]
            return {
                "success": True,
                "image": image_url,
                "style_analysis": style_analysis,
            }
        else:
            await _refund_on_failure(user_id, _charged)
            return JSONResponse(
                {"success": False, "error": result.error or "风格迁移失败"},
                status_code=502,
            )
    except httpx.TimeoutException:
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "风格迁移服务超时"},
            status_code=504,
        )
    except Exception as ex:
        logger.error("style-transfer 异常: %s", ex)
        await _refund_on_failure(user_id, _charged)
        return JSONResponse(
            {"success": False, "error": "风格迁移服务暂时不可用"},
            status_code=502,
        )


# === WebSocket: 聊天消息处理（从 ws_chat 循环中抽出，支持任务取消）===


async def _handle_chat_message(
    ws: WebSocket, msg: dict, user_id: str, username: str, uname: str
) -> None:
    """处理单条聊天消息，流式输出结果到 WebSocket。

    当新消息到达时，WebSocketTaskManager 会取消本任务。
    CancelledError 在 finally 清理心跳后自然传播，跳过历史保存。
    """
    # 余额熔断
    if not await _ws_check_balance(ws, user_id, config.precharge_chat):
        return

    session_id = msg.get("session_id", "")
    message = msg.get("message", "")
    image_url = msg.get("image_url", "")
    teaching_mode = msg.get("teaching_mode", False)

    # 消息长度限制：防止超长消息导致内存/性能问题
    if len(message) > 10000:
        await ws.send_json({"type": "error", "content": "消息长度不能超过 10000 字符"})
        return

    # 加载历史（越权返回空列表）
    history = await storage.load_chat_history_async(user_id, session_id)

    # 初始化记忆
    await memory.init_session(user_id, session_id, history)

    # 处理图片（架构改造：前端传 data URL，后端只解析不存储）
    image_data = ""
    image_mime = "image/jpeg"
    image_id_for_history = ""
    if image_url:
        if image_url.startswith("data:"):
            try:
                image_bytes, _ = parse_data_url(image_url)
            except Exception as ex:
                logger.warning("data URL 解析失败: %s", ex)
                image_bytes = None
        else:
            image_bytes, _ = await _resolve_image_bytes(image_url, uname)

        if image_bytes and len(image_bytes) <= MAX_UPLOAD_BYTES:
            try:
                async with PILLOW_SEMAPHORE:
                    compressed, image_mime = await asyncio.to_thread(
                        prepare_image_bytes, image_bytes
                    )
                image_data = base64.b64encode(compressed).decode("utf-8")
            except ValueError as ex:
                logger.warning("图片处理失败: %s", ex)
                image_data = ""
        elif image_bytes:
            logger.warning("图片超过 20MB 限制: %d bytes", len(image_bytes))

    full_response = ""
    has_image_result = False
    had_error = False

    # 心跳任务：每10秒发一次心跳，防止 WebSocket 超时断连
    async def _heartbeat():
        while True:
            await asyncio.sleep(10)
            try:
                await ws.send_json({"type": "heartbeat"})
            except Exception:
                break

    hb_task = asyncio.create_task(_heartbeat())

    try:
        if llm.has_orchestrator:
            async for event in llm.chat_with_agents(
                message, history, image_data, image_mime,
                session_id=session_id, user_id=user_id,
                username=username,
                teaching_mode=teaching_mode,
            ):
                event_dict = {
                    "type": event.type,
                    "content": event.content,
                    "agent_name": event.agent_name,
                    "agent_key": event.agent_key,
                    "agent_model": event.agent_model,
                    "agents_dispatched": event.agents_dispatched,
                    "route_reason": event.route_reason,
                    "error": event.error,
                }

                if event.type == "delta":
                    full_response += event.content
                elif event.type == "agent_done" and event.agent_key == "image_enhancer":
                    has_image_result = True
                    img_content = event.content
                    if "[IMAGE]" in img_content:
                        img_url = img_content.split("[IMAGE]")[1].split("[/IMAGE]")[0]
                        event_dict["image_url"] = img_url
                        full_response = img_content

                await ws.send_json(event_dict)
        else:
            async for chunk in llm.chat_stream(
                message, history, user_id=user_id, session_id=session_id
            ):
                full_response += chunk
                await ws.send_json({"type": "delta", "content": chunk})
            await ws.send_json({"type": "done"})

    except Exception as ex:
        had_error = True
        import traceback
        tb = traceback.format_exc()
        logger.error("WebSocket处理异常: %s\n%s", ex, tb)
        try:
            await ws.send_json({"type": "error", "content": "服务处理异常，请稍后重试"})
        except Exception:
            pass
    finally:
        hb_task.cancel()

    # 保存到历史（CancelledError 在 finally 后传播，不会到达此处）
    await storage.append_message_async(
        user_id, session_id, "user", message,
        images=[image_url] if image_url else None,
    )
    # 异常时跳过保存 assistant 消息（内容不完整），但仍保存 user 消息
    if not had_error and full_response:
        ai_images = None
        if has_image_result and "[IMAGE]" in full_response:
            ai_images = [full_response.split("[IMAGE]")[1].split("[/IMAGE]")[0]]
        await storage.append_message_async(
            user_id, session_id, "assistant", full_response,
            images=ai_images,
        )
    await storage.touch_session_async(user_id, session_id)

    # 更新记忆
    await memory.add_message(user_id, session_id, "user", message)
    if not had_error and full_response:
        ai_text_for_memory = "（生成图片）" if has_image_result else full_response
        await memory.add_message(user_id, session_id, "assistant", ai_text_for_memory)


# === WebSocket: 聊天流式 ===

@app.websocket("/ws/chat")
async def ws_chat(ws: WebSocket):
    """WebSocket 聊天端点。

    鉴权（accept 后）：
        - 兼容旧方式：query 携带 ?token=...
        - 首帧鉴权：首条消息 {"type": "auth", "token": "..."}，成功回 {"type":"auth_ok"}

    客户端发送:
        {"type": "chat", "session_id": "...", "message": "...", "image_url": "/upload_xxx.jpg"}

    服务端流式返回 AgentEvent:
        {"type": "routing"}
        {"type": "dispatch", "agents_dispatched": [...], "route_reason": "..."}
        {"type": "status", "content": "..."}
        {"type": "agent_done", "agent_name": "...", "agent_key": "...", "content": "..."}
        {"type": "agent_error", "agent_name": "...", "error": "..."}
        {"type": "synthesis_start"}
        {"type": "delta", "content": "..."}
        {"type": "done", "route_reason": "..."}
        {"type": "error", "content": "..."}
    """
    await ws.accept()
    query_token = ws.query_params.get("token", "")
    if query_token:
        # 兼容旧客户端：query 携带 token 直接验证
        # TODO: 前端全部迁移为首帧鉴权后移除此兼容分支
        payload = await auth.verify_token(query_token)
    else:
        # 首帧鉴权：等待客户端首条 {"type":"auth","token":"..."}（5 秒超时）
        payload = None
        try:
            first = await asyncio.wait_for(ws.receive_json(), timeout=5)
            if first.get("type") == "auth":
                payload = await auth.verify_token(str(first.get("token", "")))
        except Exception:
            payload = None
    if not payload:
        # 未授权：告知后按 4401 关闭，前端据此清理登录态
        try:
            await ws.send_json({"type": "error", "content": "unauthorized"})
        finally:
            await ws.close(code=4401)
        return
    if not query_token:
        # 首帧模式显式确认鉴权成功
        await ws.send_json({"type": "auth_ok"})

    user_id = payload["user_id"]
    username = payload["username"]
    uname = WebDAVService.sanitize_username(username)

    mgr = WebSocketTaskManager()

    try:
        while True:
            msg = await ws.receive_json()
            msg_type = msg.get("type", "")

            if msg_type == "ping":
                await ws.send_json({"type": "pong"})
                continue

            if msg_type != "chat":
                continue

            # 取消上一个正在处理的请求（新消息优先）
            await mgr.cancel_current()
            task = asyncio.create_task(
                _handle_chat_message(ws, msg, user_id, username, uname)
            )
            mgr.set_current(task)

    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("WebSocket 异常: %s", e)
    finally:
        await mgr.cancel_current()


# === 旧 URL 兼容：重定向到首页（仅托管前端时注册，纯 API 模式无意义） ===
from starlette.responses import RedirectResponse

if config.serve_frontend:
    @app.get("/inspiration")
    async def redirect_inspiration():
        """旧 Flet 灵感页面 → 新聊天页"""
        return RedirectResponse(url="/#/chat")

    @app.get("/image_edit")
    async def redirect_image_edit():
        """旧 Flet 改图页面 → 新改图页"""
        return RedirectResponse(url="/#/image-edit")

# === 静态文件（serve_frontend=True 托管前端；False 为纯 API 模式） ===

if config.serve_frontend:
    @app.get("/")
    async def index():
        """主页面（no-cache 响应头由 add_cache_control 中间件统一设置）。"""
        return FileResponse(str(_STATIC_DIR / "index.html"))
else:
    @app.get("/")
    async def index():
        """纯 API 模式根路由：返回服务标识 JSON（供部署探测）。"""
        return {"ok": True, "service": "lx-api", "version": "1.0"}


# 挂载前端静态文件（CSS/JS）— no-cache 响应头由 add_cache_control 中间件统一设置
if config.serve_frontend:
    _STATIC_DIR.mkdir(parents=True, exist_ok=True)
    (_STATIC_DIR / "css").mkdir(exist_ok=True)
    (_STATIC_DIR / "js").mkdir(exist_ok=True)
    app.mount("/static", StaticFiles(directory=str(_STATIC_DIR)), name="static")
    # HTML 使用相对路径（css/xxx, js/xxx, vendor/xxx），需将子目录挂载到根路径
    for _sub in ("css", "js", "vendor", "assets"):
        _sub_dir = _STATIC_DIR / _sub
        if _sub_dir.is_dir():
            app.mount(f"/{_sub}", StaticFiles(directory=str(_sub_dir)), name=f"frontend_{_sub}")
    # config.json 和 favicon.svg 也需根路径路由
    @app.get("/config.json")
    async def _serve_config_json():
        cfg_path = _STATIC_DIR / "config.json"
        if cfg_path.is_file():
            return FileResponse(str(cfg_path), media_type="application/json")
        # 文件不存在时返回默认同源配置，避免 500 错误
        return JSONResponse({"apiBase": ""})
    @app.get("/favicon.svg")
    async def _serve_favicon_svg():
        fav_path = _STATIC_DIR / "favicon.svg"
        if fav_path.is_file():
            return FileResponse(str(fav_path), media_type="image/svg+xml")
        raise HTTPException(404, "favicon not found")

# /uploads 静态挂载改为代理路由：
#   - 两段路径 /uploads/{username}/{filename} → WebDAV（带本地缓存兜底）
#   - 单段路径 /uploads/{filename}           → 本地 assets 旧文件
@app.get("/uploads/{path:path}")
async def serve_upload(path: str, request: Request):
    """按段数分发上传文件访问（防目录穿越；两段路径强制签名校验）。"""
    if ".." in path:
        raise HTTPException(400, "非法路径")
    parts = [p for p in path.split("/") if p != ""]
    if len(parts) == 2:
        username, filename = parts
        if ".." in filename or "/" in filename:
            raise HTTPException(400, "非法路径")
        if not webdav.enabled:
            raise HTTPException(404, "文件不存在")
        # 用户目录文件必须携带有效 ?sig= 签名（url_secret 为空时 verify_signature 恒 True）
        sig = request.query_params.get("sig", "")
        if not webdav.verify_signature(username, filename, sig):
            return JSONResponse({"error": "签名无效"}, status_code=403)
        try:
            content = await webdav.get_file(username, filename)
        except Exception as ex:
            logger.warning("WebDAV 读取失败 %s/%s: %s", username, filename, ex)
            content = None
        if content is None:
            raise HTTPException(404, "文件不存在")
        resp = Response(content, media_type=_ext_mime(filename))
        resp.headers["Cache-Control"] = "public, max-age=86400"
        return resp
    if len(parts) == 1:
        filename = parts[0]
        file_path = _ASSETS_DIR / filename
        if file_path.is_file():
            return FileResponse(str(file_path))
        raise HTTPException(404, "文件不存在")
    raise HTTPException(404, "文件不存在")

# 兼容旧 URL 格式：/xxx.jpg → /uploads/xxx.jpg
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request as StarletteRequest

class _LegacyUrlRedirect(BaseHTTPMiddleware):
    """将 /xxx.ext 的旧格式 URL 重定向到 /uploads/xxx.ext。"""
    # 不需要拦截的路径前缀
    _SKIP_PREFIXES = ("/api/", "/ws/", "/static/", "/uploads/")
    # serve_frontend=True 时额外跳过的前端路径
    _FRONTEND_PREFIXES = ("/css/", "/js/", "/vendor/", "/assets/", "/config.json", "/favicon.svg")

    async def dispatch(self, request: StarletteRequest, call_next):
        path = request.url.path
        # favicon 有专属路由（返回 SVG），不得重定向到 /uploads/
        if path == "/favicon.ico":
            return await call_next(request)
        # 只拦截根路径下的文件请求（如 /upload_xxx.jpg, /generated_xxx.png）
        skip = self._SKIP_PREFIXES
        if config.serve_frontend:
            skip = skip + self._FRONTEND_PREFIXES
        if not any(path.startswith(p) for p in skip):
            if "." in os.path.basename(path):
                # 是文件请求，重定向到 /uploads/
                new_url = request.url.replace(path=f"/uploads{path}")
                return RedirectResponse(url=new_url, status_code=307)
        return await call_next(request)

app.add_middleware(_LegacyUrlRedirect)


# === 启动 ===

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------


def _get_lan_ip() -> str:
    """获取局域网 IP。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "0.0.0.0"
    finally:
        s.close()


if __name__ == "__main__":
    _cleanup_old_assets()

    lan_ip = _get_lan_ip()
    print("=" * 55)
    print("  灵犀 LX — Photographer AI Assistant")
    print("  FastAPI + WebSocket + Static Files")
    print("  Local:   http://127.0.0.1:1988")
    print(f"  Network: http://{lan_ip}:1988")
    print("=" * 55)

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=1988,
        log_level="info",
        ws_ping_interval=10,
        ws_ping_timeout=60,
        timeout_keep_alive=30,
    )
