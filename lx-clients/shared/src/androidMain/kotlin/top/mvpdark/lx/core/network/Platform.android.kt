package top.mvpdark.lx.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android 平台上下文：包装 android.content.Context。
 *
 * [TokenStore] 的构造参数 `context: PlatformContext` 在 Android 上
 * 内部持有 Context，可用于 DataStore 等需要 Context 的 API。
 */
actual class PlatformContext(val androidContext: android.content.Context)

/** 当前平台标识。 */
actual val currentPlatform: String = "Android"

/**
 * 创建 Android 端 Ktor 引擎（OkHttp）。
 */
actual fun createEngine(): HttpClientEngineFactory<*> = OkHttp
