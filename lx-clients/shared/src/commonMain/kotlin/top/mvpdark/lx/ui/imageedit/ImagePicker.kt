package top.mvpdark.lx.ui.imageedit

import androidx.compose.runtime.Composable

/**
 * 跨平台图片选择器。
 *
 * 返回一个启动函数，调用时打开系统的文件选择器，
 * 用户选择图片后通过 [onResult] 回调返回图片字节流。
 *
 * @param onResult 选择图片后的回调，参数为图片字节流（取消选择时为 null）
 * @return 启动选择器的函数
 */
@Composable
expect fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit
