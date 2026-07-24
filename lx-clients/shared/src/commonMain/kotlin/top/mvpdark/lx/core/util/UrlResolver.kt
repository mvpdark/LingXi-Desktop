package top.mvpdark.lx.core.util

/**
 * URL 解析工具。
 *
 * 将服务端返回的相对路径（如 `/uploads/xxx.png`）补全为完整 URL，
 * 便于 Coil 等图片加载组件直接使用。
 */
object UrlResolver {

    /** 服务端基础地址。 */
    const val BASE_URL: String = "https://lx.mvpdark.top:8443"

    /**
     * 解析图片地址：
     * - `data:` 开头（Base64 / SVG data URL）原样返回；
     * - `http://` / `https://` 开头原样返回；
     * - `file://` 开头（本地缓存文件）原样返回；
     * - 以 `/` 开头的相对路径，前缀拼接 [BASE_URL]；
     * - 其余无前导斜杠的相对路径，补充一个 `/` 后拼接 [BASE_URL]；
     * - 空串原样返回。
     */
    fun resolveImageUrl(path: String): String {
        if (path.isBlank()) return path
        if (path.startsWith("data:")) return path
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("file://")) return path
        return if (path.startsWith("/")) {
            "$BASE_URL$path"
        } else {
            "$BASE_URL/$path"
        }
    }
}
