package top.mvpdark.lx.core.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.mvpdark.lx.core.network.PlatformContext

/**
 * Desktop 图片保存实现：弹出 AWT [FileDialog] 保存对话框，写入用户选择的文件。
 *
 * FileDialog 是模态对话框，`isVisible = true` 会阻塞当前线程直到用户
 * 选择文件或取消，因此整个流程放在 [Dispatchers.IO] 中执行，避免阻塞 UI。
 *
 * 用户取消对话框时返回失败，错误信息为「已取消保存」，UI 层直接以 Snackbar 展示。
 */
actual class ImageSaver actual constructor(@Suppress("UNUSED_PARAMETER") context: PlatformContext) {

    actual suspend fun saveImage(imageUrl: String, suggestedName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readBytes(imageUrl)
                require(bytes.isNotEmpty()) { "图片内容为空" }

                // 根据字节魔数推断真实格式，用推断出的扩展名构造默认文件名
                val (ext, _) = detectImageFormat(bytes)
                val target = chooseSaveFile(suggestedName, ext) ?: error("已取消保存")
                target.writeBytes(bytes)
                "已保存：${target.absolutePath}"
            }
        }

    /**
     * 弹出保存对话框，返回用户选择的目标文件；取消时返回 null。
     *
     * 若用户输入的文件名缺少对应扩展名，则按推断出的真实格式 [ext] 自动补齐。
     */
    private fun chooseSaveFile(suggestedName: String, ext: String): File? {
        val dialog = FileDialog(null as Frame?, "保存图片", FileDialog.SAVE)
        dialog.file = "${sanitizeFileName(suggestedName)}.$ext"
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        return if (dir != null && file != null) {
            File(dir, if (file.endsWith(".$ext", ignoreCase = true)) file else "$file.$ext")
        } else {
            null
        }
    }

    /**
     * 按图片来源读取字节流：data: / file:// / http(s) / 相对路径。
     */
    private fun readBytes(imageUrl: String): ByteArray {
        return when {
            imageUrl.startsWith("data:") -> decodeDataUrl(imageUrl)
            imageUrl.startsWith("file://") -> resolveFileUrl(imageUrl).readBytes()
            imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> download(imageUrl)
            // 兜底：相对路径补全为完整 URL 后下载
            else -> download(UrlResolver.resolveImageUrl(imageUrl))
        }
    }

    /**
     * 解析 file:// URL 为本地 [File]。
     *
     * 优先按 URI 规范解析（正确处理 file:///C:/... 三斜杠 Windows 形式）；
     * URI 解析失败时回退为前缀剥离（兼容 file://C:/... 两斜杠与纯路径）。
     *
     * 注意：直接 removePrefix("file://") 会把 file:///C:/x 解析为 /C:/x，
     * 在 Windows 上被 File 当作「当前盘符:\C:\x」，必然找不到文件（与 W5 同类 bug）。
     */
    private fun resolveFileUrl(url: String): File {
        runCatching { return File(java.net.URI(url)) }
        // 回退：剥离 file:/// 或 file:// 前缀
        val path = url.removePrefix("file:///").removePrefix("file://")
        // Windows 下 /C:/... 形式需去掉前导斜杠
        return if (path.matches(Regex("^/[A-Za-z]:/.*"))) File(path.substring(1)) else File(path)
    }

    /**
     * HttpURLConnection 下载网络图片（15s 连接超时，30s 读取超时）。
     */
    private fun download(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        return try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解码 data URL（`data:image/jpeg;base64,...`）为字节流。
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val commaIdx = dataUrl.indexOf(',')
        val base64 = if (commaIdx >= 0) dataUrl.substring(commaIdx + 1) else dataUrl
        return Base64.decode(base64)
    }

    /**
     * 根据字节魔数推断图片真实格式，返回（扩展名, MIME 类型）。
     *
     * - PNG：89 50 4E 47（'\x89' 'PNG'）
     * - JPEG：FF D8 FF
     * - WebP：字节 0-3 为 'RIFF' 且 8-11 为 'WEBP'
     * - GIF：'GIF8'
     * - 无法识别时回退为 jpg / image/jpeg
     */
    private fun detectImageFormat(bytes: ByteArray): Pair<String, String> {
        fun matches(offset: Int, vararg magic: Int): Boolean {
            if (bytes.size < offset + magic.size) return false
            for (i in magic.indices) {
                if ((bytes[offset + i].toInt() and 0xFF) != magic[i]) return false
            }
            return true
        }
        return when {
            matches(0, 0x89, 0x50, 0x4E, 0x47) -> "png" to "image/png"
            matches(0, 0xFF, 0xD8, 0xFF) -> "jpg" to "image/jpeg"
            matches(0, 0x52, 0x49, 0x46, 0x46) &&
                matches(8, 0x57, 0x45, 0x42, 0x50) -> "webp" to "image/webp"
            matches(0, 0x47, 0x49, 0x46, 0x38) -> "gif" to "image/gif"
            else -> "jpg" to "image/jpeg"
        }
    }

    /**
     * 清洗文件名：仅保留字母、数字、下划线、连字符与中日韩文字，其余替换为下划线。
     */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fff]"), "_")
        return cleaned.ifBlank { "lx_image" }
    }
}
