package top.mvpdark.lx.core.util

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * iOS (Kotlin/Native) 平台 actual 实现。
 *
 * - [currentTimeMillis]：使用 NSDate.timeIntervalSince1970（Kotlin/Native 无 System.currentTimeMillis）
 * - [formatDouble]：纯算法实现（Kotlin/Native 无 String.format）
 * - [getAppVersion]：P0 固定返回 "1.0.0"，后续可从 NSBundle.mainBundle 读取 CFBundleShortVersionString
 * - [isAutoUpdateSupported]：iOS 通过 App Store 更新，不支持应用内自动更新
 */
actual fun currentTimeMillis(): Long {
    // NSDate.date().timeIntervalSince1970 返回 Double（秒），乘 1000 转毫秒
    return (NSDate.date().timeIntervalSince1970 * 1000).toLong()
}

actual fun formatDouble(value: Double, decimals: Int): String {
    // Kotlin/Native 无 String.format，用纯算法实现
    if (decimals <= 0) {
        return roundToLong(value).toString()
    }
    val factor = 10.0.pow(decimals.toDouble())
    val rounded = (value * factor).roundToLong().toDouble() / factor
    val parts = rounded.toString().split(".")
    val intPart = parts[0]
    val decPart = if (parts.size > 1) parts[1] else ""
    val paddedDec = decPart.padEnd(decimals, '0').take(decimals)
    return "$intPart.$paddedDec"
}

actual fun getAppVersion(): String {
    // P0 阶段固定返回 "1.0.0"
    // 后续可通过 NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") 读取
    return "1.0.0"
}

actual fun isAutoUpdateSupported(): Boolean = false
