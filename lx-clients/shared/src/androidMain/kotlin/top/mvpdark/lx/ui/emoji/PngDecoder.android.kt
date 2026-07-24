package top.mvpdark.lx.ui.emoji

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android 平台 PNG 解码器。
 *
 * 使用 [BitmapFactory.decodeByteArray] 解码 PNG 字节流。
 * 注意：Android 的 BitmapFactory 不校验 CRC，因此即使 CRC 计算有误也能正常解码。
 */
actual fun decodePngBytes(bytes: ByteArray): ImageBitmap? {
    return try {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        top.mvpdark.lx.core.util.PlatformLogger.e("PngDecoder", "PNG decode failed, size=${bytes.size}", e)
        null
    }
}
