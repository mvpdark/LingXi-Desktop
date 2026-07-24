package top.mvpdark.lx.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.mvpdark.lx.data.repository.UpdateInfo
import top.mvpdark.lx.data.repository.UpdateRepository

/**
 * 更新检查 UI 状态。
 */
data class UpdateUiState(
    val isChecking: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val error: String? = null,
    /** 是否已跳过本次（用户选"稍后"）。 */
    val isSkipped: Boolean = false,
)

/**
 * 应用更新 ViewModel。
 *
 * 流程：检查版本 → 发现新版本 → 用户确认 → 下载 APK → 安装。
 * 下载和安装由平台-specific 实现（Android: ApkInstaller）。
 */
class UpdateViewModel(
    private val repository: UpdateRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    /**
     * 检查更新。
     *
     * @param currentVersion 当前版本号（如 "1.0.35"）。
     * @param autoCheck 是否为自动检查（静默，不显示错误弹窗）。
     */
    fun checkUpdate(currentVersion: String, autoCheck: Boolean = false) {
        if (_uiState.value.isChecking) return
        if (_uiState.value.isSkipped && autoCheck) return

        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, error = null) }

            val info = repository.checkLatestVersion(currentVersion)

            if (info == null) {
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        error = if (autoCheck) null else "检查更新失败，请稍后重试",
                    )
                }
            } else if (info.needsUpdate) {
                _uiState.update {
                    it.copy(isChecking = false, updateInfo = info)
                }
            } else {
                _uiState.update {
                    it.copy(isChecking = false, updateInfo = null)
                }
            }
        }
    }

    /** 用户点击"稍后"。 */
    fun skipUpdate() {
        _uiState.update { it.copy(isSkipped = true, updateInfo = null) }
    }

    /** 清除错误。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 关闭更新弹窗。 */
    fun dismissUpdate() {
        _uiState.update { it.copy(updateInfo = null) }
    }
}
