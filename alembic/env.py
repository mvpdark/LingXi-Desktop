"""Alembic 迁移环境配置（异步版本）。

使用 async_engine_from_config 创建异步引擎执行迁移；
数据库连接串从环境变量 DATABASE_URL 读取，不在 alembic.ini 中写死。
同时支持 online（直连数据库执行）与 offline（仅生成 SQL 脚本）两种模式。
"""

from __future__ import annotations

import asyncio
import os
import sys
from logging.config import fileConfig
from pathlib import Path

from alembic import context
from sqlalchemy import pool
from sqlalchemy.ext.asyncio import async_engine_from_config

# 将项目根目录加入 sys.path，确保能导入 src.db.models
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# 导入模型基类，确保所有表结构已注册到 metadata
from src.db.models import Base  # noqa: E402

# Alembic Config 对象（来自 alembic.ini）
config = context.config

# 从环境变量读取数据库连接串并注入 Alembic 配置
database_url = os.environ.get("DATABASE_URL", "")
if not database_url:
    raise RuntimeError(
        "环境变量 DATABASE_URL 未设置，"
        "请配置 postgresql+asyncpg://user:pass@host:port/db 格式的数据库连接串"
    )
config.set_main_option("sqlalchemy.url", database_url)

# 按 alembic.ini 中的配置初始化日志
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# 自动生成迁移脚本时比对的目标 metadata
target_metadata = Base.metadata


def run_migrations_offline() -> None:
    """离线模式：不连接数据库，仅根据连接串方言生成 SQL 脚本。"""
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection) -> None:
    """在同步上下文中配置并执行迁移（由异步连接桥接调用）。

    参数:
        connection: 异步连接 run_sync 包装出的同步连接。
    """
    context.configure(connection=connection, target_metadata=target_metadata)

    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    """创建异步引擎，连接数据库并执行在线迁移。"""
    connectable = async_engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,  # 迁移场景无需连接池
    )

    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)

    await connectable.dispose()


def run_migrations_online() -> None:
    """在线模式：通过 asyncio.run 驱动异步迁移流程。"""
    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
