package top.mvpdark.lx.core.util

import top.mvpdark.lx.core.network.PlatformContext

/**
 * 跨平台图片保存工具。
 *
 * 将生成的图片（聊天消息中的图片、全景图结果等）保存到设备本地：
 * - Android：通过 MediaStore 写入系统相册（Pictures/LX修图 目录）；
 *   API 29+ 无需任何权限，API 24-28 需要 WRITE_EXTERNAL_STORAGE 权限。
 * - Desktop：弹出 AWT FileDialog 保存对话框，由用户选择保存位置。
 *
 * 支持的图片来源：
 * - `http://` / `https://`：网络下载（15s 连接超时，30s 读取超时）；
 * - `data:`：Base64 解码；
 * - `file://`：读取本地文件；
 * - 其他相对路径：经 [UrlResolver.resolveImageUrl] 补全后按网络下载处理。
 *
 * 统一保存为 `.jpg` 扩展名（文件名附带时间戳避免重名覆盖）。
 */
expect class ImageSaver(context: PlatformContext) {

    /**
     * 保存图片到设备。
     *
     * @param imageUrl 图片 URL（http/https/data:/file://，相对路径会自动补全）。
     * @param suggestedName 建议的文件名（不含扩展名），实际文件名会附加时间戳。
     * @return 保存结果：成功时为人类可读的目标位置描述；失败时为错误信息。
     *         Desktop 端用户取消对话框时返回失败，错误信息为「已取消保存」。
     */
    suspend fun saveImage(imageUrl: String, suggestedName: String): Result<String>
}
