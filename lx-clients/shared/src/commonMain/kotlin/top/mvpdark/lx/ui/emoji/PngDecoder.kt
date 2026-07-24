package top.mvpdark.lx.ui.emoji

import androidx.compose.ui.graphics.ImageBitmap

/**
 * PNG 字节流解码器（expect/actual）。
 *
 * 将 [ApngParser] 构建的 PNG 字节流解码为 [ImageBitmap]。
 * 各平台使用原生解码器：
 * - Android: BitmapFactory.decodeByteArray
 * - Desktop: ImageIO.read
 *
 * @param bytes 完整的 PNG 字节流。
 * @return 解码后的 ImageBitmap。
 */
expect fun decodePngBytes(bytes: ByteArray): ImageBitmap?
