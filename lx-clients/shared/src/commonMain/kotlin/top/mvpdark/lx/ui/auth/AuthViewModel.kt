package top.mvpdark.lx.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.mvpdark.lx.data.model.MeResponse
import top.mvpdark.lx.data.repository.AuthException
import top.mvpdark.lx.data.repository.AuthRepository
import top.mvpdark.lx.core.util.runCatchingCancellable

/**
 * 登录/注册/用户信息 UI 状态。
 */
data class AuthUiState(
    val isLoading: Boolean = false,
    val isRegisterMode: Boolean = false,
    val username: String = "",
    val password: String = "",
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val initialCheckComplete: Boolean = false,
    val user: MeResponse? = null,
)

/**
 * 认证 ViewModel：管理登录/注册/获取用户信息/登出。
 *
 * 通过 Koin 注入 [AuthRepository]。
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        // 启动时检查本地登录状态
        checkInitialAuth()
    }

    /** 更新用户名输入。 */
    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, error = null) }
    }

    /** 更新密码输入。 */
    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    /** 切换登录/注册模式。 */
    fun toggleRegisterMode() {
        _uiState.update { it.copy(isRegisterMode = !it.isRegisterMode, error = null) }
    }

    /** 清除错误。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 检查本地登录态，决定起始页。 */
    fun checkInitialAuth() {
        viewModelScope.launch {
            val loggedIn = runCatchingCancellable { authRepository.isLoggedIn() }.getOrDefault(false)
            if (loggedIn) {
                runCatchingCancellable { authRepository.getMe() }
                    .onSuccess { me ->
                        _uiState.update {
                            it.copy(
                                isLoggedIn = true,
                                initialCheckComplete = true,
                                user = me,
                            )
                        }
                    }
                    .onFailure {
                        // token 失效，保持未登录
                        _uiState.update { it.copy(isLoggedIn = false, initialCheckComplete = true) }
                    }
            } else {
                _uiState.update { it.copy(isLoggedIn = false, initialCheckComplete = true) }
            }
        }
    }

    /** 登录。成功后 [AuthUiState.isLoggedIn] 置 true。 */
    fun login() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "请输入用户名和密码") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatchingCancellable { authRepository.login(state.username, state.password) }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoggedIn = true,
                            user = MeResponse(
                                id = resp.userId,
                                username = resp.username,
                                role = resp.role,
                                balance = resp.balance,
                                status = "active",
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    val msg = when (e) {
                        is AuthException -> e.message ?: "登录失败"
                        else -> "网络错误，请检查后端服务是否启动"
                    }
                    _uiState.update { it.copy(isLoading = false, error = msg) }
                }
        }
    }

    /** 注册。注册成功后自动切回登录模式。 */
    fun register() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "请输入用户名和密码") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatchingCancellable { authRepository.register(state.username, state.password) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRegisterMode = false,
                            error = "注册成功，请登录",
                        )
                    }
                }
                .onFailure { e ->
                    val msg = when (e) {
                        is AuthException -> e.message ?: "注册失败"
                        else -> "网络错误，请检查后端服务是否启动"
                    }
                    _uiState.update { it.copy(isLoading = false, error = msg) }
                }
        }
    }

    /** 退出登录。 */
    fun logout() {
        viewModelScope.launch {
            runCatchingCancellable { authRepository.logout() }
            _uiState.update {
                AuthUiState(username = it.username) // 保留用户名便于重新登录
            }
        }
    }

    /** 刷新用户信息（余额等）。 */
    fun refreshUser() {
        viewModelScope.launch {
            runCatchingCancellable { authRepository.getMe() }
                .onSuccess { me ->
                    _uiState.update { it.copy(user = me) }
                }
        }
    }
}
