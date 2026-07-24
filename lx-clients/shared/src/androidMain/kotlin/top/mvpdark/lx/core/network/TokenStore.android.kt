package top.mvpdark.lx.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import top.mvpdark.lx.core.util.PlatformLogger

/**
 * 顶层 DataStore 扩展，按进程单例持有。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lx_prefs")

/**
 * Android 平台 TokenStore：基于 DataStore Preferences 持久化。
 *
 * TODO: 安全加固 — 应迁移到 EncryptedSharedPreferences（基于 Android Keystore）。
 * 当前使用明文 DataStore Preferences 存储，root 设备可被直接提取 token。
 * 迁移步骤：添加 androidx.security:security-crypto 依赖，用
 * EncryptedSharedPreferences 替换 DataStore，密钥由 Android Keystore 托管。
 *
 * @param context Android Context（[PlatformContext] 在 Android 上即 Context）。
 */
actual class TokenStore actual constructor(context: PlatformContext) {

    private val ctx: Context = context.androidContext
    private val dataStore: DataStore<Preferences> get() = ctx.dataStore

    init {
        PlatformLogger.w("TokenStore", "当前使用明文 DataStore 存储 token，root 设备可被提取，建议迁移到 EncryptedSharedPreferences")
    }

    actual suspend fun getAccessToken(): String? {
        return dataStore.data.map { it[KEY_ACCESS_TOKEN] }.first()
    }

    actual suspend fun setAccessToken(token: String?) {
        dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(KEY_ACCESS_TOKEN)
            } else {
                prefs[KEY_ACCESS_TOKEN] = token
            }
        }
    }

    actual suspend fun getRefreshToken(): String? {
        return dataStore.data.map { it[KEY_REFRESH_TOKEN] }.first()
    }

    actual suspend fun setRefreshToken(token: String?) {
        dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(KEY_REFRESH_TOKEN)
            } else {
                prefs[KEY_REFRESH_TOKEN] = token
            }
        }
    }

    actual suspend fun setTokens(accessToken: String, refreshToken: String) {
        // 原子写入：在单次 edit 事务中同时落盘两个 token，避免中间崩溃导致不一致
        dataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = accessToken
            prefs[KEY_REFRESH_TOKEN] = refreshToken
        }
    }

    actual suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }
}
