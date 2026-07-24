"""数据库 ORM 模型定义。

使用 SQLAlchemy 2.0 的 Mapped[] 声明式语法，
定义LX 后端的全部数据表结构（PostgreSQL）。

表清单：
    users              用户账户
    sessions           登录会话（token）
    billing_baselines  计费基线
    chat_sessions      聊天会话
    chat_messages      聊天消息
    memory_profiles    用户记忆画像
    memory_summaries   会话记忆摘要
    usage_stats        API 用量统计
    api_keys           用户 API Key
"""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    JSON,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    Uuid,
    func,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

__all__ = [
    "Base",
    "User",
    "Session",
    "BillingBaseline",
    "ChatSession",
    "ChatMessage",
    "MemoryProfile",
    "MemorySummary",
    "UsageStat",
    "ApiKey",
]


class Base(DeclarativeBase):
    """所有 ORM 模型的声明式基类。"""


class User(Base):
    """用户账户表。"""

    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    username: Mapped[str] = mapped_column(
        String(50), unique=True, nullable=False, index=True
    )
    password_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    # bcrypt 迁移后仍保留该字段，用于兼容旧数据
    salt: Mapped[str] = mapped_column(String(64), nullable=False)
    balance: Mapped[Decimal] = mapped_column(
        Numeric(10, 2), nullable=False, default=Decimal("0.00")
    )
    # 账户状态：pending / active / suspended
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default="pending"
    )
    # 角色：admin / user
    role: Mapped[str] = mapped_column(String(20), nullable=False, default="user")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=func.now(),
        onupdate=func.now(),
    )


class Session(Base):
    """登录会话表（登录 token）。"""

    __tablename__ = "sessions"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    token: Mapped[str] = mapped_column(
        String(128), unique=True, nullable=False, index=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=func.now()
    )


class BillingBaseline(Base):
    """计费基线表（每用户一条）。"""

    __tablename__ = "billing_baselines"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        unique=True,
    )
    baseline_cents: Mapped[Decimal] = mapped_column(Numeric(12, 6), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=func.now(),
        onupdate=func.now(),
    )


class ChatSession(Base):
    """聊天会话表。"""

    __tablename__ = "chat_sessions"

    # 主键保持现有的 12 位 hex 格式（由应用层生成）
    id: Mapped[str] = mapped_column(String(12), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(
        String(200), nullable=False, default="新对话"
    )
    pinned: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=func.now(),
        onupdate=func.now(),
    )

    # 复合索引：加速"用户会话列表按置顶+更新时间排序"查询
    __table_args__ = (
        Index("ix_chat_sessions_user_pinned_updated", "user_id", "pinned", "updated_at"),
    )


class ChatMessage(Base):
    """聊天消息表。"""

    __tablename__ = "chat_messages"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    session_id: Mapped[str] = mapped_column(
        String(12),
        ForeignKey("chat_sessions.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    # 消息角色：user / assistant / system
    role: Mapped[str] = mapped_column(String(20), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # 图片 URL 列表（JSON 数组）
    images: Mapped[list[Any] | None] = mapped_column(JSON, nullable=True)
    timestamp: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=func.now(), index=True
    )

    # 复合索引：加速"按会话拉取消息历史（按时间排序）"查询
    __table_args__ = (
        Index("ix_chat_messages_session_timestamp", "session_id", "timestamp"),
    )


class MemoryProfile(Base):
    """用户记忆画像表（每用户一条）。"""

    __tablename__ = "memory_profiles"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        unique=True,
    )
    # 用户画像数据（JSON 对象）
    profile_data: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=func.now(),
        onupdate=func.now(),
    )


class MemorySummary(Base):
    """会话记忆摘要表（同一用户同一会话仅一条）。"""

    __tablename__ = "memory_summaries"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    session_id: Mapped[str] = mapped_column(
        String(12),
        ForeignKey("chat_sessions.id", ondelete="CASCADE"),
        nullable=False,
    )
    summary: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=func.now(),
        onupdate=func.now(),
    )

    # 复合唯一索引：(user_id, session_id)
    __table_args__ = (
        UniqueConstraint(
            "user_id", "session_id", name="uq_memory_summaries_user_session"
        ),
    )


class UsageStat(Base):
    """API 用量统计表。

    两种写入形态共存：
    - 按月聚合行（period='YYYY-MM'，如 '2026-07'）：同一
      (user_id, api_type, period) 仅一行，通过 INSERT ... ON CONFLICT
      DO UPDATE 原子累加 count（见 SearchService.record_usage，Tavily 用）
    - 逐次事件行（period 为 NULL）：每次调用插入一行
      （见 BillingService.record_usage，LLM / 图片计费用）。
      PostgreSQL 唯一约束中 NULL 互不相等，因此事件行之间不会冲突
    """

    __tablename__ = "usage_stats"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    # 接口类型：llm / image / search / rembg / enhance / tavily
    api_type: Mapped[str] = mapped_column(String(20), nullable=False)
    # 统计周期（'YYYY-MM' 字符串）；聚合行必填，逐次事件行为 NULL
    period: Mapped[str | None] = mapped_column(
        String(7), nullable=True, default=None
    )
    count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    cost: Mapped[Decimal] = mapped_column(
        Numeric(10, 6), nullable=False, default=Decimal("0")
    )
    timestamp: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=func.now(), index=True
    )

    # 复合唯一索引：(user_id, api_type, period)，支撑按月聚合 upsert
    __table_args__ = (
        UniqueConstraint(
            "user_id", "api_type", "period",
            name="uq_usage_stats_user_api_type_period",
        ),
    )


class ApiKey(Base):
    """用户 API Key 表。"""

    __tablename__ = "api_keys"

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid, primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    key_name: Mapped[str] = mapped_column(String(100), nullable=False)
    key_hash: Mapped[str] = mapped_column(
        String(128), unique=True, nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=func.now()
    )
    last_used_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
