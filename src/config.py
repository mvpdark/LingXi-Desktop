"""Configuration module for the designer app.

Loads settings from config.yaml with sensible defaults.
Supports multi-agent orchestration with tiered model routing.
"""

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict

import yaml


@dataclass
class SubAgentConfig:
    """Sub-agent configuration."""
    model: str = ""


@dataclass
class ModelTiersConfig:
    """Tiered model configuration for complexity-based routing.

    - light:     greetings, simple Q&A → cheapest, fastest
    - standard:  normal design questions → balanced
    - heavy:     complex analysis, multi-domain → most capable
    """
    light: str = "gpt-5.4-nano"
    standard: str = "gpt-5.6-luna"
    heavy: str = "gpt-5.6-luna-max"


@dataclass
class AgentsConfig:
    """Multi-agent orchestration configuration."""
    enabled: bool = True
    models: ModelTiersConfig = field(default_factory=ModelTiersConfig)
    sub_agents: Dict[str, SubAgentConfig] = field(default_factory=dict)


@dataclass
class TavilyConfig:
    """Tavily 搜索配置。"""
    api_keys: list = field(default_factory=list)
    enabled: bool = True


@dataclass
class Config:
    """Application configuration loaded from config.yaml."""

    llm_api_base: str = "https://yunwu.ai"
    llm_api_key: str = ""           # 向后兼容（单 key）
    llm_api_keys: list = field(default_factory=list)   # 多 key 轮询列表
    llm_model: str = "gpt-5.6-luna"
    image_api_base: str = "https://yunwu.ai"
    image_model: str = "gpt-image-2"
    cache_dir: str = "cache"

    # 数据库（PostgreSQL，异步驱动）
    database_url: str = ""
    # JWT 认证
    jwt_secret: str = ""
    jwt_algorithm: str = "HS256"
    jwt_access_ttl: int = 3600      # access token 有效期（秒），1小时
    jwt_refresh_ttl: int = 604800   # refresh token 有效期（秒），7天
    # WebDAV 存储
    webdav_url: str = ""
    webdav_username: str = ""
    webdav_password: str = ""
    # 管理员（首个注册用户自动成为 admin）
    admin_username: str = "admin"
    # 计费汇率（100美分=多少人民币），即 yunwu 每消耗 1 美元扣多少元
    billing_rate: float = 2.0

    # 余额熔断配置（防止余额为正但极小时无限使用）
    min_balance: float = 1.0          # 最低余额门槛（元），低于此值拒绝消耗型 API
    precharge_chat: float = 0.5       # 聊天预扣费（元/次）
    precharge_image: float = 1.0      # 图像编辑预扣费（元/次）
    precharge_vlm: float = 0.3        # VLM 检测预扣费（元/次）

    # rembg 背景去除
    rembg_model: str = "birefnet-portrait"  # rembg 模型：birefnet-portrait(人像高精度)/birefnet-general/u2net/u2netp
    models_dir: str = "models/rembg"  # 本地模型目录（打包集成时使用）
    # FFmpeg 画质增强（自动识别 GPU，核显/独显自动调整策略）
    ffmpeg_enhance_enabled: bool = True  # 是否启用 ffmpeg 分辨率提升
    ffmpeg_path: str = "ffmpeg"  # ffmpeg 可执行文件路径
    # DiffBIR 画质增强
    enhance_enabled: bool = False  # DiffBIR 画质增强服务是否启用
    enhance_url: str = "http://127.0.0.1:8766"  # DiffBIR 服务地址

    # 阶段5：纯 API 模式开关（False 时不托管前端静态文件，仅提供 REST/WebSocket/图片代理）
    serve_frontend: bool = False
    # CORS 允许来源列表（默认 ["*"]；含 * 时 credentials 自动关闭）
    cors_origins: list = field(default_factory=lambda: ["*"])

    agents: AgentsConfig = field(default_factory=AgentsConfig)
    tavily: TavilyConfig = field(default_factory=TavilyConfig)

    @classmethod
    def load(cls, path: str | Path = "config.yaml") -> "Config":
        """Load configuration from a YAML file."""
        config_path = Path(path)
        if not config_path.exists():
            cfg = cls()
            cfg.apply_env_overrides()
            return cfg

        try:
            with config_path.open("r", encoding="utf-8") as f:
                data = yaml.safe_load(f) or {}
        except (yaml.YAMLError, OSError):
            cfg = cls()
            cfg.apply_env_overrides()
            return cfg

        llm = data.get("llm", {}) or {}
        image = data.get("image", {}) or {}
        agents_data = data.get("agents", {}) or {}
        tavily_data = data.get("tavily", {}) or {}

        # Parse sub-agents
        sub_agents = {}
        for key, val in (agents_data.get("sub_agents", {}) or {}).items():
            if isinstance(val, dict):
                sub_agents[key] = SubAgentConfig(model=val.get("model", ""))
            elif isinstance(val, str):
                sub_agents[key] = SubAgentConfig(model=val)

        # Parse model tiers
        models_data = agents_data.get("models", {}) or {}
        model_tiers = ModelTiersConfig(
            light=models_data.get("light", ModelTiersConfig.light),
            standard=models_data.get("standard", ModelTiersConfig.standard),
            heavy=models_data.get("heavy", ModelTiersConfig.heavy),
        )

        agents_cfg = AgentsConfig(
            enabled=agents_data.get("enabled", True),
            models=model_tiers,
            sub_agents=sub_agents,
        )

        # CORS 允许来源（yaml 中为列表；非法/空时回退 ["*"]）
        cors_origins = data.get("cors_origins")
        if not isinstance(cors_origins, list) or not cors_origins:
            cors_origins = ["*"]

        # 支持多 key 列表（api_keys），兼容旧的单 key（api_key）
        llm_keys = llm.get("api_keys", [])
        if not llm_keys and llm.get("api_key"):
            llm_keys = [llm.get("api_key")]

        cfg = cls(
            llm_api_base=llm.get("api_base", cls.llm_api_base),
            llm_api_key=llm.get("api_key", ""),
            llm_api_keys=llm_keys,
            llm_model=llm.get("model", cls.llm_model),
            image_api_base=image.get("api_base", cls.image_api_base),
            image_model=image.get("model", cls.image_model),
            cache_dir=data.get("cache_dir", cls.cache_dir),
            database_url=data.get("database_url", cls.database_url),
            jwt_secret=data.get("jwt_secret", cls.jwt_secret),
            jwt_algorithm=data.get("jwt_algorithm", cls.jwt_algorithm),
            jwt_access_ttl=data.get("jwt_access_ttl", cls.jwt_access_ttl),
            jwt_refresh_ttl=data.get("jwt_refresh_ttl", cls.jwt_refresh_ttl),
            webdav_url=data.get("webdav_url", cls.webdav_url),
            webdav_username=data.get("webdav_username", cls.webdav_username),
            webdav_password=data.get("webdav_password", cls.webdav_password),
            admin_username=data.get("admin_username", cls.admin_username),
            billing_rate=data.get("billing_rate", cls.billing_rate),
            min_balance=data.get("min_balance", cls.min_balance),
            precharge_chat=data.get("precharge_chat", cls.precharge_chat),
            precharge_image=data.get("precharge_image", cls.precharge_image),
            precharge_vlm=data.get("precharge_vlm", cls.precharge_vlm),
            serve_frontend=bool(data.get("serve_frontend", False)),
            models_dir=data.get("models_dir", cls.models_dir),
            ffmpeg_enhance_enabled=bool(data.get("ffmpeg_enhance_enabled", True)),
            ffmpeg_path=data.get("ffmpeg_path", cls.ffmpeg_path),
            cors_origins=cors_origins,
            agents=agents_cfg,
            tavily=TavilyConfig(
                api_keys=tavily_data.get("api_keys", []),
                enabled=tavily_data.get("enabled", True),
            ),
        )
        cfg.apply_env_overrides()
        return cfg

    def apply_env_overrides(self) -> None:
        """用环境变量覆盖 config.yaml 中的配置（环境变量优先级更高）。

        支持的环境变量：
            LLM_API_KEY / LLM_API_BASE / LLM_MODEL
            IMAGE_API_BASE / IMAGE_MODEL
            TAVILY_API_KEY  —— 支持逗号分隔的多个 key
            CACHE_DIR       —— 覆盖 cache_dir
            DATABASE_URL / JWT_SECRET / ADMIN_USERNAME
            WEBDAV_URL / WEBDAV_USERNAME / WEBDAV_PASSWORD
            BILLING_RATE    —— 计费汇率（float 解析）
            SERVE_FRONTEND  —— 纯 API 模式开关（"false"/"0"/"no" → False）
            CORS_ORIGINS    —— CORS 允许来源（逗号分隔）
        """
        env_map = {
            "LLM_API_BASE": "llm_api_base",
            "LLM_MODEL": "llm_model",
            "IMAGE_API_BASE": "image_api_base",
            "IMAGE_MODEL": "image_model",
            "CACHE_DIR": "cache_dir",
            "DATABASE_URL": "database_url",
            "JWT_SECRET": "jwt_secret",
            "WEBDAV_URL": "webdav_url",
            "WEBDAV_USERNAME": "webdav_username",
            "WEBDAV_PASSWORD": "webdav_password",
            "ADMIN_USERNAME": "admin_username",
        }
        for env_name, attr in env_map.items():
            val = os.environ.get(env_name)
            if val:
                setattr(self, attr, val)

        # yunwu LLM：支持编号变量 LLM_API_KEY_1, LLM_API_KEY_2 ...
        # yunwu 集成 key：LLM 与图像共用同一组 key，无需 IMAGE_API_KEY
        numbered_keys = []
        for i in range(1, 101):
            v = os.environ.get(f"LLM_API_KEY_{i}")
            if v and v.strip():
                numbered_keys.append(v.strip())
        if numbered_keys:
            self.llm_api_keys = numbered_keys
            self.llm_api_key = numbered_keys[0]
        else:
            llm_env = os.environ.get("LLM_API_KEY")
            if llm_env:
                keys = [k.strip() for k in llm_env.split(",") if k.strip()]
                if keys:
                    self.llm_api_keys = keys
                    self.llm_api_key = keys[0]

        # Tavily：支持编号变量 TAVILY_API_KEY_1, TAVILY_API_KEY_2 ...
        numbered_tavily = []
        for i in range(1, 101):
            v = os.environ.get(f"TAVILY_API_KEY_{i}")
            if v and v.strip():
                numbered_tavily.append(v.strip())
        if numbered_tavily:
            self.tavily.api_keys = numbered_tavily
        else:
            tavily_env = os.environ.get("TAVILY_API_KEY")
            if tavily_env:
                keys = [k.strip() for k in tavily_env.split(",") if k.strip()]
                if keys:
                    self.tavily.api_keys = keys

        # 计费汇率：float 解析
        billing_rate_env = os.environ.get("BILLING_RATE")
        if billing_rate_env:
            try:
                self.billing_rate = float(billing_rate_env)
            except ValueError:
                pass

        # 纯 API 模式开关："false"/"0"/"no" → False，其他非空值 → True
        serve_frontend_env = os.environ.get("SERVE_FRONTEND")
        if serve_frontend_env is not None and serve_frontend_env.strip() != "":
            self.serve_frontend = serve_frontend_env.strip().lower() not in ("false", "0", "no")

        # CORS 允许来源：逗号分隔
        cors_origins_env = os.environ.get("CORS_ORIGINS")
        if cors_origins_env:
            origins = [o.strip() for o in cors_origins_env.split(",") if o.strip()]
            if origins:
                self.cors_origins = origins
