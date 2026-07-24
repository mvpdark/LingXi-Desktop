package top.mvpdark.lx.ui.imageedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mvpdark.lx.core.util.PlatformLogger
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop 图片选择器。
 *
 * 平台策略：
 * - Windows：优先使用 [WindowsFilePicker]（COM IFileOpenDialog，Win11 Fluent Design 原生对话框），
 *   失败时回退到 AWT [FileDialog]
 * - macOS / Linux：使用 AWT [FileDialog]（调用各自系统的原生文件对话框）
 *
 * 所有文件选择操作在 [Dispatchers.IO] 中执行，避免阻塞 Compose UI 线程。
 */
@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)

    return remember(Unit) {
        {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        val path = pickFilePath()
                        path?.let { File(it).readBytes() }
                    }.onFailure { e ->
                        PlatformLogger.e("ImagePicker", "Failed to pick image file", e)
                    }.getOrNull()
                }
                currentOnResult(bytes)
            }
        }
    }
}

/**
 * 选择图片文件路径。
 *
 * Windows 平台优先尝试 COM IFileOpenDialog（Win11 Fluent Design），
 * 失败或取消时回退到 AWT FileDialog。
 */
private fun pickFilePath(): String? {
    if (isWindows()) {
        // COM IFileOpenDialog — Win11 原生 Fluent Design 文件选择器
        val nativePath = WindowsFilePicker.pickImageFile()
        if (nativePath != null) return nativePath
        // 原生选择器失败或用户取消 → 回退到 FileDialog
        // （用户取消时 nativePath 为 null，FileDialog 也会让用户取消）
    }
    return pickWithFileDialog()
}

/**
 * AWT FileDialog 回退方案（macOS / Linux 原生对话框）。
 */
private fun pickWithFileDialog(): String? {
    val dialog = FileDialog(null as Frame?, "选择图片", FileDialog.LOAD).apply {
        file = "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp"
        setFilenameFilter { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        }
        isVisible = true
    }
    val dir = dialog.directory
    val file = dialog.file
    return if (dir != null && file != null) {
        File(dir, file).absolutePath
    } else {
        null
    }
}

private fun isWindows(): Boolean {
    return System.getProperty("os.name", "").lowercase().contains("win")
}
