package top.mvpdark.lx.core.util

import android.util.Log

actual fun platformLogD(tag: String, message: String) {
    Log.d(tag, message)
}

actual fun platformLogE(tag: String, message: String, throwable: Throwable?) {
    if (throwable != null) {
        Log.e(tag, message, throwable)
    } else {
        Log.e(tag, message)
    }
}

actual fun platformLogW(tag: String, message: String) {
    Log.w(tag, message)
}
