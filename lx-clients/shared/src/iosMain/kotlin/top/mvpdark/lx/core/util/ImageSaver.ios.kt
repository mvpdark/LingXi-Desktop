package top.mvpdark.lx.core.util

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage
import platform.posix.memcpy
import top.mvpdark.lx.core.network.PlatformContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * iOS 图片保存实现：通过 PHPhotoLibrary 保存到系统相册。
 *
 * 支持的图片来源：
 * - `http://` / `https://`：用 NSData.dataWithContentsOfURL 下载
 * - `data:`：Base64 解码
 * - `file://`：读取本地文件
 *
 * 保存流程：
 * 1. 读取图片字节流
 * 2. 用 UIImage.imageWithData 创建 UIImage
 * 3. PHPhotoLibrary.performChanges 中调用 PHAssetCreationRequest.creationRequestForAsset
 *
 * @param context iOS 平台上下文（空占位）
 */
actual class ImageSaver actual constructor(private val context: PlatformContext) {

    actual suspend fun saveImage(imageUrl: String, suggestedName: String): Result<String> =
        withContext(Dispatchers.Default) {
            runCatching {
                val bytes = readBytes(imageUrl)
                require(bytes.isNotEmpty()) { "图片内容为空" }

                // 创建 UIImage
                val nsData = bytes.toNSData()
                val image = UIImage.imageWithData(nsData)
                    ?: error("无法解码图片数据")

                // 保存到系统相册
                saveToPhotos(image)
            }
        }

    /**
     * 按图片来源读取字节流：data: / file:// / http(s)。
     */
    private fun readBytes(imageUrl: String): ByteArray {
        return when {
            imageUrl.startsWith("data:") -> decodeDataUrl(imageUrl)
            imageUrl.startsWith("file://") -> {
                val path = imageUrl.removePrefix("file://")
                val data = NSData.dataWithContentsOfFile(path)
                    ?: error("文件不存在: $path")
                data.toByteArray()
            }
            imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                val url = NSURL.URLWithString(imageUrl)
                    ?: error("无效的 URL: $imageUrl")
                val data = NSData.dataWithContentsOfURL(url)
                    ?: error("下载失败: $imageUrl")
                data.toByteArray()
            }
            else -> {
                // 兜底：相对路径补全为完整 URL 后下载
                val resolved = UrlResolver.resolveImageUrl(imageUrl)
                val url = NSURL.URLWithString(resolved)
                    ?: error("无效的 URL: $resolved")
                val data = NSData.dataWithContentsOfURL(url)
                    ?: error("下载失败: $resolved")
                data.toByteArray()
            }
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
     * 通过 PHPhotoLibrary 将 UIImage 保存到系统相册。
     * 使用 suspendCoroutine 包装 performChanges 的异步回调。
     */
    private suspend fun saveToPhotos(image: UIImage): String = suspendCoroutine { cont ->
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            {
                PHAssetCreationRequest.creationRequestForAsset(image)
            },
            completionHandler = { success, error ->
                if (success) {
                    cont.resume("已保存到系统相册")
                } else {
                    cont.resumeWithException(
                        error?.let { Exception(it.localizedDescription) }
                            ?: Exception("保存到相册失败")
                    )
                }
            },
        )
    }
}

// ============================================================
// NSData ↔ ByteArray 转换扩展
// ============================================================

/**
 * ByteArray 转 NSData。
 */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    val size = this.size
    return if (size == 0) {
        NSData()
    } else {
        val bytes = this
        bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), length = size.toULong())
        }
    }
}

/**
 * NSData 转 ByteArray（通过 memcpy 拷贝底层字节）。
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = this.length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) {
        val nsData = this
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), nsData.bytes, size.toULong())
        }
    }
    return bytes
}
