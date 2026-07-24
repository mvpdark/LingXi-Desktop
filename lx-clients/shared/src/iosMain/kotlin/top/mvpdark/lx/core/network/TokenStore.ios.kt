package top.mvpdark.lx.core.network

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults

/**
 * Token 存储器 iOS 实现：使用 NSUserDefaults 持久化 access/refresh token。
 *
 * 与各平台对照：
 * - Android：SharedPreferences
 * - Desktop：java.util.prefs.Preferences
 * - iOS：NSUserDefaults（standardUserDefaults）
 *
 * NSUserDefaults 是 iOS/macOS 原生键值存储，适用于小量配置数据（如 token）。
 * 数据以 plist 形式持久化到 App 的 Library/Preferences 目录。
 *
 * TODO: 安全加固 — NSUserDefaults 为明文存储，越狱设备可被提取。
 * 敏感 token 应迁移到 iOS Keychain（kSecClassGenericPassword），由系统安全 enclave 保护。
 *
 * @param context 平台上下文（iOS 为空占位，使用 standardUserDefaults 不需要额外上下文）
 */
actual class TokenStore actual constructor(
    @Suppress("UNUSED_PARAMETER") context: PlatformContext,
) {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual suspend fun getAccessToken(): String? {
        return defaults.stringForKey(KEY_ACCESS_TOKEN)
    }

    actual suspend fun setAccessToken(token: String?) {
        if (token.isNullOrBlank()) {
            defaults.removeObjectForKey(KEY_ACCESS_TOKEN)
        } else {
            defaults.setObject(token, forKey = KEY_ACCESS_TOKEN)
        }
        defaults.synchronize()
    }

    actual suspend fun getRefreshToken(): String? {
        return defaults.stringForKey(KEY_REFRESH_TOKEN)
    }

    actual suspend fun setRefreshToken(token: String?) {
        if (token.isNullOrBlank()) {
            defaults.removeObjectForKey(KEY_REFRESH_TOKEN)
        } else {
            defaults.setObject(token, forKey = KEY_REFRESH_TOKEN)
        }
        defaults.synchronize()
    }

    actual suspend fun setTokens(accessToken: String, refreshToken: String) {
        // 原子写入：连续写入两个 token 后统一 synchronize
        defaults.setObject(accessToken, forKey = KEY_ACCESS_TOKEN)
        defaults.setObject(refreshToken, forKey = KEY_REFRESH_TOKEN)
        defaults.synchronize()
    }

    actual suspend fun clear() {
        defaults.removeObjectForKey(KEY_ACCESS_TOKEN)
        defaults.removeObjectForKey(KEY_REFRESH_TOKEN)
        defaults.synchronize()
    }

    private companion object {
        private const val KEY_ACCESS_TOKEN = "lx_access_token"
        private const val KEY_REFRESH_TOKEN = "lx_refresh_token"
    }
}
