package top.mvpdark.lx.core.util

import android.os.Build
import top.mvpdark.lx.di.AndroidAppContextHolder

/**
 * Android 平台 actual 实现。
 */
actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatDouble(value: Double, decimals: Int): String {
    // 固定 US Locale：德/法等 Locale 下 "%.2f" 会输出逗号小数点（如 1,50），
    // 导致金额等展示错误（R11）
    return String.format(java.util.Locale.US, "%.${decimals}f", value)
}

actual fun getAppVersion(): String {
    return try {
        val context = AndroidAppContextHolder.context?.androidContext
            ?: return "1.0.0"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
            info.versionName ?: "1.0.0"
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        }
    } catch (e: Exception) {
        "1.0.0"
    }
}

actual fun isAutoUpdateSupported(): Boolean = true

internal actual fun localUtcOffsetMinutes(): Int =
    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
