package top.mvpdark.lx.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.koin.core.context.startKoin
import top.mvpdark.lx.LxApp
import top.mvpdark.lx.di.appModule
import top.mvpdark.lx.di.platformModule

/**
 * Desktop 应用入口。
 *
 * - 启动 Koin（appModule + platformModule）
 * - 创建 1280×800 窗口，标题「LX修图」
 * - 渲染 [LxApp] 共享根组件
 */
fun main() {
    // 1. 启动 Koin（必须在渲染 Composable 前完成）
    startKoin {
        modules(appModule, platformModule)
    }

    // 2. 创建应用窗口
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "LX修图",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            LxApp()
        }
    }
}
