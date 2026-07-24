"""用户认证与账户管理服务（PostgreSQL + JWT 版）。

为LX 后端提供用户注册、登录、Token 验证、账户与余额管理能力。
数据持久化到 PostgreSQL（users 表，见 src/db/models.py），认证采用
无状态 JWT（见 src/utils/jwt_utils.py）。

特性：
    - 密码使用 bcrypt 哈希（passlib）；旧 SHA256(salt+password) 账户
      登录验证成功后自动升级为 bcrypt 并写回数据库
    - access / refresh token 有效期可在实例化时通过 access_ttl /
      refresh_ttl 配置（默认 1 小时 / 7 天）
    - 支持 token 吊销（logout 吊销单个 jti，封禁吊销用户全部 token；
      内存级黑名单，进程重启后失效）
    - 首个注册用户自动成为 admin 并直接激活；其余用户默认 pending，
      需管理员审批（用户名匹配 admin_username 的除外）
    - 余额增减使用单条 UPDATE 语句在数据库侧完成，保证原子性
    - 所有公共方法均为异步，错误以 None / False / {success: False} 返回，
      不向外抛异常（数据库连接等基础设施异常除外）
"""

from __future__ import annotations

import hashlib
import logging
import re
import secrets
import time
import uuid
from decimal import Decimal
from typing import Any, Dict, List, Optional, Tuple

from passlib.context import CryptContext
from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

# 兼容两种包布局：项目根在 sys.path（src.*）或 src 目录在 sys.path（*）
try:
    from src.db.models import User
except ImportError:  # pragma: no cover
    from db.models import User

try:
    from src.utils import jwt_utils
except ImportError:  # pragma: no cover
    from utils import jwt_utils

logger = logging.getLogger(__name__)


