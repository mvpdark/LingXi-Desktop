package top.mvpdark.lx.core.util

/**
 * iOS 平台日志实现。
 *
 * P0 阶段使用 println 转发到 stdout（在模拟器调试时可捕获）。
 * 后续可替换为 NSLog / os_log 以获得 Xcode Console 原生日志输出。
 *
 * 与 Desktop 实现一致，格式为 [tag/level] message。
 */
actual fun platformLogD(tag: String, message: String) {
    println("[$tag/D] $message")
}

actual fun platformLogE(tag: String, message: String, throwable: Throwable?) {
    println("[$tag/E] $message")
    throwable?.printStackTrace()
}

actual fun platformLogW(tag: String, message: String) {
    println("[$tag/W] $message")
}
