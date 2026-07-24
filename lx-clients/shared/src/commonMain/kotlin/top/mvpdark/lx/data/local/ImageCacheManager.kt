package top.mvpdark.lx.data.local

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.mvpdark.lx.core.util.PlatformLogger
import top.mvpdark.lx.data.model.ChatMessage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 图片缓存管理器：负责将网络图片 URL 下载到本地缓存。
 *
 * 处理三种图片来源：
 * - `http://` / `https://`：用 [HttpClient] 下载字节流，调用 [LocalMessageStore.saveImage] 得到本地路径
 * - `data:`：解码 base64 得到字节流，以原始 data URL 的 MD5 为 key 保存到本地
 * - `file://`：已是本地路径，直接返回原值
 *
 * 多张图片通过 `async` / `awaitAll` 并行下载，并发度限制为 3（避免大批量图片
 * 同时下载打满连接池或触发服务端限流）。单张下载失败时回退为原始 URL，
 * 保证消息内容不被丢失。
 *
 * @param httpClient 用于下载网络图片的 Ktor 客户端
 * @param localStore 本地持久化存储
 */
class ImageCacheManager(
    private val httpClient: HttpClient,
    private val localStore: LocalMessageStore,
) {
    /**
     * 限制图片下载并发度的 IO 调度器。
     * 作为实例属性存储：每次 [Dispatchers.IO.limitedParallelism] 调用都会创建
     * 独立的限流器，若在 [cacheImages] 内部调用则每次都新建，限流失效。
     */
    private val limitedIo = Dispatchers.IO.limitedParallelism(3)

    /**
     * 处理消息中的图片：如果是网络 URL 则下载到本地，返回本地路径。
     * 已是本地路径（`file://`）的直接返回原值。
     *
     * @param images 原始图片 URL 列表
     * @return 处理后的本地路径列表（与输入一一对应）
     */
    suspend fun cacheImages(images: List<String>): List<String> {
        if (images.isEmpty()) return emptyList()
        return coroutineScope {
            images.map { url ->
                async(limitedIo) { cacheSingleImage(url) }
            }.awaitAll()
        }
    }

    /**
     * 批量处理消息列表中的所有图片，返回替换为本地路径的新消息列表。
     *
     * 无图片的消息原样保留，不进行拷贝。
     *
     * @param messages 原始消息列表
     * @return 图片路径已本地化的消息列表
     */
    suspend fun cacheMessagesImages(messages: List<ChatMessage>): List<ChatMessage> {
        return messages.map { msg ->
            if (msg.images.isEmpty()) msg
            else msg.copy(images = cacheImages(msg.images))
        }
    }

    /**
     * 处理单张图片：下载并缓存到本地，返回本地路径。
     * 下载或保存失败时返回原始 URL，保证消息可用性。
     */
    private suspend fun cacheSingleImage(url: String): String {
        return try {
            when {
                url.startsWith("file://") -> url

                url.startsWith("http://") || url.startsWith("https://") -> {
                    val response = httpClient.get(url)
                    // 校验 HTTP 状态码：错误响应（如 404 错误页）的字节一旦写入缓存，
                    // 其本地路径会被持久化到消息中，导致坏缓存永远命中
                    if (!response.status.isSuccess()) {
                        error("HTTP ${response.status.value}: $url")
                    }
                    val bytes = response.bodyAsBytes()
                    // 魔数校验：防止 200 状态码的错误页 HTML / JSON 被当作图片缓存，
                    // 坏缓存一旦写入会被永久命中（以 URL MD5 为 key），后续无法纠正
                    if (!isImageBytes(bytes)) {
                        error("非图片内容（URL=$url, ${bytes.size} bytes）")
                    }
                    localStore.saveImage(url, bytes)
                }

                url.startsWith("data:") -> {
                    val bytes = decodeDataUrl(url)
                    localStore.saveImage(url, bytes)
                }

                else -> url
            }
        } catch (e: CancellationException) {
            // 协程取消不能吞掉：否则父作用域取消时下载任务无法及时停止
            throw e
        } catch (e: Exception) {
            // 下载或保存失败时返回原始 URL，保证消息内容不丢失；
            // 记录日志便于排查（否则坏 URL 静默回退，无法定位根因）
            // 截断 URL 防止 data URL（多 MB base64）写入日志导致膨胀和泄漏
            val safeUrl = if (url.length > 200) url.take(200) + "...(${url.length} chars)" else url
            PlatformLogger.e("ImageCacheManager", "图片缓存失败，回退原始 URL: $safeUrl", e)
            url
        }
    }

    /**
     * 解码 data URL（`data:image/jpeg;base64,...`）为字节流。
     *
     * 取逗号后的 base64 部分进行解码，MIME 前缀忽略（统一保存为 .jpg）。
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val commaIdx = dataUrl.indexOf(',')
        val base64 = if (commaIdx >= 0) dataUrl.substring(commaIdx + 1) else dataUrl
        return Base64.decode(base64)
    }
}

/**
 * 按魔数嗅探图片格式，返回格式标识（png/webp/jpeg）或 null（未知格式）。
 *
 * 抽取为单一判定源，[sniffImageExtension] 与 [isImageBytes] 复用，避免魔数
 * 判定逻辑重复（重复的判定一旦改一处忘改另一处会产生不一致）。
 */
internal fun detectImageFormat(bytes: ByteArray): String? {
    return when {
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() -> "png" // \x89PNG
        bytes.size >= 12 &&
            bytes[8] == 0x57.toByte() &&
            bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() &&
            bytes[11] == 0x50.toByte() -> "webp" // RIFF....WEBP
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpeg" // JPEG（FF D8 FF）
        else -> null
    }
}

/**
 * 按魔数嗅探图片格式，返回扩展名（jpg/png/webp）。
 *
 * 用于 [LocalMessageStore.saveImage] 决定文件扩展名：旧实现无论内容格式
 * 一律使用 `.jpg`，当实际内容为 PNG/WebP 时扩展名与内容不匹配，
 * 部分图片查看器/WebView 会因 MIME 嗅探失败而拒绝解码。
 *
 * 未知格式回退为 `jpg`（兼容旧缓存）。
 */
internal fun sniffImageExtension(bytes: ByteArray): String {
    val format = detectImageFormat(bytes)
    return if (format == "jpeg") "jpg" else format ?: "jpg"
}

/**
 * 校验字节流是否为已知图片格式（PNG/JPEG/WEBP）。
 *
 * 用于 [ImageCacheManager.cacheSingleImage] 在 http 下载后校验响应体：
 * 防止 HTTP 200 的错误页 HTML/JSON 被当作图片缓存。
 */
internal fun isImageBytes(bytes: ByteArray): Boolean = detectImageFormat(bytes) != null
