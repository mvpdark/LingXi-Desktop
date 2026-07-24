package top.mvpdark.lx.core.util

/**
 * 编码工具（纯 Kotlin 实现，跨平台可用）。
 *
 * 提供 Base64 编码与 data URL 转换，用于将本地选择的图片字节流
 * 转换为 Coil3 可直接显示、可随消息发送的 data URL。
 */
object EncodeUtils {

    private val BASE64_TABLE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    /**
     * 将字节流编码为 Base64 字符串。
     *
     * 纯 Kotlin 实现，不依赖平台 API，确保 commonMain 跨平台行为统一。
     */
    fun encodeBase64(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            sb.append(BASE64_TABLE[b0 ushr 2])
            sb.append(BASE64_TABLE[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)])
            sb.append(if (b1 >= 0) BASE64_TABLE[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) BASE64_TABLE[b2 and 0x3F] else '=')

            i += 3
        }
        return sb.toString()
    }

    /**
     * 将图片字节流转换为 data URL。
     *
     * @param bytes 图片字节流。
     * @param mime MIME 类型，默认 `image/jpeg`。
     * @return 形如 `data:image/jpeg;base64,...` 的字符串，可直接作为 Coil3 model 或随消息发送。
     */
    fun bytesToDataUrl(bytes: ByteArray, mime: String = "image/jpeg"): String {
        return "data:$mime;base64," + encodeBase64(bytes)
    }
}

/**
 * 将 data URL 或纯 Base64 字符串解码为字节流。
 *
 * 支持两种输入格式：
 * - data URL：`data:image/jpeg;base64,...`
 * - 纯 Base64 字符串
 *
 * 解码失败时返回 null。
 */
fun decodeBase64ToBytes(dataUrlOrBase64: String): ByteArray? {
    return try {
        val base64Part = if (dataUrlOrBase64.startsWith("data:")) {
            dataUrlOrBase64.substringAfter("base64,")
        } else {
            dataUrlOrBase64
        }
        decodeBase64String(base64Part)
    } catch (e: Exception) {
        null
    }
}

private val BASE64_DECODE_TABLE = IntArray(128) { -1 }.apply {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    chars.forEachIndexed { index, char ->
        this[char.code] = index
    }
}

private fun decodeBase64String(base64: String): ByteArray {
    val clean = base64.trim().trimEnd('=')
    if (clean.isEmpty()) return ByteArray(0)

    val padding = when (clean.length % 4) {
        2 -> 2
        3 -> 1
        else -> 0
    }
    val outputLength = (clean.length * 3) / 4 - padding
    val output = ByteArray(outputLength)
    var pos = 0
    var buffer = 0
    var bits = 0

    for (i in clean.indices) {
        val char = clean[i]
        if (char.code >= 128) continue
        val value = BASE64_DECODE_TABLE[char.code]
        if (value < 0) continue

        buffer = (buffer shl 6) or value
        bits += 6

        if (bits >= 8) {
            bits -= 8
            output[pos++] = ((buffer shr bits) and 0xFF).toByte()
        }
    }

    return output.copyOf(pos)
}
