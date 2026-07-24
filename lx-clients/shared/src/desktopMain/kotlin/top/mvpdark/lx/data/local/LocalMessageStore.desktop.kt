package top.mvpdark.lx.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.mvpdark.lx.core.network.PlatformContext
import top.mvpdark.lx.core.util.PlatformLogger
import top.mvpdark.lx.data.model.ChatMessage
import java.io.File
import java.security.MessageDigest

/**
 * Desktop 平台 LocalMessageStore：基于用户目录 + JSON 文件。
 *
 * - 消息存储：`~/.lx/messages/{sessionId}.json`
 * - 图片存储：`~/.lx/images/{md5(url)}.{png|jpg|webp}`（扩展名按魔数嗅探）
 *
 * 文件读写通过 [Mutex] 保证线程安全，IO 操作切换到 [Dispatchers.IO]。
 *
 * @param context Desktop 平台上下文（空占位，未使用；数据目录由 `user.home` 决定）
 */
actual class LocalMessageStore actual constructor(context: PlatformContext) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 消息列表的序列化器，避免反射。 */
    private val messageListSerializer = ListSerializer(ChatMessage.serializer())

    /** 保护同一会话文件读写的互斥锁。 */
    private val mutex = Mutex()

    private val baseDir: File get() = File(System.getProperty("user.home"), ".lx")
    private val messagesDir: File get() = File(baseDir, "messages").apply { mkdirs() }
    private val imagesDir: File get() = File(baseDir, "images").apply { mkdirs() }

    private fun messageFile(sessionId: String): File =
        File(messagesDir, "${sanitizeSessionId(sessionId)}.json")

    /**
     * 清洗 sessionId 中的路径不安全字符，防止服务端下发的会话 ID 含
     * `../`、路径分隔符等导致文件被写到 messages 目录之外（路径穿越）。
     */
    private fun sanitizeSessionId(sessionId: String): String {
        val cleaned = sessionId.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return cleaned.ifBlank { "unknown_session" }
    }

    actual suspend fun saveMessage(sessionId: String, message: ChatMessage) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = messageFile(sessionId)
                val existing = if (file.exists()) {
                    runCatching {
                        json.decodeFromString(messageListSerializer, file.readText())
                    }.getOrElse { e ->
                        // 文件损坏：先备份损坏文件再重建，而不是静默用空列表覆盖
                        // 导致全部历史消息丢失；备份文件可供排查/人工恢复。
                        // 与 Android 端实现保持一致
                        PlatformLogger.e(TAG, "消息文件损坏，备份后重建: ${file.name}", e)
                        runCatching {
                            file.copyTo(File(file.parentFile, "${file.name}.corrupted"), overwrite = true)
                        }
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                file.writeText(json.encodeToString(messageListSerializer, existing + message))
            }
        }
    }

    actual suspend fun getMessages(sessionId: String): List<ChatMessage> {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = messageFile(sessionId)
                if (!file.exists()) {
                    emptyList()
                } else {
                    runCatching {
                        json.decodeFromString(messageListSerializer, file.readText())
                    }.getOrElse { e ->
                        // 读取失败不再静默返回空列表，记录日志便于排查。
                        // 与 Android 端实现保持一致
                        PlatformLogger.e(TAG, "读取消息失败: ${file.name}", e)
                        emptyList()
                    }
                }
            }
        }
    }

    actual suspend fun deleteSessionMessages(sessionId: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                messageFile(sessionId).delete()
            }
        }
    }

    actual suspend fun saveImage(url: String, bytes: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val ext = sniffImageExtension(bytes)
            val fileName = "${md5(url)}.$ext"
            val file = File(imagesDir, fileName)
            // 原子写：先写临时文件再重命名，避免并发读/写或进程中断留下半截文件。
            // tmp 文件名加 nanoTime 后缀：并发写同一 URL 时避免两个 tmp 互相覆盖。
            // renameTo 在目标已存在时可能失败（Windows），兜底为覆盖拷贝 + 删除临时文件
            val tmpFile = File(imagesDir, "$fileName.tmp.${System.nanoTime()}")
            try {
                tmpFile.writeBytes(bytes)
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                }
            } finally {
                // 异常时（磁盘满等）清理 tmp，与 Android 端保持一致
                if (tmpFile.exists()) tmpFile.delete()
            }
            // 生成合法 file:// URI（处理 Windows 反斜杠，确保 file:/// 前缀）
            "file:///" + file.absolutePath.replace('\\', '/')
        }
    }

    actual suspend fun resolveImage(localPath: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            // 兼容 file:///C:/... （三斜杠）和 file://C:/... （两斜杠）
            val path = localPath.removePrefix("file:///").removePrefix("file://")
            val file = File(path)
            // 路径穿越防护：仅允许读取 imagesDir 内的文件
            try {
                val allowedDir = imagesDir.canonicalPath + File.separator
                val targetPath = file.canonicalPath
                if (!targetPath.startsWith(allowedDir)) return@withContext null
            } catch (e: java.io.IOException) {
                return@withContext null
            }
            if (file.exists()) file.readBytes() else null
        }
    }

    /** 计算 URL 的 MD5 十六进制摘要，用作图片文件名。 */
    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        private const val TAG = "LocalMessageStore"
    }
}
