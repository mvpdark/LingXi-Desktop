package top.mvpdark.lx.ui.imageedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS 平台图片选择器：基于 PHPickerViewController（iOS 14+）。
 *
 * 与 Android 端的 ActivityResultContracts.GetContent 对齐：
 * 返回一个启动函数，调用时打开系统图片选择器，
 * 用户选择图片后通过 [onResult] 回调返回图片字节流。
 *
 * 实现要点：
 * - PHPickerConfiguration 设置 imagesFilter + selectionLimit=1
 * - 通过 UIApplication 获取 keyWindow 的 rootViewController 来 present
 * - delegate 需强引用持有（PHPickerViewController.delegate 是 weak），
 *   用 [retainedDelegate] 全局变量防止被 GC 回收
 * - 结果通过 NSItemProvider.loadItemForTypeIdentifier 加载，
 *   支持 NSData（内联数据）和 NSURL（文件 URL）两种返回类型
 *
 * @param onResult 选择图片后的回调，参数为图片字节流（取消选择时为 null）
 * @return 启动选择器的函数
 */
@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    // 用 rememberUpdatedState 保证回调始终引用最新值（避免重组后闭包过期）
    val currentOnResult by rememberUpdatedState(onResult)
    return {
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(config)
        val delegate = PickerDelegate { bytes ->
            currentOnResult(bytes)
        }
        // PHPickerViewController.delegate 是 weak 引用，需手动强持有
        retainedDelegate = delegate
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

/**
 * 强持有当前 picker delegate，防止被 GC 回收（delegate 属性是 weak）。
 * picker 结束后置 null 释放。
 */
private var retainedDelegate: PickerDelegate? = null

/**
 * PHPickerViewController delegate 实现。
 *
 * picker:didFinishPicking: 回调中：
 * 1. dismiss picker
 * 2. 从第一个 PHPickerResult 的 itemProvider 加载图片数据
 * 3. 转为 ByteArray 后回调
 */
private class PickerDelegate(
    private val onResult: (ByteArray?) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<PHPickerResult>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        retainedDelegate = null

        val result = didFinishPicking.firstOrNull()
        if (result == null) {
            onResult(null)
            return
        }

        val provider: NSItemProvider = result.itemProvider
        val typeIdentifier = "public.image"
        if (!provider.hasItemConformingToTypeIdentifier(typeIdentifier)) {
            onResult(null)
            return
        }

        provider.loadItemForTypeIdentifier(typeIdentifier, options = null) { item, error ->
            if (error != null) {
                onResult(null)
                return@loadItemForTypeIdentifier
            }
            val bytes = when (item) {
                is NSData -> item.toByteArray()
                is NSURL -> {
                    val data = NSData.dataWithContentsOfURL(item)
                    data?.toByteArray()
                }
                else -> null
            }
            onResult(bytes)
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

/**
 * 获取当前最顶层的 UIViewController（用于 present picker）。
 *
 * 遍历 UIApplication 的 keyWindow → rootViewController → presentedViewController 链，
 * 找到最顶层的 VC。
 */
private fun topViewController(): UIViewController? {
    val keyWindow: UIWindow? = UIApplication.sharedApplication.windows.firstOrNull { it.isKeyWindow }
    val root = keyWindow?.rootViewController ?: return null
    var top = root
    while (top.presentedViewController != null) {
        top = top.presentedViewController!!
    }
    return top
}