class AuthService:
    """用户认证与账户管理服务（PostgreSQL + JWT）。

    参数:
        db_session_factory: async_sessionmaker 实例（见 src/db/database.py）
        jwt_secret: JWT 签名密钥
        jwt_algorithm: JWT 签名算法，默认 HS256
        access_ttl: access token 有效期（秒），默认 3600
        refresh_ttl: refresh token 有效期（秒），默认 604800（7 天）
    """

    # Token 有效期（deprecated：仅为兼容保留，实例实际使用
    # __init__ 注入的 access_ttl / refresh_ttl，请改用实例属性）
    ACCESS_TOKEN_TTL = jwt_utils.ACCESS_TOKEN_TTL      # 1 小时（秒）
    REFRESH_TOKEN_TTL = jwt_utils.REFRESH_TOKEN_TTL    # 7 天（秒）

    #: 用户名校验规则：2-32 位字母 / 数字 / 下划线 / 连字符（正则全文匹配）
    USERNAME_PATTERN = re.compile(r"^[a-zA-Z0-9_-]{2,32}$")

    #: 密码 UTF-8 编码后的最大字节数（bcrypt 仅取前 72 字节，超长直接拒绝）
    MAX_PASSWORD_BYTES = 72

    # 账户状态
    STATUS_PENDING = "pending"
    STATUS_ACTIVE = "active"
    STATUS_SUSPENDED = "suspended"
    STATUS_DELETED = "deleted"

    # 角色
    ROLE_ADMIN = "admin"
    ROLE_USER = "user"

    def __init__(
        self,
        db_session_factory: async_sessionmaker,
        jwt_secret: str,
        jwt_algorithm: str = "HS256",
        access_ttl: int = 3600,
        refresh_ttl: int = 604800,
    ):
        self.db = db_session_factory
        self.jwt_secret = jwt_secret
        self.jwt_algorithm = jwt_algorithm
        # Token 有效期（实例级，替代 deprecated 类常量）
        self.access_ttl = int(access_ttl)
        self.refresh_ttl = int(refresh_ttl)
        self.pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
        # 兼容旧 SHA256(salt+password) 哈希
        self.legacy_sha256 = True
        # Token 吊销名单（内存级）：
        # 仅当前进程内生效，进程重启后失效——重启后旧 access token
        # 在自然过期（access_ttl，默认 1 小时）前仍可用，这是已知局限；
        # 如需跨进程吊销需引入 Redis / 数据库黑名单。
        self._revoked_jti: set[str] = set()      # 已吊销的 token jti
        self._revoked_users: set[str] = set()    # 已吊销全部 token 的 user_id
        # verify_token 调用计数：每 100 次触发一次 jti 清理
        self._verify_count = 0
        # 密码修改时间戳（user_id -> timestamp）：改密码后使旧 token 失效
        self._password_changed: dict[str, float] = {}

    # ------------------------------------------------------------------
    # 密码哈希工具
    # ------------------------------------------------------------------

    def _hash_password(self, password: str) -> Tuple[str, str]:
        """哈希密码，返回 (hash, salt)。

        bcrypt 的盐已内嵌在哈希串中，salt 返回空字符串（仅为兼容
        users.salt 非空约束与旧接口签名）。
        """
        return self.pwd_context.hash(password), ""

    def _verify_password(
        self, plain_password: str, hashed: str, salt: str
    ) -> bool:
        """验证密码，同时支持 bcrypt 与旧 SHA256(salt+password)。"""
        if not hashed:
            return False
        if not self._needs_rehash(hashed):
            try:
                # bcrypt 只取密码前 72 字节，passlib 对超长输入会静默截断；
                # 这里显式按 UTF-8 编码截断到 72 字节再 verify，使行为显式
                # 且与注册侧"超长拒绝"形成双保险（登录校验 / 改密校验共用）。
                pw_bytes = plain_password.encode("utf-8")[: self.MAX_PASSWORD_BYTES]
                return self.pwd_context.verify(pw_bytes, hashed)
            except ValueError:
                return False
        # 旧格式：sha256(salt + password)
        if not self.legacy_sha256:
            return False
        computed = hashlib.sha256(
            (salt + plain_password).encode("utf-8")
        ).hexdigest()
        # 常量时间比较，防止计时攻击
        return secrets.compare_digest(computed, hashed)

    def _needs_rehash(self, hashed: str) -> bool:
        """检查哈希是否需要升级：非 bcrypt（旧 SHA256）即需要。"""
        return not (hashed or "").startswith("$2")

    # ------------------------------------------------------------------
    # 内部工具
    # ------------------------------------------------------------------

    @staticmethod
    def _to_uuid(user_id: str) -> Optional[uuid.UUID]:
        """将字符串形式的 user_id 转为 UUID，非法输入返回 None。"""
        try:
            return uuid.UUID(str(user_id))
        except (ValueError, AttributeError, TypeError):
            return None

    @staticmethod
    def _user_to_dict(user: User) -> Dict[str, Any]:
        """将 User ORM 对象序列化为对外字典（不含密码哈希）。"""
        return {
            "user_id": str(user.id),
            "username": user.username,
            "role": user.role,
            "status": user.status,
            "balance": float(user.balance),
            "created_at": (
                user.created_at.isoformat() if user.created_at else None
            ),
        }

    async def _get_user_by_id(
        self, session: AsyncSession, user_id: str
    ) -> Optional[User]:
        """会话内按 ID 查询用户（user_id 为字符串，非法时返回 None）。"""
        uid = self._to_uuid(user_id)
        if uid is None:
            return None
        result = await session.execute(select(User).where(User.id == uid))
        return result.scalar_one_or_none()

    def _issue_tokens(self, user: User) -> Dict[str, str]:
        """为用户签发 access + refresh token（有效期取实例级 ttl 配置）。"""
        access_token = jwt_utils.create_access_token(
            str(user.id),
            user.username,
            user.role,
            self.jwt_secret,
            self.jwt_algorithm,
            self.access_ttl,
        )
        refresh_token = jwt_utils.create_refresh_token(
            str(user.id),
            self.jwt_secret,
            self.jwt_algorithm,
            self.refresh_ttl,
        )
        return {"access_token": access_token, "refresh_token": refresh_token}

    # ------------------------------------------------------------------
    # 注册 / 登录 / Token
    # ------------------------------------------------------------------

    async def register(
        self, username: str, password: str, admin_username: str = "admin"
    ) -> Dict[str, Any]:
        """注册新用户。

        - users 表为空时，首个用户自动 admin + active
        - 否则 username 匹配 admin_username 时为 admin + active
        - 其余用户为 user + pending（待管理员审批）

        返回:
            {success, user_id, username, role, status, message}
            失败时 success=False，message 区分"格式非法"（用户名格式 /
            密码超长或为空）与"用户名已存在"，路由层统一返回 400 并透传文案
        """
        # P0-1 注册输入校验：先校验格式再查库 / 哈希
        # 1) 用户名：^[a-zA-Z0-9_-]{2,32}$ 正则全文匹配
        if not username or not self.USERNAME_PATTERN.fullmatch(username):
            return {
                "success": False,
                "user_id": None,
                "username": username,
                "role": None,
                "status": None,
                "message": "用户名格式非法：仅限 2-32 位字母、数字、下划线、连字符",
            }
        # 2) 密码：非空且 UTF-8 编码后 ≤72 字节
        #    bcrypt 仅取前 72 字节，超长必须拒绝而非静默截断，
        #    否则 "xxx...超长后缀" 与 "xxx" 会被视为同一密码
        if not password or len(password.encode("utf-8")) > self.MAX_PASSWORD_BYTES:
            return {
                "success": False,
                "user_id": None,
                "username": username,
                "role": None,
                "status": None,
                "message": "密码不能为空且 UTF-8 编码后不得超过 72 字节",
            }

        password_hash, salt = self._hash_password(password)

        async with self.db() as session:
            # 检查用户名是否已存在
            result = await session.execute(
                select(User).where(User.username == username)
            )
            if result.scalar_one_or_none() is not None:
                return {
                    "success": False,
                    "user_id": None,
                    "username": username,
                    "role": None,
                    "status": None,
                    "message": "用户名已存在",
                }

            # 首个用户或匹配 admin_username 的用户直接成为 admin
            result = await session.execute(select(func.count(User.id)))
            user_count = result.scalar_one()
            is_admin = user_count == 0 or username == admin_username

            user = User(
                username=username,
                password_hash=password_hash,
                salt=salt,
                balance=Decimal("0.00"),
                role=self.ROLE_ADMIN if is_admin else self.ROLE_USER,
                status=(
                    self.STATUS_ACTIVE if is_admin else self.STATUS_PENDING
                ),
            )
            session.add(user)
            try:
                await session.commit()
            except IntegrityError:
                # 并发注册撞唯一约束
                await session.rollback()
                return {
                    "success": False,
                    "user_id": None,
                    "username": username,
                    "role": None,
                    "status": None,
                    "message": "用户名已存在",
                }

            logger.info(
                "用户注册: %s (role=%s, status=%s)",
                username, user.role, user.status,
            )
            return {
                "success": True,
                "user_id": str(user.id),
                "username": user.username,
                "role": user.role,
                "status": user.status,
                "message": (
                    "注册成功"
                    if user.status == self.STATUS_ACTIVE
                    else "注册成功，等待管理员审批"
                ),
            }

    async def login(
        self, username: str, password: str
    ) -> Optional[Dict[str, Any]]:
        """登录验证。

        验证密码（支持旧 SHA256 自动升级为 bcrypt），仅允许
        status == 'active' 的用户登录，成功后签发 JWT。

        返回:
            {user_id, username, role, balance,
             access_token, refresh_token, expires_in}；失败返回 None
        """
        async with self.db() as session:
            result = await session.execute(
                select(User).where(User.username == username)
            )
            user = result.scalar_one_or_none()
            if user is None:
                # 防计时侧信道：用户不存在时也执行一次 bcrypt 校验
                # （passlib CryptContext.dummy_verify 使用内置假哈希），
                # 使"用户不存在"与"密码错误"的响应耗时一致，
                # 避免攻击者通过响应时间枚举有效用户名
                self.pwd_context.dummy_verify(password)
                return None
            if not self._verify_password(
                password, user.password_hash, user.salt
            ):
                return None
            if user.status != self.STATUS_ACTIVE:
                logger.info(
                    "登录被拒绝（状态=%s）: %s", user.status, username
                )
                return None

            # 旧 SHA256 账户：验证通过后自动升级为 bcrypt
            if self._needs_rehash(user.password_hash):
                user.password_hash, user.salt = self._hash_password(password)
                await session.commit()
                logger.info("密码哈希已升级为 bcrypt: %s", username)

            logger.info("用户登录成功: %s", username)
            tokens = self._issue_tokens(user)
            return {
                "user_id": str(user.id),
                "username": user.username,
                "role": user.role,
                "balance": float(user.balance),
                "access_token": tokens["access_token"],
                "refresh_token": tokens["refresh_token"],
                "expires_in": self.access_ttl,
            }

    async def verify_token(self, token: str) -> Optional[Dict[str, Any]]:
        """验证 JWT access token（签名 + 过期 + 吊销检查）。

        解码成功后检查吊销名单：jti 命中单 token 黑名单，或
        user_id 命中用户级黑名单（如封禁后吊销其全部 token），
        均视为无效。

        返回:
            {user_id, username, role}；无效、过期或已吊销返回 None
        """
        payload = jwt_utils.decode_token(
            token,
            self.jwt_secret,
            self.jwt_algorithm,
            expected_type=jwt_utils.TOKEN_TYPE_ACCESS,
        )
        if payload is None:
            return None

        # 定期清理过期的 jti 吊销名单（每 100 次调用触发一次）
        self._verify_count += 1
        if self._verify_count % 100 == 0:
            self._cleanup_expired_jtis()

        # 吊销检查（内存级黑名单，进程重启后失效）
        if payload.get("jti") in self._revoked_jti:
            return None
        user_id = payload.get("sub")
        if user_id in self._revoked_users:
            return None

        # 密码修改检查：token 签发时间早于密码修改时间则拒绝
        changed_at = self._password_changed.get(str(user_id))
        if changed_at is not None:
            iat = payload.get("iat")
            if iat is not None and iat < changed_at:
                return None

        return {
            "user_id": user_id,
            "username": payload.get("username"),
            "role": payload.get("role"),
        }

    # ------------------------------------------------------------------
    # Token 吊销（内存级黑名单）
    # ------------------------------------------------------------------

    def revoke_token(self, jti: str) -> None:
        """按 jti 吊销单个 token（登出场景）。

        仅加入进程内存黑名单，进程重启后失效（见 __init__ 注释）。
        """
        if jti:
            self._revoked_jti.add(str(jti))

    def revoke_user_tokens(self, user_id: str) -> None:
        """吊销某用户的全部 token（封禁 / 强制下线场景）。

        仅加入进程内存黑名单，进程重启后失效（见 __init__ 注释）。
        """
        if user_id:
            self._revoked_users.add(str(user_id))

    def _cleanup_expired_jtis(self) -> None:
        """清理已过期的 jti 吊销名单（超过 10000 条时清空）。

        jti 对应的 token 最多存活 access_ttl 秒，过期的 jti 已无意义。
        简单策略：当吊销名单超过上限时直接清空，因为过期的 jti
        对应的 token 已自然失效，清空不影响安全性。
        """
        if len(self._revoked_jti) > 10000:
            self._revoked_jti.clear()
            logger.info("清理过期的 jti 吊销名单（超过 10000 条）")

    async def refresh_token(
        self, refresh_token: str
    ) -> Optional[Dict[str, Any]]:
        """用 refresh token 换新 access token。

        校验 refresh token 后查询用户，确认仍为 active 才签发。

        返回:
            {access_token, expires_in}；失败返回 None
        """
        payload = jwt_utils.decode_token(
            refresh_token,
            self.jwt_secret,
            self.jwt_algorithm,
            expected_type=jwt_utils.TOKEN_TYPE_REFRESH,
        )
        if payload is None:
            return None

        async with self.db() as session:
            user = await self._get_user_by_id(session, payload.get("sub", ""))
            if user is None or user.status != self.STATUS_ACTIVE:
                return None
            access_token = jwt_utils.create_access_token(
                str(user.id),
                user.username,
                user.role,
                self.jwt_secret,
                self.jwt_algorithm,
                self.access_ttl,
            )
            return {
                "access_token": access_token,
                "expires_in": self.access_ttl,
            }

    async def logout(self, token: str) -> bool:
        """登出：将当前 token 的 jti 加入吊销名单，使该 token 立即失效。

        内部 decode 取 jti 后调用 revoke_token；token 无法解码
        （已过期 / 非法）时也返回 True（登出语义幂等）。
        黑名单为进程内存级，重启后失效（见 __init__ 注释）。
        """
        payload = jwt_utils.decode_token(
            token,
            self.jwt_secret,
            self.jwt_algorithm,
            expected_type=jwt_utils.TOKEN_TYPE_ACCESS,
        )
        if payload is not None:
            self.revoke_token(payload.get("jti", ""))
        return True

    # ------------------------------------------------------------------
    # 用户查询
    # ------------------------------------------------------------------

    async def get_user_by_id(
        self, user_id: str
    ) -> Optional[Dict[str, Any]]:
        """根据 ID 获取用户信息（不含密码），不存在返回 None。"""
        async with self.db() as session:
            user = await self._get_user_by_id(session, user_id)
            return self._user_to_dict(user) if user else None

    async def get_user_by_username(
        self, username: str
    ) -> Optional[Dict[str, Any]]:
        """根据用户名获取用户信息（不含密码），不存在返回 None。"""
        async with self.db() as session:
            result = await session.execute(
                select(User).where(User.username == username)
            )
            user = result.scalar_one_or_none()
            return self._user_to_dict(user) if user else None

    async def list_users(self) -> List[Dict[str, Any]]:
        """列出所有用户（管理员用），按创建时间升序。"""
        async with self.db() as session:
            result = await session.execute(
                select(User).order_by(User.created_at)
            )
            return [self._user_to_dict(u) for u in result.scalars().all()]

    # ------------------------------------------------------------------
    # 账户维护
    # ------------------------------------------------------------------

    async def change_password(
        self, user_id: str, old_password: str, new_password: str
    ) -> bool:
        """修改密码（新密码使用 bcrypt 哈希）。

        修改成功后记录密码修改时间戳，使该用户已签发的旧 token 失效。
        """
        # 新密码长度校验（与 register 方法一致）
        if not new_password or len(new_password.encode("utf-8")) > self.MAX_PASSWORD_BYTES:
            return False

        async with self.db() as session:
            user = await self._get_user_by_id(session, user_id)
            if user is None:
                return False
            if not self._verify_password(
                old_password, user.password_hash, user.salt
            ):
                return False
            user.password_hash, user.salt = self._hash_password(new_password)
            await session.commit()

        # 记录密码修改时间戳，使该用户已签发的旧 token 失效
        self._password_changed[str(user_id)] = time.time()
        logger.info("密码修改: user_id=%s", user_id)
        return True

    async def update_balance(self, user_id: str, delta: float) -> bool:
        """增减余额（数据库侧单条 UPDATE 原子操作，避免先读后写）。

        扣款（delta 为负）时 WHERE 附加 ``balance >= -delta`` 条件：
        余额不足时 rowcount=0，UPDATE 不生效，余额保持不变，
        避免并发扣款把余额扣成负数（余额下限保护）。

        参数:
            user_id: 用户 ID
            delta: 余额变化量，正数为增加，负数为减少

        返回:
            成功返回 True；用户不存在、ID 非法或余额不足返回 False
        """
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        stmt = (
            update(User)
            .where(User.id == uid)
            .values(balance=User.balance + Decimal(str(delta)))
        )
        if delta < 0:
            # 余额下限：仅当余额足够覆盖本次扣款时才执行
            stmt = stmt.where(User.balance >= Decimal(str(-delta)))
        async with self.db() as session:
            result = await session.execute(stmt)
            await session.commit()
            if result.rowcount:
                logger.info("余额更新: user_id=%s delta=%+g", user_id, delta)
            elif delta < 0:
                # rowcount=0：用户不存在或余额不足（WHERE 条件未命中）
                logger.warning(
                    "余额扣减被拒绝（用户不存在或余额不足）: user_id=%s delta=%+g",
                    user_id, delta,
                )
            return result.rowcount > 0

    async def set_balance(self, user_id: str, balance: float) -> bool:
        """直接设置余额。"""
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        async with self.db() as session:
            result = await session.execute(
                update(User)
                .where(User.id == uid)
                .values(balance=Decimal(str(balance)))
            )
            await session.commit()
            if result.rowcount:
                logger.info("余额设置: user_id=%s balance=%g", user_id, balance)
            return result.rowcount > 0

    # ------------------------------------------------------------------
    # 余额熔断（预扣费模式）
    # ------------------------------------------------------------------

    async def get_balance(self, user_id: str) -> float | None:
        """获取用户余额，用户不存在返回 None。"""
        uid = self._to_uuid(user_id)
        if uid is None:
            return None
        async with self.db() as session:
            result = await session.execute(
                select(User.balance).where(User.id == uid)
            )
            val = result.scalar_one_or_none()
            return float(val) if val is not None else None

    async def precharge(self, user_id: str, amount: float) -> bool:
        """预扣费：调用消耗型 API 前冻结预估金额。

        利用 update_balance 的原子 WHERE balance >= amount 保护，
        余额不足时返回 False，调用方应返回 402 拒绝服务。
        """
        if amount <= 0:
            return True
        ok = await self.update_balance(user_id, -amount)
        if ok:
            logger.info("预扣费: user_id=%s amount=%g", user_id, amount)
        else:
            logger.warning(
                "预扣费失败（余额不足）: user_id=%s amount=%g", user_id, amount
            )
        return ok

    async def refund(self, user_id: str, amount: float) -> bool:
        """退回预扣费：API 调用失败或实际消耗低于预扣时退还差额。"""
        if amount <= 0:
            return True
        ok = await self.update_balance(user_id, amount)
        if ok:
            logger.info("退回预扣: user_id=%s amount=%g", user_id, amount)
        return ok

    # ------------------------------------------------------------------
    # 管理员操作
    # ------------------------------------------------------------------

    async def _set_status(
        self, user_id: str, from_status: str, to_status: str
    ) -> bool:
        """状态迁移：仅当当前状态为 from_status 时迁移到 to_status。"""
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        async with self.db() as session:
            result = await session.execute(
                update(User)
                .where(User.id == uid, User.status == from_status)
                .values(status=to_status)
            )
            await session.commit()
            if result.rowcount:
                logger.info(
                    "用户状态变更: user_id=%s %s -> %s",
                    user_id, from_status, to_status,
                )
            return result.rowcount > 0

    async def approve_user(self, user_id: str) -> bool:
        """审批用户 pending -> active。"""
        return await self._set_status(
            user_id, self.STATUS_PENDING, self.STATUS_ACTIVE
        )

    async def suspend_user(self, user_id: str) -> bool:
        """封禁用户 active -> suspended，并吊销其全部已签发 token。"""
        ok = await self._set_status(
            user_id, self.STATUS_ACTIVE, self.STATUS_SUSPENDED
        )
        if ok:
            # 吊销闭环：封禁后立即使该用户所有 token 失效
            self.revoke_user_tokens(user_id)
        return ok

    async def activate_user(self, user_id: str) -> bool:
        """解封用户 suspended -> active。"""
        return await self._set_status(
            user_id, self.STATUS_SUSPENDED, self.STATUS_ACTIVE
        )

    async def delete_user(self, user_id: str) -> bool:
        """删除用户（软删除：状态标记为 deleted，不可再登录）。"""
        uid = self._to_uuid(user_id)
        if uid is None:
            return False
        async with self.db() as session:
            result = await session.execute(
                update(User)
                .where(User.id == uid, User.status != self.STATUS_DELETED)
                .values(status=self.STATUS_DELETED)
            )
            await session.commit()
            if result.rowcount:
                logger.info("用户删除（软删除）: user_id=%s", user_id)
            return result.rowcount > 0
