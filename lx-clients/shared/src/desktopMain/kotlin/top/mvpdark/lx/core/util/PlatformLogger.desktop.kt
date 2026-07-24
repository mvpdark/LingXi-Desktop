package top.mvpdark.lx.core.util

import java.io.PrintStream

actual fun platformLogD(tag: String, message: String) {
    println("[$tag/D] $message")
}

actual fun platformLogE(tag: String, message: String, throwable: Throwable?) {
    val err: PrintStream = System.err
    err.println("[$tag/E] $message")
    throwable?.printStackTrace(err)
}

actual fun platformLogW(tag: String, message: String) {
    println("[$tag/W] $message")
}
