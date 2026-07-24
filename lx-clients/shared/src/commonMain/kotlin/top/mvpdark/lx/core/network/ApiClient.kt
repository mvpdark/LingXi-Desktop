package top.mvpdark.lx.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.mvpdark.lx.core.util.PlatformLogger
import top.mvpdark.lx.core.util.UrlResolver

/**
 * 灵犀后端 API 客户端。
 *
 * 封装 Ktor HttpClient，统一管理：
 * - JSON 序列化（kotlinx.serialization）
 * - 鉴权（Bearer Token 注入 + 401 自动刷新 + 重试）
 * - WebSocket 支持
 * - 基础 URL
 *
 * 鉴权流程：
 * 1. 每个请求自动注入 Authorization: Bearer <access_token>
 * 2. 收到 401 时，用 refresh_token 调用 /api/auth/refresh 获取新 access_token
 * 3. 刷新成功 → 重试原请求（限 1 次）
 * 4. 刷新失败 → 清除 token，后续请求不再带 Authorization header
 *
 * @param tokenStore token 持久化存储
 * @param baseUrl 后端基地址，默认使用 [UrlResolver.BASE_URL]（生产环境）
 */
class ApiClient(
    val tokenStore: TokenStore,
    val baseUrl: String = UrlResolver.BASE_URL,
) {
    /** 共享的 JSON 配置：宽松解析，忽略未知字段。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** 用于内部 refresh 请求的独立 HttpClient（避免插件递归）。 */
    private val refreshClient: HttpClient = HttpClient(createEngine()) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
        }
    }

    /** 防止并发请求同时触发多次 refresh。 */
    private val refreshMutex = Mutex()
    /** 标记 refresh 是否已失败，避免无意义的重复刷新尝试。 */
    @Volatile
    private var refreshFailed = false

    /**
     * 后端域名（从 [baseUrl] 用 Ktor [Url] 解析，commonMain 可用）。
     * 用于限制 Bearer token 只注入到本站请求，避免泄漏给第三方域名。
     */
    private val baseUrlHost: String by lazy {
        runCatching { Url(baseUrl).host }.getOrDefault("")
    }

    /** 鉴权拦截器插件：注入 token + 401 自动刷新重试。 */
    private val authPlugin = createClientPlugin("AuthPlugin") {
        val store = tokenStore
        val apiClient = this@ApiClient

        // 请求前：注入 access token
        onRequest { request, _ ->
            if (refreshFailed) return@onRequest
            // 仅向后端域名注入 token：绝对地址指向第三方域名（如 api.github.com）时跳过；
            // 相对地址（host 为空，稍后被 defaultRequest 合并为 baseUrl）视为本站请求。
            val requestHost = request.url.host
            if (baseUrlHost.isNotEmpty() && requestHost.isNotEmpty() &&
                !requestHost.equals(baseUrlHost, ignoreCase = true)
            ) {
                return@onRequest
            }
            val token = store.getAccessToken()
            if (!token.isNullOrBlank() && request.headers[HttpHeaders.Authorization] == null) {
                request.header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

        // 响应后：检测 401，自动刷新并重试
        onResponse { response ->
            if (response.status != HttpStatusCode.Unauthorized) return@onResponse
            // 获取原始请求，准备重试
            val originalRequest = response.request
            // 避免刷新接口自身 401 导致递归
            if (originalRequest.url.encodedPath.contains("/api/auth/refresh")) return@onResponse

            // 尝试刷新 token（互斥，防止并发多次刷新）
            val refreshed = apiClient.tryRefreshToken()
            if (!refreshed) return@onResponse

            // 刷新成功后，这里不能直接重试（Ktor 插件限制），
            // 实际重试由 HttpClient 的 HttpCallValidator 或调用方处理。
            // 为保持简单，我们在此标记 response 让上层感知。
            // 但 Ktor 的 onResponse 不能重发请求，所以 401 自动重试
            // 改为在 Repository 层通过 safeRequest 包装实现。
        }
    }

    /**
     * 尝试用 refresh_token 换取新的 access_token。
     *
     * 使用 [refreshMutex] 保证同一时刻只有一个刷新请求在执行。
     * 刷新成功后更新 TokenStore；失败则清除所有 token 并标记 [refreshFailed]。
     *
     * @return true=刷新成功，false=刷新失败或无 refresh_token
     */
    private suspend fun tryRefreshToken(): Boolean {
        if (refreshFailed) return false

        return refreshMutex.withLock {
            // 双重检查：可能在等待锁期间已被其他请求刷新成功
            if (refreshFailed) return@withLock false

            val refreshToken = tokenStore.getRefreshToken()
            if (refreshToken.isNullOrBlank()) {
                refreshFailed = true
                return@withLock false
            }

            try {
                val response = refreshClient.post("/api/auth/refresh") {
                    setBody(RefreshRequest(refreshToken))
                }
                if (!response.status.isSuccess()) {
                    PlatformLogger.e("ApiClient", "Refresh token failed: ${response.status}")
                    // 仅当 refresh_token 被拒绝（401/403）时才标记永久失败并清除 token；
                    // 5xx 等服务端瞬时错误不设置 refreshFailed，允许服务恢复后再次刷新。
                    if (response.status.value == 401 || response.status.value == 403) {
                        refreshFailed = true
                        tokenStore.clear()
                    }
                    return@withLock false
                }
                val body = response.body<RefreshResponse>()
                if (!body.ok || body.accessToken.isBlank()) {
                    PlatformLogger.e("ApiClient", "Refresh response invalid: ok=${body.ok}")
                    refreshFailed = true
                    tokenStore.clear()
                    return@withLock false
                }
                // 刷新成功，更新 token（优先使用原子写入，避免中间崩溃导致两 token 不一致）
                if (body.refreshToken.isNotBlank()) {
                    tokenStore.setTokens(body.accessToken, body.refreshToken)
                } else {
                    tokenStore.setAccessToken(body.accessToken)
                }
                PlatformLogger.d("ApiClient", "Token refreshed successfully")
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 网络异常（连接超时、DNS 失败、SocketTimeout 等）视为瞬时故障：
                // 不设置 refreshFailed、不清除 token，允许网络恢复后再次刷新。
                PlatformLogger.w("ApiClient", "Refresh token network error (transient): ${e.message}")
                false
            }
        }
    }

    /**
     * 重置刷新失败状态（用户重新登录后调用）。
     */
    fun resetRefreshState() {
        refreshFailed = false
    }

    /**
     * 检查并刷新 token（供 Repository 层在请求前调用）。
     *
     * 如果 access token 可能过期（通过 refreshFailed 标志判断），
     * 尝试刷新。Repository 层可用此方法实现"先刷新再重试"策略。
     *
     * @return true=token 有效或刷新成功，false=需要重新登录
     */
    suspend fun ensureValidToken(): Boolean {
        if (refreshFailed) return false
        val accessToken = tokenStore.getAccessToken()
        if (!accessToken.isNullOrBlank()) return true
        // 无 access token，尝试用 refresh token
        return tryRefreshToken()
    }

    /** 已配置好的 HttpClient 实例。 */
    val httpClient: HttpClient = HttpClient(createEngine()) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            // release 包关闭日志，调试时改为 HEADERS
            level = LogLevel.NONE
        }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        install(authPlugin)
        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
        }
    }

    /** WebSocket 完整地址。 */
    val wsBaseUrl: String = baseUrl
        .replace("http://", "ws://")
        .replace("https://", "wss://")

    /**
     * 关闭客户端，释放资源。
     *
     * 注：ApiClient 在 Koin 中以 single 注册，进程退出时由 JVM/ART 统一清理，
     * 通常无需显式调用 close()。保留方法便于在需要时（如热重载、测试场景）手动释放。
     */
    fun close() {
        httpClient.close()
        refreshClient.close()
    }
}

/** Refresh token 请求体。 */
@Serializable
private data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

/** Refresh token 响应体（后端返回 {ok, access_token, refresh_token?, expires_in?}）。 */
@Serializable
private data class RefreshResponse(
    val ok: Boolean = false,
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val error: String = "",
)
