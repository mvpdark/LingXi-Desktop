# syntax=docker/dockerfile:1
# ===========================================================================
# 灵犀后端镜像（多租户 SaaS 纯 API：FastAPI + PostgreSQL + WebDAV + JWT）
#
# 构建上下文为【项目根目录】：
#   docker build -t lingxi-backend .
#   或：docker compose up -d --build
#
# 要点：
#   - 纯 API 模式：SERVE_FRONTEND=false 时不托管静态文件
#   - config.yaml 不打入镜像（含真实密钥），全部配置经环境变量注入
#   - 启动流程（docker/entrypoint.sh）：先 ensure_schema 建表，再拉起 uvicorn
#   - rembg 模型（u2net，约 176MB）首次调用时自动下载到 U2NET_HOME 缓存目录
# ===========================================================================
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# 运行期系统库（最简集）：
#   libglib2.0-0 - rembg / Pillow 运行依赖
#   curl         - HEALTHCHECK 探测 /api/health
# 说明：pillow 官方 manylinux wheel 已自带 libjpeg-turbo/zlib，
#       asyncpg 自带预编译 wheel 且不依赖 libpq（自实现协议），均无需额外系统库。
RUN apt-get update \
    && apt-get install -y --no-install-recommends libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行
RUN useradd --create-home --shell /bin/sh lingxi

WORKDIR /app

# 1) 先安装依赖：单独成层，requirements.txt 不变时可命中构建缓存
COPY backend/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# 2) 复制应用代码（根 .dockerignore 已排除敏感配置；
#    config.yaml 刻意不复制——密钥不入镜像，运行时全部走环境变量注入）
# 建表/迁移职责划分：docker/entrypoint.sh 中的 ensure_schema 负责首次启动幂等建表，
# alembic 仅供后续高级迁移（手工执行 alembic upgrade head），不参与容器启动流程。
COPY src/ /app/src/
COPY alembic/ /app/alembic/
COPY alembic.ini /app/alembic.ini

# 3) 将 /app/src 暴露到 import 路径，使 server.py 的 `from utils.xxx import` 能正确解析
ENV PYTHONPATH=/app/src

# 4) rembg 模型缓存目录（u2net 权重首次调用时自动下载至此）
ENV U2NET_HOME=/app/cache/models

# 5) 运行期目录（非 root 运行需预先授权）
RUN mkdir -p /app/assets /app/cache/models \
    && chown -R lingxi:lingxi /app

# 6) 启动脚本：ensure_schema 建表后再启动 uvicorn；sed 兼容 Windows CRLF 行尾，
#    chmod 兜底 Windows/git 丢失执行位的情况
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh

USER lingxi

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8765/api/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
