"""初始化管理员账户脚本。

仅从环境变量读取管理员凭据，避免在源码中保存密码或密码哈希。

用法（Windows PowerShell）：
    $env:LX_ADMIN_PASSWORD = "请设置强密码"
    python src/db/init_admin.py

可选环境变量：
    LX_ADMIN_USERNAME  管理员用户名，默认 admin
    LX_ADMIN_BALANCE   初始余额，默认 999999.00
"""

from __future__ import annotations

import asyncio
import os
import uuid
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DATA_DIR = PROJECT_ROOT / "data"
DB_PATH = DATA_DIR / "lx.db"

ADMIN_STATUS = "active"
ADMIN_ROLE = "admin"

CREATE_USERS_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS users (
    id              TEXT PRIMARY KEY,
    username        TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    salt            TEXT NOT NULL,
    balance         REAL NOT NULL DEFAULT 0.0,
    status          TEXT NOT NULL DEFAULT 'pending',
    role            TEXT NOT NULL DEFAULT 'user',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
"""

CREATE_INDEX_USERNAME_SQL = """
CREATE UNIQUE INDEX IF NOT EXISTS ix_users_username ON users(username);
"""

INSERT_ADMIN_SQL = """
INSERT INTO users (id, username, password_hash, salt, balance, status, role, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
"""

CHECK_ADMIN_EXISTS_SQL = """
SELECT COUNT(*) FROM users WHERE username = ?;
"""


def load_admin_config() -> tuple[str, str, Decimal]:
    """读取并校验管理员初始化参数。"""
    username = os.environ.get("LX_ADMIN_USERNAME", "admin").strip()
    password = os.environ.get("LX_ADMIN_PASSWORD", "")
    if not username:
        raise RuntimeError("LX_ADMIN_USERNAME 不能为空")
    if len(password) < 12:
        raise RuntimeError("请通过 LX_ADMIN_PASSWORD 设置至少 12 位的管理员密码")

    try:
        balance = Decimal(os.environ.get("LX_ADMIN_BALANCE", "999999.00"))
    except InvalidOperation as exc:
        raise RuntimeError("LX_ADMIN_BALANCE 必须是有效数字") from exc
    return username, password, balance


async def init_admin() -> None:
    """创建管理员账户；已存在时保持不变。"""
    import aiosqlite
    from passlib.hash import bcrypt as passlib_bcrypt

    username, password, balance = load_admin_config()
    password_hash = passlib_bcrypt.hash(password)

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    print(f"[init_admin] 数据库路径: {DB_PATH.resolve()}")

    async with aiosqlite.connect(str(DB_PATH)) as db:
        await db.execute(CREATE_USERS_TABLE_SQL)
        await db.execute(CREATE_INDEX_USERNAME_SQL)
        await db.commit()

        cursor = await db.execute(CHECK_ADMIN_EXISTS_SQL, (username,))
        row = await cursor.fetchone()
        if row and row[0] > 0:
            print(f"[init_admin] 用户 '{username}' 已存在，跳过插入")
            return

        now_iso = datetime.now(timezone.utc).isoformat()
        admin_id = uuid.uuid4().hex
        await db.execute(
            INSERT_ADMIN_SQL,
            (
                admin_id,
                username,
                password_hash,
                "",
                float(balance),
                ADMIN_STATUS,
                ADMIN_ROLE,
                now_iso,
                now_iso,
            ),
        )
        await db.commit()
        print(f"[init_admin] 管理员用户创建成功: username='{username}', id={admin_id}")


if __name__ == "__main__":
    asyncio.run(init_admin())
