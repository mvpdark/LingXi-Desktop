"""数据库结构启动引导模块（容器首次启动建表）。

用途：
    Docker 容器启动时（见 docker/entrypoint.sh），在启动 FastAPI 服务之前
    执行本模块，确保 PostgreSQL 中全部业务表已就绪：

    1. 通过 SQLAlchemy ``Base.metadata.create_all`` 幂等创建全部 ORM 表
       （users / sessions / billing_baselines / chat_sessions /
        chat_messages / memory_profiles / memory_summaries /
        usage_stats / api_keys）。
       create_all 自带 IF NOT EXISTS 语义，重复执行安全。
    2. 执行 usage_stats 的幂等增量迁移 SQL，为【旧版已建表】的数据库
       补齐 period 列与 (user_id, api_type, period) 唯一索引
       （新库由 create_all 一并建好，下方 SQL 因 IF NOT EXISTS 自动跳过）。
    3. 打印当前数据库中的表清单，便于容器日志排查。

    后续更复杂的结构变更（改列类型、删列、数据搬迁等）请使用 Alembic
    （alembic.ini + alembic/env.py，连接串同样读 DATABASE_URL 环境变量），
    本模块只负责"首次启动能把服务拉起来"的最低保障。

执行方式：
    python -m src.db.ensure_schema

环境变量：
    DATABASE_URL —— 必填，格式 postgresql+asyncpg://user:pass@host:port/db
                    未设置时打印错误并以退出码 1 结束（entrypoint set -e
                    会阻断容器启动，形成可见的失败而非带病运行）。

退出码：
    0 —— 结构就绪；1 —— DATABASE_URL 未设置或建表/迁移执行失败。

包布局兼容：
    支持 ``python -m src.db.ensure_schema``（项目根为 sys.path，Docker 默认）
    与 ``python -m db.ensure_schema``（src/ 目录本身在 sys.path 上）两种
    导入布局，二者均能正确加载 ORM 模型。
"""

from __future__ import annotations

import asyncio
import os
import sys

# usage_stats 幂等增量迁移（兼容旧库；新库由 create_all 建全，此处自动跳过）
USAGE_STATS_MIGRATION_SQL: tuple[str, ...] = (
    "ALTER TABLE usage_stats ADD COLUMN IF NOT EXISTS period VARCHAR(7);",
    "CREATE UNIQUE INDEX IF NOT EXISTS uq_usage_stats_user_api_type_period "
    "ON usage_stats (user_id, api_type, period);",
)

# billing_baselines 幂等列改名迁移（baseline_usd → baseline_cents）
# 计费基数由「美元」改为「美分」后，旧库需把列名同步过来；
# 新库由 create_all 直接建出 baseline_cents，此 DO 块因列不存在自动跳过。
# PostgreSQL 的 ALTER TABLE ... RENAME COLUMN 不支持 IF EXISTS，
# 故用 information_schema 检查 + DO 块包裹实现幂等。
BILLING_BASELINE_MIGRATION_SQL: tuple[str, ...] = (
    "DO $$ BEGIN "
    "  IF EXISTS (SELECT 1 FROM information_schema.columns "
    "             WHERE table_name='billing_baselines' "
    "               AND column_name='baseline_usd') THEN "
    "    ALTER TABLE billing_baselines RENAME COLUMN baseline_usd TO baseline_cents; "
    "  END IF; "
    "END $$;",
)

# chat_messages / chat_sessions 复合索引幂等迁移（兼容旧库；新库由 create_all 建全）
COMPOSITE_INDEX_MIGRATION_SQL: tuple[str, ...] = (
    "CREATE INDEX IF NOT EXISTS ix_chat_messages_session_timestamp "
    "ON chat_messages (session_id, timestamp);",
    "CREATE INDEX IF NOT EXISTS ix_chat_sessions_user_pinned_updated "
    "ON chat_sessions (user_id, pinned, updated_at);",
)


def _import_base():
    """按当前 sys.path 布局导入 ORM 声明式基类（兼容 src.db / db 两种布局）。"""
    try:
        from src.db.models import Base  # 项目根在 sys.path 上（Docker 默认布局）
    except ImportError:
        from db.models import Base  # src/ 目录直接在 sys.path 上
    return Base


async def _ensure_schema(database_url: str) -> list[str]:
    """建表 + 执行幂等迁移，返回数据库中的表名清单。"""
    from sqlalchemy import inspect, text
    from sqlalchemy.ext.asyncio import create_async_engine

    Base = _import_base()

    engine = create_async_engine(database_url, pool_pre_ping=True)
    try:
        async with engine.begin() as conn:
            # 1) 幂等创建全部 ORM 表
            await conn.run_sync(Base.metadata.create_all)
            # 2) 旧库增量迁移（幂等）
            for stmt in USAGE_STATS_MIGRATION_SQL:
                await conn.execute(text(stmt))
            for stmt in BILLING_BASELINE_MIGRATION_SQL:
                await conn.execute(text(stmt))
            for stmt in COMPOSITE_INDEX_MIGRATION_SQL:
                await conn.execute(text(stmt))
        # 3) 读取并返回表清单
        async with engine.connect() as conn:
            tables = await conn.run_sync(
                lambda sync_conn: sorted(inspect(sync_conn).get_table_names())
            )
        return tables
    finally:
        await engine.dispose()


def main() -> int:
    database_url = os.environ.get("DATABASE_URL", "").strip()
    if not database_url:
        print(
            "[ensure_schema] 错误：环境变量 DATABASE_URL 未设置，"
            "请配置 postgresql+asyncpg://user:pass@host:port/db 格式的数据库连接串",
            file=sys.stderr,
        )
        return 1

    print("[ensure_schema] 开始确保数据库结构 ...")
    try:
        tables = asyncio.run(_ensure_schema(database_url))
    except Exception as ex:  # noqa: BLE001 —— 启动引导需给出清晰失败信息
        print(f"[ensure_schema] 失败：{type(ex).__name__}: {ex}", file=sys.stderr)
        return 1

    print(f"[ensure_schema] 完成，当前共 {len(tables)} 张表：")
    for name in tables:
        print(f"[ensure_schema]   - {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
