package top.mvpdark.lx.core.util

/**
 * 跨平台日志工具。
 *
 * 在 commonMain 中统一调用，由各平台 actual 实现转发到原生日志系统：
 * - Android：android.util.Log
 * - Desktop：System.out / System.err
 *
 * 使用方式：
 * ```
 * PlatformLogger.d("Tag", "debug message")
 * PlatformLogger.e("Tag", "error message", exception)
 * ```
 */
object PlatformLogger {

    /** Debug 级别日志。 */
    fun d(tag: String, message: String) {
        platformLogD(tag, message)
    }

    /** Error 级别日志，可附带异常堆栈。 */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        platformLogE(tag, message, throwable)
    }

    /** Warning 级别日志。 */
    fun w(tag: String, message: String) {
        platformLogW(tag, message)
    }
}

/** 平台相关的 Debug 日志实现。 */
expect fun platformLogD(tag: String, message: String)

/** 平台相关的 Error 日志实现。 */
expect fun platformLogE(tag: String, message: String, throwable: Throwable?)

/** 平台相关的 Warning 日志实现。 */
expect fun platformLogW(tag: String, message: String)
