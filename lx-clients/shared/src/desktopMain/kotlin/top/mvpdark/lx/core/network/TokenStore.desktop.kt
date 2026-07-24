package top.mvpdark.lx.core.network

import java.util.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop 平台 TokenStore：基于 java.util.prefs.Preferences 持久化。
 *
 * TODO: 安全加固 — java.util.prefs.Preferences 为明文存储（XML 文件），
 * 应迁移到平台原生密钥链（macOS Keychain / Windows Credential Manager / Linux Secret Service）。
 * 可考虑使用 jkeychain 或类似库，将 token 加密后存储。
 *
 * @param context Desktop 平台上下文（空占位，未使用）。
 */
actual class TokenStore actual constructor(context: PlatformContext) {

    private val prefs: Preferences = Preferences.userRoot().node("top/mvpdark/lx")

    actual suspend fun getAccessToken(): String? {
        return withContext(Dispatchers.IO) {
            prefs.get(KEY_ACCESS_TOKEN, null)
        }
    }

    actual suspend fun setAccessToken(token: String?) {
        withContext(Dispatchers.IO) {
            if (token.isNullOrBlank()) {
                prefs.remove(KEY_ACCESS_TOKEN)
            } else {
                prefs.put(KEY_ACCESS_TOKEN, token)
            }
            prefs.flush()
        }
    }

    actual suspend fun getRefreshToken(): String? {
        return withContext(Dispatchers.IO) {
            prefs.get(KEY_REFRESH_TOKEN, null)
        }
    }

    actual suspend fun setRefreshToken(token: String?) {
        withContext(Dispatchers.IO) {
            if (token.isNullOrBlank()) {
                prefs.remove(KEY_REFRESH_TOKEN)
            } else {
                prefs.put(KEY_REFRESH_TOKEN, token)
            }
            prefs.flush()
        }
    }

    actual suspend fun setTokens(accessToken: String, refreshToken: String) {
        // 原子写入：在单次 IO 上下文中同时写入两个 token 并 flush，减少中间崩溃窗口
        withContext(Dispatchers.IO) {
            prefs.put(KEY_ACCESS_TOKEN, accessToken)
            prefs.put(KEY_REFRESH_TOKEN, refreshToken)
            prefs.flush()
        }
    }

    actual suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.remove(KEY_ACCESS_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.flush()
        }
    }

    private companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
