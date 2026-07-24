package top.mvpdark.lx.data.repository

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import top.mvpdark.lx.core.network.ApiClient
import top.mvpdark.lx.core.util.PlatformLogger
import top.mvpdark.lx.core.util.runCatchingCancellable

/**
 * GitHub Release 信息（仅提取需要的字段）。
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

/**
 * 版本检查结果。
 */
data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val needsUpdate: Boolean,
)

/**
 * 应用更新仓库：检查 GitHub Releases 最新版本。
 *
 * 调用 GitHub API: GET /repos/{owner}/{repo}/releases/latest
 * 从 tag_name 提取版本号，从 assets 找到 APK 下载链接。
 */
class UpdateRepository(
    private val apiClient: ApiClient,
) {
    companion object {
        private const val GITHUB_API = "https://api.github.com"
        private const val REPO = "mvpdark/linxi"
        /** APK 下载地址允许的域名白名单。 */
        private val ALLOWED_DOWNLOAD_DOMAINS = listOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
    }

    /**
     * 检查最新版本。
     *
     * @param currentVersion 当前版本号（如 "1.0.35"）。
     * @return 更新信息，或 null 表示检查失败。
     */
    suspend fun checkLatestVersion(currentVersion: String): UpdateInfo? {
        return runCatchingCancellable {
            val release: GitHubRelease = apiClient.httpClient.get(
                "$GITHUB_API/repos/$REPO/releases/latest",
            ).body()

            // 从 tag_name 提取版本号（如 "v1.0.35" -> "1.0.35"）
            val latestVersion = release.tagName.removePrefix("v")

            // 查找 APK 资产
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            val rawDownloadUrl = apkAsset?.browserDownloadUrl ?: ""

            // 下载地址域名白名单校验，防止 Release 被篡改后指向恶意域名
            val downloadUrl = validateDownloadDomain(rawDownloadUrl)

            // TODO: 完整性校验 — GitHub Release 当前未附带 checksum 文件。
            // 应在发布流程中额外上传 *.sha256 校验文件（作为 Release asset），
            // 下载 APK 后用 SHA256 比对，防止中间人替换或 CDN 缓存污染。
            // 参见 [downloadApk] 方法中的校验骨架。

            // 比较版本号
            val needsUpdate = compareVersions(latestVersion, currentVersion) > 0

            UpdateInfo(
                latestVersion = latestVersion,
                downloadUrl = downloadUrl,
                releaseNotes = release.body.ifBlank { release.name },
                needsUpdate = needsUpdate,
            )
        }.getOrNull()
    }

    /**
     * 比较语义化版本号。
     *
     * @return 正数表示 v1 > v2，负数表示 v1 < v2，0 表示相等。
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    /**
     * 校验下载地址域名是否在白名单内。
     *
     * @return 合法则原样返回 URL，非法或空则返回空字符串并记录警告。
     */
    private fun validateDownloadDomain(downloadUrl: String): String {
        if (downloadUrl.isBlank()) return ""
        val host = runCatching { Url(downloadUrl).host }.getOrDefault("")
        if (host.isEmpty()) {
            PlatformLogger.w("UpdateRepository", "无法解析下载地址主机名: $downloadUrl")
            return ""
        }
        if (host !in ALLOWED_DOWNLOAD_DOMAINS) {
            PlatformLogger.w("UpdateRepository", "下载地址域名不在白名单内: $host")
            return ""
        }
        return downloadUrl
    }

    /**
     * 下载 APK 并校验完整性。
     *
     * 流程：
     * 1. 域名白名单校验（非法域名抛 [SecurityException]）
     * 2. 下载 APK 字节流
     * 3. SHA256 校验（需后端在 Release 中附带 checksum 文件）
     *
     * TODO: 完整性校验尚未实现 — 当前 GitHub Release 未上传 *.sha256 校验文件。
     * 待发布流程补齐 checksum 后，在此比对下载内容的 SHA256 摘要：
     * ```
     * val expectedSha256 = fetchChecksumAsset(release)  // 从 Release assets 读取 *.sha256
     * val actualSha256 = sha256(apkBytes)
     * if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
     *     throw SecurityException("APK SHA256 校验失败")
     * }
     * ```
     *
     * @param downloadUrl APK 下载地址（须通过白名单校验）
     * @return APK 字节流
     */
    suspend fun downloadApk(downloadUrl: String): ByteArray {
        // 二次校验域名白名单
        val host = runCatching { Url(downloadUrl).host }.getOrDefault("")
        if (host !in ALLOWED_DOWNLOAD_DOMAINS) {
            throw SecurityException("下载地址域名不在白名单内: $host")
        }
        // 下载 APK
        val apkBytes = apiClient.httpClient.get(downloadUrl).body<ByteArray>()
        // TODO: SHA256 完整性校验 — 待 Release 附带 checksum 后实现
        PlatformLogger.d("UpdateRepository", "APK downloaded: ${apkBytes.size} bytes, SHA256 校验待实现")
        return apkBytes
    }
}
