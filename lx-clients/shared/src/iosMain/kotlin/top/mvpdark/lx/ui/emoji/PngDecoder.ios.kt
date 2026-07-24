package top.mvpdark.lx.ui.emoji

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * PNG 解码器 iOS 实现：使用 Skia（Skiko）的 [Image.makeFromEncodedBytes] 解码。
 *
 * iOS 端 Compose Multiplatform 底层使用 Skia 渲染引擎，org.jetbrains.skia 包
 * 通过 Skiko 依赖自动可用（与 Desktop 端共享同一套 Skia API）。
 *
 * 与 Desktop 端区别：
 * - Desktop 使用 javax.imageio.ImageIO（JVM）
 * - iOS 使用 org.jetbrains.skia.Image（Skiko，Kotlin/Native）
 *
 * @param bytes PNG/JPG 字节数组
 * @return 解码后的 ImageBitmap，失败返回 null
 */
actual fun decodePngBytes(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return try {
        val image = Image.makeFromEncodedBytes(bytes)
        image.toComposeImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
