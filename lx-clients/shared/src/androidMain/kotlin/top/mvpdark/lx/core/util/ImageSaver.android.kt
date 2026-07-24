package top.mvpdark.lx.core.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.mvpdark.lx.core.network.PlatformContext

/**
 * Android 图片保存实现：写入系统相册（Pictures/LX修图）。
 *
 * - API 29+（Android 10+）：MediaStore + RELATIVE_PATH，无需任何权限；
 * - API 24-28：写入公共 Pictures 目录后经 MediaScanner 通知相册刷新，
 *   需要 WRITE_EXTERNAL_STORAGE 权限（Manifest 中以 maxSdkVersion=28 声明），
 *   未授权时返回失败信息而不是抛异常崩溃。
 */
actual class ImageSaver actual constructor(private val context: PlatformContext) {

    actual suspend fun saveImage(imageUrl: String, suggestedName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readBytes(imageUrl)
                require(bytes.isNotEmpty()) { "图片内容为空" }

                // 根据字节魔数推断真实格式，避免 PNG/WebP 被强制存为 .jpg + image/jpeg
                val (ext, mimeType) = detectImageFormat(bytes)
                val fileName = "${sanitizeFileName(suggestedName)}_${System.currentTimeMillis()}.$ext"
                val appContext = context.androidContext

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(appContext, bytes, fileName, mimeType)
                } else {
                    saveLegacy(appContext, bytes, fileName, mimeType)
                }
            }
        }

    /**
     * 按图片来源读取字节流：data: / file:// / http(s) / 相对路径。
     */
    private fun readBytes(imageUrl: String): ByteArray {
        return when {
            imageUrl.startsWith("data:") -> decodeDataUrl(imageUrl)
            imageUrl.startsWith("file://") -> File(imageUrl.removePrefix("file://")).readBytes()
            imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> download(imageUrl)
            // 兜底：相对路径补全为完整 URL 后下载
            else -> download(UrlResolver.resolveImageUrl(imageUrl))
        }
    }

    /**
     * HttpURLConnection 下载网络图片（15s 连接超时，30s 读取超时）。
     */
    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
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
     * API 29+：通过 MediaStore 插入相册（Pictures/LX修图），无需权限。
     */
    private fun saveWithMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): String {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LX修图")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: error("MediaStore 插入失败")

        val outputStream = resolver.openOutputStream(uri)
        if (outputStream == null) {
            // 打开失败时清理刚才插入的空记录，避免相册出现坏图
            resolver.delete(uri, null, null)
            error("无法打开相册输出流")
        }
        try {
            outputStream.use { it.write(bytes) }
        } catch (e: Exception) {
            // 写入中途失败（如存储空间不足）同样清理坏记录，避免相册残留坏图
            resolver.delete(uri, null, null)
            throw e
        }
        return "已保存到相册：Pictures/LX修图/$fileName"
    }

    /**
     * API 24-28：写入公共 Pictures/LX修图 目录，经 MediaScanner 通知相册。
     * 需要 WRITE_EXTERNAL_STORAGE 权限（运行时检查，未授权返回失败信息）。
     */
    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): String {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            error("未授予存储权限，无法保存图片（Android 9 及以下需要存储权限）")
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "LX修图",
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeBytes(bytes)

        // 通知系统相册扫描新文件，立即在图库中可见
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
            null,
        )
        return "已保存到相册：${file.absolutePath}"
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
