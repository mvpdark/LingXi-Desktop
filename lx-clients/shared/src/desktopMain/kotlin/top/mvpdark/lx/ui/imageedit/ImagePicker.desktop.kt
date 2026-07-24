package top.mvpdark.lx.ui.imageedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
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
 * 线程策略：
 * - 文件选择对话框在 [Dispatchers.Swing]（AWT EDT）上执行，因为：
 *   1. COM IFileOpenDialog::Show 需要 STA 线程 + 消息泵（EDT 满足两者）
 *   2. AWT FileDialog 也要求在 EDT 上创建和显示
 * - 文件字节读取在 [Dispatchers.IO] 上执行，避免阻塞 EDT
 */
@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)

    return remember(Unit) {
        {
            scope.launch {
                // 1. 在 EDT 上显示文件选择对话框（COM 和 AWT 都要求 EDT）
                val path = withContext(Dispatchers.Swing) {
                    runCatching { pickFilePath() }
                        .onFailure { e ->
                            PlatformLogger.e("ImagePicker", "Failed to pick image file", e)
                        }
                        .getOrNull()
                }
                // 2. 在 IO 线程上读取文件字节（不阻塞 EDT）
                val bytes = path?.let { p ->
                    withContext(Dispatchers.IO) {
                        runCatching { File(p).readBytes() }
                            .onFailure { e ->
                                PlatformLogger.e("ImagePicker", "Failed to read file: $p", e)
                            }
                            .getOrNull()
                    }
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
 *
 * 必须在 AWT EDT 上调用。
 */
private fun pickFilePath(): String? {
    if (isWindows()) {
        PlatformLogger.d("ImagePicker", "Trying WindowsFilePicker (COM IFileOpenDialog)")
        // COM IFileOpenDialog — Win11 原生 Fluent Design 文件选择器
        val nativePath = WindowsFilePicker.pickImageFile()
        if (nativePath != null) {
            PlatformLogger.d("ImagePicker", "WindowsFilePicker returned: $nativePath")
            return nativePath
        }
        PlatformLogger.d("ImagePicker", "WindowsFilePicker returned null, falling back to FileDialog")
        // 原生选择器失败或用户取消 → 回退到 FileDialog
        // （用户取消时 nativePath 为 null，FileDialog 也会让用户取消）
    }
    return pickWithFileDialog()
}

/**
 * AWT FileDialog 回退方案（macOS / Linux 原生对话框）。
 *
 * 必须在 AWT EDT 上调用。
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
