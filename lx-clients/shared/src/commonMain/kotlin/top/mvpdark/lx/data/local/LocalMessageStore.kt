package top.mvpdark.lx.data.local

import top.mvpdark.lx.core.network.PlatformContext
import top.mvpdark.lx.data.model.ChatMessage

/**
 * 本地消息与图片持久化存储（跨平台抽象）。
 *
 * 所有聊天消息和图片均保存在客户端本地，不依赖后端历史 API：
 * - 消息存储：每个会话一个 JSON 文件，内容为 `List<ChatMessage>` 的 JSON 数组
 * - 图片存储：以 URL 的 MD5 作为文件名，保存原始字节
 *
 * 平台实现：
 * - Android: 基于应用内部存储目录（context.filesDir）
 * - Desktop: 基于用户目录（~/.lx）
 *
 * 构造需要平台上下文，由各平台 `platformModule` 注入，
 * 参考 [top.mvpdark.lx.core.network.TokenStore] 的 expect/actual 模式。
 *
 * @param context 平台上下文（Android 持有 Context，Desktop 为空占位）
 */
expect class LocalMessageStore(context: PlatformContext) {

    /** 追加保存一条消息到指定会话的本地文件。 */
    suspend fun saveMessage(sessionId: String, message: ChatMessage)

    /** 读取指定会话的全部本地消息（按保存顺序）。 */
    suspend fun getMessages(sessionId: String): List<ChatMessage>

    /** 删除指定会话的全部本地消息。 */
    suspend fun deleteSessionMessages(sessionId: String)

    /**
     * 保存图片字节到本地，返回本地路径（`file://` 绝对路径）。
     *
     * @param url 原始图片 URL（网络 URL 或 data URL），用于生成 MD5 文件名
     * @param bytes 图片字节流
     * @return 本地 `file://` 路径，后续可用于 [resolveImage] 读取
     */
    suspend fun saveImage(url: String, bytes: ByteArray): String

    /**
     * 读取本地图片字节。
     *
     * @param localPath [saveImage] 返回的 `file://` 路径
     * @return 图片字节流，文件不存在则返回 null
     */
    suspend fun resolveImage(localPath: String): ByteArray?
}
