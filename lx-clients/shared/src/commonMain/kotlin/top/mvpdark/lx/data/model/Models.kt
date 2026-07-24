package top.mvpdark.lx.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 灵犀共享数据模型。
 *
 * 字段命名与后端 JSON 响应保持一致（snake_case），通过 kotlinx.serialization
 * 的 @SerialName 注解映射到 Kotlin 属性。
 */

/** 用户账户信息。 */
@Serializable
data class User(
    @SerialName("user_id") val id: String = "",
    val username: String = "",
    val role: String = "user",
    val balance: Double = 0.0,
    val status: String = "active",
)

/** 登录请求体。 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

/** 注册请求体。 */
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
)

/** 登录响应（后端返回 {ok, user_id, username, role, balance, access_token, refresh_token, expires_in}）。 */
@Serializable
data class LoginResponse(
    val ok: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val role: String = "user",
    val balance: Double = 0.0,
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val error: String = "",
)

/** GET /api/auth/me 响应。 */
@Serializable
data class MeResponse(
    @SerialName("user_id") val id: String = "",
    val username: String = "",
    val role: String = "user",
    val balance: Double = 0.0,
    val status: String = "active",
)

/** 聊天会话。 */
@Serializable
data class ChatSession(
    val id: String = "",
    val title: String = "新对话",
    val pinned: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

/** 聊天消息。 */
@Serializable
data class ChatMessage(
    val id: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val role: String = "user",
    val content: String = "",
    val images: List<String> = emptyList(),
    val timestamp: String = "",
)

/**
 * Agent 执行事件。对应后端 WebSocket /ws/chat 流式返回的 AgentEvent 帧。
 *
 * 事件类型：
 * - routing: 路由判断中
 * - dispatch: 已决定调用哪些子 Agent
 * - status: 状态提示文案
 * - agent_done: 某个子 Agent 完成
 * - agent_error: 某个子 Agent 失败
 * - synthesis_start: 主 Agent 开始整合结果
 * - delta: 流式文本增量
 * - search_image: 搜索到的图片
 * - done: 全部完成
 * - error: 错误
 * - auth_ok: 鉴权成功
 * - heartbeat: 心跳
 * - pong: 心跳回应
 */
@Serializable
data class AgentEvent(
    val type: String = "",
    val content: String = "",
    @SerialName("agent_name") val agentName: String = "",
    @SerialName("agent_key") val agentKey: String = "",
    @SerialName("agents_dispatched") val agentsDispatched: List<String> = emptyList(),
    @SerialName("route_reason") val routeReason: String = "",
    val error: String = "",
    /** AI 图效完成后附带的图片 URL（后端 image_enhancer 专用）。 */
    @SerialName("image_url") val imageUrl: String = "",
)

/** WebSocket 发送的消息帧。 */
@Serializable
data class ChatFrame(
    val type: String = "chat",
    @SerialName("session_id") val sessionId: String,
    val message: String,
    @SerialName("image_url") val imageUrl: String = "",
)

/** WebSocket 鉴权帧。 */
@Serializable
data class AuthFrame(
    val type: String = "auth",
    val token: String,
)

/** GET /api/sessions 响应。 */
@Serializable
data class SessionListResponse(
    val sessions: List<ChatSession> = emptyList(),
)

/** 修图预设。 */
@Serializable
data class Preset(
    val id: String = "",
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val prompt: String = "",
    val icon: String = "",
)

/** 预设列表响应。 */
@Serializable
data class PresetListResponse(
    val success: Boolean = false,
    val presets: List<Preset> = emptyList(),
)


/** 协作修图阶段信息 */
@Serializable
data class CollaborativeStage(
    @SerialName("agent_name") val agentName: String = "",
    @SerialName("agent_key") val agentKey: String = "",
    val status: String = "", // "pending", "running", "done", "error"
    val content: String = "",
)

/** 协作修图响应 */
@Serializable
data class CollaborativeEditResponse(
    val success: Boolean = false,
    val image: String = "",
    val stages: List<CollaborativeStage> = emptyList(),
    val error: String = "",
)

/** 风格迁移响应 */
@Serializable
data class StyleTransferResponse(
    val success: Boolean = false,
    val image: String = "",
    @SerialName("style_analysis") val styleAnalysis: String = "",
    val error: String = "",
)
