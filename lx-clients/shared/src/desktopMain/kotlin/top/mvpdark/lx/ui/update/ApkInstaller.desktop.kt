package top.mvpdark.lx.ui.update

/**
 * Desktop 平台 APK 安装器（空实现）。
 *
 * 桌面端通过 MSI/EXE 更新，不支持 APK 安装。
 */
actual class ApkInstaller actual constructor() {
    actual suspend fun downloadAndInstall(
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        // Desktop 端无操作
        onComplete(false)
    }
}
