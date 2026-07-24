package top.mvpdark.lx.di

import org.koin.dsl.module
import top.mvpdark.lx.core.network.PlatformContext
import top.mvpdark.lx.core.network.TokenStore
import top.mvpdark.lx.data.local.LocalMessageStore

/**
 * Android App Context 持有器。
 *
 * 由 androidApp 的 [top.mvpdark.lx.android.LxApplication] 在 onCreate 中初始化，
 * 供 [platformModule] 在 Koin 启动时读取。
 *
 * 采用此持有器而非 koin-android 的 `androidContext()`，是为了避免在共享模块
 * androidMain 中引入额外依赖；功能等价。
 */
object AndroidAppContextHolder {
    @Volatile
    var context: PlatformContext? = null
}

/**
 * Android 平台 Koin 模块：提供 [TokenStore]（依赖 android.content.Context）。
 *
 * 注意：调用方需在 `startKoin` 前完成 [AndroidAppContextHolder] 的初始化。
 */
val platformModule = module {
    // PlatformContext 注册一次（与 Desktop/iOS 端保持一致），
    // 供 TokenStore / LocalMessageStore / ImageSaver 等共用
    single {
        AndroidAppContextHolder.context
            ?: error("AndroidAppContextHolder 未初始化，请在 LxApplication.onCreate 中赋值")
    }
    single { TokenStore(get()) }
    single { LocalMessageStore(get()) }
}
