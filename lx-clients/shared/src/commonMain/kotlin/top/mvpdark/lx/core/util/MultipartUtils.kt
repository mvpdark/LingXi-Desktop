package top.mvpdark.lx.core.util

/**
 * multipart 上传工具。
 */

/**
 * 清洗 multipart Content-Disposition 中的文件名。
 *
 * 去掉双引号与 CR/LF，防止破坏 header 结构或产生 header 注入。
 */
fun sanitizeMultipartFileName(fileName: String): String {
    return fileName.replace("\"", "").replace("\r", "").replace("\n", "")
}
