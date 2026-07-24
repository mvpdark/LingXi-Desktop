package top.mvpdark.lx.ui.imageedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    // 缓存 JFileChooser 实例，避免每次打开都重新创建（包含文件系统扫描开销）
    val fileChooser = remember {
        JFileChooser().apply {
            fileFilter = FileNameExtensionFilter(
                "Images (jpg, png, webp, gif, bmp)",
                "jpg", "jpeg", "png", "webp", "gif", "bmp",
            )
            isMultiSelectionEnabled = false
        }
    }
    // rememberUpdatedState 持有最新 onResult：remember(Unit) 缓存的启动 lambda
    // 不会因重组捕获到过期的回调引用
    val currentOnResult by rememberUpdatedState(onResult)
    return remember(Unit) {
        {
            // TODO: 传入 Compose 窗口的 AWT Window 作为 parent，避免对话框可能出现在屏幕角落
            val result = fileChooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val selectedFile = fileChooser.selectedFile
                // 将 IO 密集的读取操作切到 Dispatchers.IO，避免阻塞 UI 线程
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching {
                            selectedFile?.readBytes()
                        }.onFailure { e ->
                            top.mvpdark.lx.core.util.PlatformLogger.e(
                                "ImagePicker",
                                "Failed to read image file: ${selectedFile?.absolutePath}",
                                e,
                            )
                        }.getOrNull()
                    }
                    currentOnResult(bytes)
                }
            } else {
                currentOnResult(null)
            }
        }
    }
}
