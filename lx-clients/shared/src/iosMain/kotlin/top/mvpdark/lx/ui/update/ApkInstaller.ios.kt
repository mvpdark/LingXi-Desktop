package top.mvpdark.lx.ui.update

/**
 * iOS 平台 APK 安装器（no-op 实现）。
 *
 * iOS 无 APK 概念，应用更新通过 App Store 完成。
 * 所有方法直接返回失败，与 Desktop 端的空实现一致。
 */
actual class ApkInstaller actual constructor() {

    actual suspend fun downloadAndInstall(
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        // iOS 不支持 APK 安装
        onComplete(false)
    }
}
