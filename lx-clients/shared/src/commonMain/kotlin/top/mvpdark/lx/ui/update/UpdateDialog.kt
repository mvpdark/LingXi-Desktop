package top.mvpdark.lx.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lx.data.repository.UpdateInfo

/**
 * 应用更新检查弹窗。
 *
 * 自动检查流程：
 * 1. App 启动后延迟 3 秒静默检查
 * 2. 发现新版本 → 显示更新弹窗
 * 3. 用户点击"立即更新" → 下载 APK（显示进度条） → 触发系统安装
 * 4. 用户点击"稍后" → 4 小时内不再自动检查
 *
 * @param currentVersion 当前版本号（如 "1.0.35"）。
 * @param autoCheck 是否在弹窗组合时自动检查。
 */
@Composable
fun UpdateCheckHost(
    currentVersion: String,
    autoCheck: Boolean = true,
    viewModel: UpdateViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // 自动检查（延迟 3 秒）
    LaunchedEffect(autoCheck) {
        if (autoCheck) {
            kotlinx.coroutines.delay(3000)
            viewModel.checkUpdate(currentVersion, autoCheck = true)
        }
    }

    // 发现新版本：显示更新弹窗
    if (state.updateInfo != null) {
        UpdateDialog(
            info = state.updateInfo!!,
            onDismiss = viewModel::skipUpdate,
            onConfirm = viewModel::dismissUpdate,
        )
    }

    // 检查失败：仅手动检查时显示错误
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("检查更新失败") },
            text = { Text(state.error!!) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text("确定")
                }
            },
        )
    }
}

/**
 * 更新弹窗：显示版本信息 + 下载进度。
 */
@Composable
private fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val installer = remember { ApkInstaller() }

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "发现新版本 v${info.latestVersion}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column {
                if (!isDownloading) {
                    Text(
                        text = info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // 下载进度
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "下载中... $downloadProgress%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                // 防御安装器回报异常进度值（负数或超过 100）
                                progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (downloadError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = downloadError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isDownloading && info.downloadUrl.isNotEmpty()) {
                        isDownloading = true
                        downloadError = null
                        scope.launch {
                            installer.downloadAndInstall(
                                downloadUrl = info.downloadUrl,
                                onProgress = { progress ->
                                    downloadProgress = progress
                                },
                                onComplete = { success ->
                                    isDownloading = false
                                    if (success) {
                                        onConfirm()
                                    } else {
                                        downloadError = "下载失败，请稍后重试"
                                    }
                                },
                            )
                        }
                    }
                },
                enabled = !isDownloading && info.downloadUrl.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("立即更新")
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("稍后")
                }
            }
        },
    )
}
