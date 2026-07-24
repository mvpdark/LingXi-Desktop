package top.mvpdark.lx.ui.update

/**
 * APK 下载和安装接口（expect/actual）。
 *
 * Android: 使用 FileProvider + ACTION_INSTALL_PACKAGE 触发系统安装。
 * Desktop: 无操作（桌面端通过 MSI/EXE 更新）。
 */

/**
 * 下载并安装 APK。
 *
 * @param downloadUrl APK 下载链接。
 * @param onProgress 下载进度回调（0-100）。
 * @param onComplete 完成回调（true=成功安装，false=失败）。
 */
expect class ApkInstaller() {
    /**
     * 下载 APK 到缓存目录并触发安装。
     *
     * @param downloadUrl APK 下载链接。
     * @param onProgress 下载进度回调（0-100）。
     * @param onComplete 完成回调（true=下载完成并触发安装，false=失败）。
     */
    suspend fun downloadAndInstall(
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit,
    )
}
