package top.mvpdark.lx.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import top.mvpdark.lx.core.network.ApiClient
import top.mvpdark.lx.core.network.createEngine
import top.mvpdark.lx.core.util.ImageSaver
import top.mvpdark.lx.core.util.UrlResolver
import top.mvpdark.lx.data.local.ImageCacheManager
import top.mvpdark.lx.data.repository.AuthRepository
import top.mvpdark.lx.data.repository.ChatRepository
import top.mvpdark.lx.data.repository.ImageEditRepository
import top.mvpdark.lx.data.repository.UpdateRepository
import top.mvpdark.lx.ui.auth.AuthViewModel
import top.mvpdark.lx.ui.chat.ChatViewModel
import top.mvpdark.lx.ui.imageedit.ImageEditViewModel
import top.mvpdark.lx.ui.update.UpdateViewModel

/**
 * 应用级 Koin 模块：注册共享网络层、仓库与 ViewModel。
 *
 * 平台相关依赖（[top.mvpdark.lx.core.network.TokenStore]）
 * 由各平台的 `platformModule` 提供（见 shared/src/androidMain 与 desktopMain）。
 *
 * 注意：调用方需在 `startKoin { }` 中以 `modules(appModule, platformModule)` 注册。
 */
val appModule = module {
    // baseUrl 可由 platformModule 以 named("baseUrl") 覆盖；默认使用 UrlResolver.BASE_URL
    val baseUrlQualifier = named("baseUrl")

    // 网络客户端
    single {
        ApiClient(
            tokenStore = get(),
            baseUrl = getOrNull(baseUrlQualifier) ?: UrlResolver.BASE_URL,
        )
    }

    // 仓库
    singleOf(::AuthRepository)
    singleOf(::ChatRepository)
    singleOf(::ImageEditRepository)
    singleOf(::UpdateRepository)

    // 图片下载专用 HttpClient（无 JSON 协商、无鉴权插件，用于下载网络图片到本地缓存）
    single(named("imageHttpClient")) {
        HttpClient(createEngine()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    // 本地消息与图片缓存管理器（依赖 LocalMessageStore，由 platformModule 提供）
    single { ImageCacheManager(get(named("imageHttpClient")), get()) }

    // 跨平台图片保存工具（依赖 PlatformContext，由 platformModule 提供）
    single { ImageSaver(get()) }

    // ViewModel
    viewModel { AuthViewModel(get()) }
    viewModel { ChatViewModel(get(), get(), get()) }
    viewModel { ImageEditViewModel(get()) }
    viewModel { UpdateViewModel(get()) }
}
