package top.mvpdark.lx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android 实现：no-op。
 *
 * Android 平台不支持桌面式文件拖拽，图片选择通过 [rememberImagePickerLauncher]
 * 调用系统 ActivityResultContracts.GetContent() 实现。
 */
@Composable
actual fun Modifier.imageDropZone(
    onDraggingChange: (Boolean) -> Unit,
    onImageDropped: (ByteArray) -> Unit,
): Modifier = this
