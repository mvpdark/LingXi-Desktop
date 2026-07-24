package top.mvpdark.lx.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.java.Java

/**
 * Desktop 平台上下文：空实现占位。
 *
 * Desktop 端 TokenStore 基于 java.util.prefs.Preferences，无需额外上下文。
 */
actual class PlatformContext

/** 当前平台标识。 */
actual val currentPlatform: String = "Desktop"

/**
 * 创建 Desktop 端 Ktor 引擎（Java）。
 */
actual fun createEngine(): HttpClientEngineFactory<*> = Java
