package top.mvpdark.lx.ios

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import top.mvpdark.lx.LxApp
import top.mvpdark.lx.di.appModule
import top.mvpdark.lx.di.platformModule

/**
 * iOS 应用入口：由 Swift 端（iOS App Delegate / SwiftUI）调用，
 * 返回承载 Compose UI 的 [platform.UIKit.UIViewController]。
 *
 * 首次调用时启动 Koin（appModule + platformModule），后续调用直接复用。
 *
 * Swift 端示例：
 * ```swift
 * import ComposeApp
 * struct ContentView: UIViewControllerRepresentable {
 *     func makeUIViewController(context: Context) -> UIViewController {
 *         MainViewControllerKt.MainViewController()
 *     }
 *     func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
 * }
 * ```
 */
fun MainViewController() = run {
    // 确保 Koin 已启动（幂等，多次调用安全）
    if (GlobalContext.getOrNull() == null) {
        startKoin {
            modules(appModule, platformModule)
        }
    }
    ComposeUIViewController { LxApp() }
}
