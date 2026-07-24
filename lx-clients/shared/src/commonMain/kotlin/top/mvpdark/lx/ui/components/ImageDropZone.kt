package top.mvpdark.lx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 跨平台图片拖拽区域修饰符。
 *
 * - Desktop：使用 Compose dragAndDropTarget + AWT Transferable 实现文件拖拽
 * - 其他平台：no-op（仅保持 Modifier 链不变）
 *
 * @param onDraggingChange 拖拽状态变化回调（true = 文件进入区域）
 * @param onImageDropped 图片字节流回调
 */
@Composable
expect fun Modifier.imageDropZone(
    onDraggingChange: (Boolean) -> Unit,
    onImageDropped: (ByteArray) -> Unit,
): Modifier
