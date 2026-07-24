"""JWT 工具模块。

基于 PyJWT 为LX 后端提供 access token / refresh token 的
签发与解码能力。Token 载荷约定：

    sub      用户 ID（UUID 字符串）
    type     token 类型："access" / "refresh"
    iat      签发时间（UTC）
    exp      过期时间（UTC）
    jti      token 唯一标识（可用于黑名单）
    username 用户名（仅 access token）
    role     用户角色（仅 access token）

有效期：access token 1 小时，refresh token 7 天。
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Optional

import jwt

# Token 默认有效期
ACCESS_TOKEN_TTL = 3600             # access token：1 小时（秒）
REFRESH_TOKEN_TTL = 7 * 24 * 60 * 60  # refresh token：7 天（秒）

DEFAULT_ALGORITHM = "HS256"

# token 类型常量
TOKEN_TYPE_ACCESS = "access"
TOKEN_TYPE_REFRESH = "refresh"


def _create_token(
    subject: str,
    token_type: str,
    secret: str,
    algorithm: str,
    ttl_seconds: int,
    extra_claims: Optional[Dict[str, Any]] = None,
) -> str:
    """签发一个 JWT。

    参数:
        subject: 主体（用户 ID）
        token_type: token 类型（access / refresh）
        secret: 签名密钥
        algorithm: 签名算法
        ttl_seconds: 有效期（秒）
        extra_claims: 附加载荷字段

    返回:
        编码后的 JWT 字符串
    """
    now = datetime.now(tz=timezone.utc)
    payload: Dict[str, Any] = {
        "sub": str(subject),
        "type": token_type,
        "iat": now,
        "exp": now + timedelta(seconds=ttl_seconds),
        "jti": uuid.uuid4().hex,
    }
    if extra_claims:
        payload.update(extra_claims)
    return jwt.encode(payload, secret, algorithm=algorithm)


def create_access_token(
    user_id: str,
    username: str,
    role: str,
    secret: str,
    algorithm: str = DEFAULT_ALGORITHM,
    ttl_seconds: int = ACCESS_TOKEN_TTL,
) -> str:
    """签发 access token（携带 username / role 载荷）。"""
    return _create_token(
        str(user_id),
        TOKEN_TYPE_ACCESS,
        secret,
        algorithm,
        ttl_seconds,
        {"username": username, "role": role},
    )


def create_refresh_token(
    user_id: str,
    secret: str,
    algorithm: str = DEFAULT_ALGORITHM,
    ttl_seconds: int = REFRESH_TOKEN_TTL,
) -> str:
    """签发 refresh token（仅携带用户 ID，用于换新 access token）。"""
    return _create_token(
        str(user_id), TOKEN_TYPE_REFRESH, secret, algorithm, ttl_seconds
    )


def decode_token(
    token: str,
    secret: str,
    algorithm: str = DEFAULT_ALGORITHM,
    expected_type: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    """解码并校验 JWT（签名 + 过期时间 + 可选类型检查）。

    参数:
        token: JWT 字符串
        secret: 签名密钥
        algorithm: 签名算法
        expected_type: 期望的 token 类型（"access" / "refresh"），
                       传入后类型不匹配视为无效

    返回:
        解码后的载荷 dict；签名错误、过期或类型不匹配时返回 None
    """
    try:
        payload = jwt.decode(token, secret, algorithms=[algorithm])
    except jwt.exceptions.PyJWTError:
        # 签名无效、已过期、格式错误等统一按无效 token 处理
        return None
    if expected_type is not None and payload.get("type") != expected_type:
        return None
    return payload
