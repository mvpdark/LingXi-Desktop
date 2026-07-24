package top.mvpdark.lx.core.network

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * 平台上下文。各平台提供实际承载物：
 * - Android: 持有 android.content.Context
 * - Desktop: 空实现，仅作占位
 */
expect class PlatformContext

/**
 * 当前平台标识："Android" / "Desktop"。
 */
expect val currentPlatform: String

/**
 * 创建平台对应的 Ktor HttpClient 引擎工厂。
 * - Android: OkHttp
 * - Desktop: Java
 */
expect fun createEngine(): HttpClientEngineFactory<*>
