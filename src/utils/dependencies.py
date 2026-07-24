"""FastAPI 权限依赖注入模块。

为路由层提供开箱即用的依赖项（dependencies），用于：

- ``get_current_user``：获取当前登录用户（强制登录），
  未登录 / 用户不存在返回 401，账号被封禁返回 403。
- ``require_admin``：要求当前用户具备管理员角色，否则返回 403。

前置约定：认证中间件负责解析请求中的令牌，并将用户 ID 写入
``request.state.user_id``；未通过认证的请求不写入该属性。

用法示例::

    from fastapi import Depends
    from src.db.models import User
    from src.utils.dependencies import get_current_user, require_admin

    @app.get("/me")
    async def read_me(user: User = Depends(get_current_user)):
        return {"username": user.username}

    @app.get("/admin/stats")
    async def admin_stats(user: User = Depends(require_admin)):
        ...
"""

from __future__ import annotations

import uuid
from typing import Optional

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from src.db.database import get_db
from src.db.models import User

__all__ = [
    "get_current_user",
    "require_admin",
]


def _extract_user_id(request: Request) -> Optional[uuid.UUID]:
    """从 request.state 中提取并规范化用户 ID。

    认证中间件将用户 ID 写入 ``request.state.user_id``，可能是
    ``str`` 或 ``uuid.UUID`` 类型。本函数统一转换为 ``uuid.UUID``，
    取不到或格式非法时返回 ``None``（由调用方决定抛错或放行）。

    参数:
        request: 当前 HTTP 请求对象。

    返回:
        Optional[uuid.UUID]: 合法的用户 ID，或 ``None``。
    """
    raw_user_id = getattr(request.state, "user_id", None)
    if raw_user_id is None:
        return None
    if isinstance(raw_user_id, uuid.UUID):
        return raw_user_id
    try:
        return uuid.UUID(str(raw_user_id))
    except (ValueError, AttributeError, TypeError):
        return None


async def get_current_user(
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> User:
    """获取当前登录用户（强制登录）。

    处理流程：
        1. 从 ``request.state.user_id`` 读取用户 ID（由认证中间件写入），
           缺失或格式非法则抛 401；
        2. 查询数据库获取完整的 ``User`` 对象，不存在则抛 401；
        3. 校验账号状态，``status != "active"``（封禁/待激活）则抛 403。

    参数:
        request: 当前 HTTP 请求对象。
        db: 数据库会话（由 ``get_db`` 依赖注入）。

    返回:
        User: 当前登录用户的 ORM 对象。

    异常:
        HTTPException(401): 未登录、凭证非法或用户不存在。
        HTTPException(403): 用户存在但账号状态非 active（如被封禁）。
    """
    user_id = _extract_user_id(request)
    if user_id is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="未登录或登录凭证已失效",
            headers={"WWW-Authenticate": "Bearer"},
        )

    user = await db.get(User, user_id)
    if user is None:
        # 令牌有效但用户已被删除
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="用户不存在",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if user.status != "active":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="账号已被封禁或未激活",
        )

    return user


async def require_admin(
    current_user: User = Depends(get_current_user),
) -> User:
    """要求当前用户具备管理员角色。

    基于 ``get_current_user`` 的二次校验，适合直接用作
    管理后台接口的依赖项。

    参数:
        current_user: 当前登录用户（由 ``get_current_user`` 注入）。

    返回:
        User: 具备管理员角色的当前用户。

    异常:
        HTTPException(403): 当前用户不是管理员。
    """
    if current_user.role != "admin":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="需要管理员权限",
        )
    return current_user
