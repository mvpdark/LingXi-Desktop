package top.mvpdark.lx.ui.emoji

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Desktop (JVM) 平台 PNG 解码器。
 *
 * 使用 [ImageIO.read] 解码 PNG 字节流。
 */
actual fun decodePngBytes(bytes: ByteArray): ImageBitmap? {
    return try {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        image?.toComposeImageBitmap()
    } catch (e: Exception) {
        top.mvpdark.lx.core.util.PlatformLogger.e("PngDecoder", "PNG decode failed, size=${bytes.size}", e)
        null
    }
}
