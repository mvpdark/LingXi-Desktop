package top.mvpdark.lx

import androidx.compose.runtime.Composable
import org.koin.compose.koinInject
import top.mvpdark.lx.core.util.getAppVersion
import top.mvpdark.lx.core.util.isAutoUpdateSupported
import top.mvpdark.lx.data.repository.AuthRepository
import top.mvpdark.lx.ui.navigation.NavGraph
import top.mvpdark.lx.ui.theme.LxTheme
import top.mvpdark.lx.ui.update.UpdateCheckHost

/**
 * 应用根组件。
 *
 * - 用 [LxTheme] 包裹（Noir Aurum 黑曜鎏金风格，默认强制深色）
 * - Koin 初始化检查（确保 Koin 已启动）
 * - 渲染 [NavGraph]
 * - Android 平台：启动时自动检查 GitHub Releases 新版本
 *
 * 注：启动时的登录态检查由 [top.mvpdark.lx.ui.auth.AuthViewModel] 的
 * checkInitialAuth() 在 init 中完成，此处无需重复调用。
 */
@Composable
fun LxApp() {
    LxTheme {
        // 触发 Koin 依赖解析，确认上下文可用
        // 确保平台入口（LxApplication/Main.kt）已先调用 startKoin，否则此处会抛 Koin 异常
        @Suppress("unused")
        val authRepository: AuthRepository = koinInject()

        NavGraph()

        // 自动更新检查（仅 Android 平台）
        if (isAutoUpdateSupported()) {
            UpdateCheckHost(currentVersion = getAppVersion())
        }
    }
}
