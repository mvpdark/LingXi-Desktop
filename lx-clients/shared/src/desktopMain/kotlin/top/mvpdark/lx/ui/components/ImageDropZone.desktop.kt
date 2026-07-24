package top.mvpdark.lx.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mvpdark.lx.core.util.PlatformLogger
import java.awt.datatransfer.DataFlavor
import java.io.File

private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/**
 * Desktop 实现：使用 Compose dragAndDropTarget + AWT Transferable 处理文件拖拽。
 *
 * 流程：
 * 1. shouldStartDragAndDrop 检测是否为文件列表类型的拖拽
 * 2. onStarted/onEnded 通知 UI 更新拖拽高亮状态
 * 3. onDrop 从 Transferable 提取 File，校验扩展名后读取字节流
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Modifier.imageDropZone(
    onDraggingChange: (Boolean) -> Unit,
    onImageDropped: (ByteArray) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()

    val target = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                onDraggingChange(true)
            }

            override fun onEnded(event: DragAndDropEvent) {
                onDraggingChange(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                onDraggingChange(false)
                val transferable = event.awtTransferable
                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    val imageFile = files.firstOrNull { it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                    if (imageFile != null) {
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) {
                                runCatching { imageFile.readBytes() }
                                    .onFailure { e ->
                                        PlatformLogger.e(
                                            "ImageDropZone",
                                            "Failed to read dropped file: ${imageFile.absolutePath}",
                                            e,
                                        )
                                    }
                                    .getOrNull()
                            }
                            if (bytes != null) {
                                onImageDropped(bytes)
                            }
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.mimeTypes().any { mimeType ->
                mimeType == "application/x-java-file-list" || mimeType == "text/uri-list"
            }
        },
        target = target,
    )
}
