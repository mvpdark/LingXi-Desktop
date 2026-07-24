package top.mvpdark.lx.core.util

/**
 * Desktop (JVM) 平台 actual 实现。
 */
actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatDouble(value: Double, decimals: Int): String {
    // 固定 Locale.US：德/法等 Locale 下 %f 输出逗号小数点（"1,50"），
    // 会导致金额等数值显示错乱
    return String.format(java.util.Locale.US, "%.${decimals}f", value)
}

actual fun getAppVersion(): String {
    return System.getProperty("lx.version") ?: "1.0.0"
}

actual fun isAutoUpdateSupported(): Boolean = false

internal actual fun localUtcOffsetMinutes(): Int =
    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
