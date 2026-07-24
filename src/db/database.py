"""数据库连接模块。

基于 SQLAlchemy 2.0 异步语法，为 LX 后端提供
数据库连接、会话管理与连接池生命周期管理。

支持的数据库：
    - PostgreSQL（asyncpg 驱动）—— 生产环境
    - SQLite（aiosqlite 驱动）    —— 开发/单机部署回退方案

连接串读取优先级：
    1. 环境变量 DATABASE_URL
    2. 回退到 SQLite（sqlite+aiosqlite:///./data/lx.db）

用法（FastAPI 依赖注入）：
    from fastapi import Depends
    from sqlalchemy.ext.asyncio import AsyncSession

    from src.db.database import get_db

    @app.get("/users")
    async def list_users(db: AsyncSession = Depends(get_db)):
        ...
"""

from __future__ import annotations

import logging
import os
from typing import AsyncGenerator

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 数据库连接串
# ---------------------------------------------------------------------------
# 优先从环境变量读取；未设置时回退到本地 SQLite（开发/单机场景）
DEFAULT_SQLITE_URL: str = "sqlite+aiosqlite:///./data/lx.db"
DATABASE_URL: str = os.environ.get("DATABASE_URL", "") or DEFAULT_SQLITE_URL

_is_postgresql: bool = DATABASE_URL.startswith("postgresql")
_is_sqlite: bool = DATABASE_URL.startswith("sqlite")

if _is_postgresql:
    logger.info("数据库类型: PostgreSQL (asyncpg)")
elif _is_sqlite:
    logger.info("数据库类型: SQLite (aiosqlite) —— 开发回退模式")
else:
    logger.warning("未识别的数据库类型，按 PostgreSQL 参数创建引擎")

# ---------------------------------------------------------------------------
# 异步引擎 & 会话工厂
# ---------------------------------------------------------------------------
if _is_sqlite:
    engine: AsyncEngine = create_async_engine(
        DATABASE_URL,
        echo=False,
        connect_args={"check_same_thread": False},
    )
else:
    # PostgreSQL / 其他 —— 生产级连接池
    engine: AsyncEngine = create_async_engine(
        DATABASE_URL,
        echo=False,
        pool_size=10,        # 连接池常驻连接数
        max_overflow=20,     # 高峰期允许临时扩展的额外连接数
        pool_pre_ping=True,  # 取用连接前先探测活性，避免拿到失效连接
        pool_recycle=3600,   # 连接最长存活 1 小时，防止被服务端空闲断开
    )

# 全局异步会话工厂
async_session_factory: async_sessionmaker[AsyncSession] = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,  # 提交后对象不过期，避免访问属性时触发隐式 IO
)


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """FastAPI 依赖注入：提供一次请求范围内的数据库会话。

    正常结束时自动 commit；发生异常时自动 rollback 并继续抛出；
    无论成功与否，最终都会 close 会话并把连接归还连接池。

    产出:
        AsyncSession: 异步数据库会话。
    """
    async with async_session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()


async def init_db(engine: AsyncEngine) -> None:
    """首次启动时创建所有数据表（可选便捷方式，生产环境以 Alembic 迁移为准）。

    参数:
        engine: 异步数据库引擎。
    """
    # 延迟导入，避免与 models 模块产生循环依赖
    from src.db.models import Base

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("数据库表结构初始化完成")


async def close_db(engine: AsyncEngine) -> None:
    """应用关闭时清理连接池，释放全部数据库连接。

    参数:
        engine: 异步数据库引擎。
    """
    await engine.dispose()
    logger.info("数据库连接池已关闭")
