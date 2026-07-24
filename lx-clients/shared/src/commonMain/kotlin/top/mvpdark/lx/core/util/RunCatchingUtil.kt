package top.mvpdark.lx.core.util

import kotlinx.coroutines.CancellationException

/**
 * 可取消版本的 [runCatching]。
 *
 * 与 [runCatching] 的区别：不会捕获 [CancellationException]，而是重新抛出，
 * 确保协程的结构化并发正常工作。
 *
 * 在 suspend 函数中应使用此函数替代 [runCatching]，避免吞掉取消信号。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/**
 * 将网络/序列化异常转换为用户友好的中文错误文案。
 *
 * 用于 Repository 层的 [runCatchingCancellable] 兜底，避免把
 * "Socket timeout has expired"、"Connection refused" 等技术细节
 * 直接暴露给终端用户。
 *
 * 判断依据：异常类名关键词 + message 内容匹配，不依赖平台特定异常类，
 * 保证 commonMain 跨平台可用。
 */
fun Throwable.toUserMessage(): String {
    val className = this::class.simpleName.orEmpty().lowercase()
    val msg = message.orEmpty().lowercase()

    return when {
        // 超时类（SocketTimeoutException / HttpRequestTimeoutException）
        className.contains("timeout") -> "请求超时，请稍后重试"
        msg.contains("timeout") || msg.contains("timed out") || msg.contains("expired") -> "请求超时，请稍后重试"

        // 连接类（ConnectException / SocketException）
        className.contains("connect") -> "网络连接失败，请检查网络后重试"
        msg.contains("connect") || msg.contains("refused") || msg.contains("reset") -> "网络连接失败，请检查网络后重试"

        // DNS 解析失败（UnknownHostException）
        className.contains("host") || msg.contains("unknown host") || msg.contains("resolve") -> "无法连接服务器，请检查网络"

        // 序列化/解析异常（JSON 反序列化失败说明后端返回了非预期格式）
        className.contains("json") || className.contains("serialize") || className.contains("parse") -> "服务器响应异常，请稍后重试"

        // 其他网络 IO 异常
        className.contains("io") || className.contains("socket") -> "网络异常，请稍后重试"

        // 兜底
        else -> "网络异常，请稍后重试"
    }
}
