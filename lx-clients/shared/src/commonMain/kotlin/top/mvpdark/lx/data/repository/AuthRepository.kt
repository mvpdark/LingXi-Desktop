package top.mvpdark.lx.data.repository

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import top.mvpdark.lx.core.network.ApiClient
import top.mvpdark.lx.core.network.TokenStore
import top.mvpdark.lx.core.util.PlatformLogger
import top.mvpdark.lx.core.util.runCatchingCancellable
import top.mvpdark.lx.data.model.LoginRequest
import top.mvpdark.lx.data.model.LoginResponse
import top.mvpdark.lx.data.model.MeResponse
import top.mvpdark.lx.data.model.RegisterRequest

/**
 * 认证仓库：封装 /api/auth/ 接口。
 */
class AuthRepository(
    private val apiClient: ApiClient,
    private val tokenStore: TokenStore,
) {
    /** 登录，成功后持久化 token。失败抛出 [AuthException]。 */
    suspend fun login(username: String, password: String): LoginResponse {
        val response = apiClient.httpClient.post("/api/auth/login") {
            setBody(LoginRequest(username, password))
        }
        if (!response.status.isSuccess()) {
            val errorBody = runCatchingCancellable { response.body<LoginResponse>() }.getOrNull()
            throw AuthException(errorBody?.error?.ifBlank { "用户名或密码错误" } ?: "用户名或密码错误")
        }
        val body: LoginResponse = response.body()
        if (!body.ok) {
            throw AuthException(body.error.ifBlank { "用户名或密码错误" })
        }
        // 持久化 token（原子写入，避免中间崩溃导致两 token 不一致）
        tokenStore.setTokens(body.accessToken, body.refreshToken)
        // 登录成功，重置 refresh 失败状态（允许后续 401 时再次自动刷新）
        apiClient.resetRefreshState()
        return body
    }

    /** 注册，成功后返回 true。 */
    suspend fun register(username: String, password: String): Boolean {
        val response = apiClient.httpClient.post("/api/auth/register") {
            setBody(RegisterRequest(username, password))
        }
        if (!response.status.isSuccess()) {
            val errorBody = runCatchingCancellable { response.body<LoginResponse>() }.getOrNull()
            throw AuthException(errorBody?.error?.ifBlank { "注册失败" } ?: "注册失败")
        }
        val body: LoginResponse = response.body()
        if (!body.ok) {
            throw AuthException(body.error.ifBlank { "注册失败" })
        }
        return true
    }

    /** 获取当前用户信息（/api/auth/me）。 */
    suspend fun getMe(): MeResponse {
        val response = apiClient.httpClient.get("/api/auth/me")
        if (!response.status.isSuccess()) {
            throw HttpException(response.status.value, response.status.description)
        }
        return response.body()
    }

    /** 退出登录：通知后端 + 清除本地 token。 */
    suspend fun logout() {
        runCatchingCancellable {
            apiClient.httpClient.post("/api/auth/logout")
        }.onFailure { e ->
            PlatformLogger.w("AuthRepository", "Logout request failed: ${e.message}")
        }
        tokenStore.clear()
    }

    /** 当前是否已登录（本地存在 access token）。 */
    suspend fun isLoggedIn(): Boolean {
        return !tokenStore.getAccessToken().isNullOrBlank()
    }
}

/** 认证相关异常。 */
class AuthException(message: String) : Exception(message)
