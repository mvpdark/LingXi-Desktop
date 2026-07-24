package top.mvpdark.lx.android

import android.app.Application
import org.koin.core.context.startKoin
import top.mvpdark.lx.core.network.PlatformContext
import top.mvpdark.lx.di.AndroidAppContextHolder
import top.mvpdark.lx.di.appModule
import top.mvpdark.lx.di.platformModule

/**
 * Android Application 入口。
 *
 * 在 [onCreate] 中：
 * 1. 注入 Application Context 到 [AndroidAppContextHolder]（供 platformModule 读取）
 * 2. 启动 Koin，注册 appModule + platformModule
 */
class LxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 注入平台 Context（PlatformContext 包装 android.content.Context）
        AndroidAppContextHolder.context = PlatformContext(this)
        // 启动 Koin
        startKoin {
            modules(appModule, platformModule)
        }
    }
}
