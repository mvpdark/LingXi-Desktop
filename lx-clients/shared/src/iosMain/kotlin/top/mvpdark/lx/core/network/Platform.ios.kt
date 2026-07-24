package top.mvpdark.lx.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

/**
 * iOS 平台上下文：空实现占位。
 *
 * iOS 端 [TokenStore] 基于 NSUserDefaults（使用 standardUserDefaults），无需额外上下文。
 * 与 Desktop 端一致，PlatformContext 仅作占位。
 */
actual class PlatformContext

/** 当前平台标识。 */
actual val currentPlatform: String = "iOS"

/**
 * 创建 iOS 端 Ktor 引擎（Darwin / NSURLSession）。
 *
 * Darwin 引擎是 Ktor 在 iOS/macOS 上的原生 HTTP 引擎，基于 NSURLSession，
 * 自动复用连接池，支持 HTTP/2、TLS 证书固定等系统能力。
 */
actual fun createEngine(): HttpClientEngineFactory<*> = Darwin
