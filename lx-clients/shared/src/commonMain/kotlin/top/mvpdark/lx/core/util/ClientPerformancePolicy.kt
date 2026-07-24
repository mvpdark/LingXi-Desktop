package top.mvpdark.lx.core.util

data class ImageHistoryReference(
    val imageRef: String,
    val operation: String,
)

data class ImageHistoryAppendResult(
    val retained: List<ImageHistoryReference>,
    val evictedImageRefs: List<String>,
)

fun appendImageHistory(
    history: List<ImageHistoryReference>,
    entry: ImageHistoryReference,
    maxSteps: Int = 10,
): ImageHistoryAppendResult {
    val combined = history + entry
    val retained = combined.takeLast(maxSteps)
    return ImageHistoryAppendResult(retained, combined.dropLast(retained.size).map { it.imageRef })
}

fun unreferencedImageRefs(
    evicted: List<String>,
    retained: List<ImageHistoryReference>,
    currentImageRef: String?,
): List<String> {
    val referenced = retained.mapTo(mutableSetOf()) { it.imageRef }
    currentImageRef?.let(referenced::add)
    return evicted.distinct().filterNot(referenced::contains)
}

class SingleFlightGate {
    private var running = false

    @Synchronized
    fun tryStart(): Boolean {
        if (running) return false
        running = true
        return true
    }

    @Synchronized
    fun finish() {
        running = false
    }
}

class TextUpdateThrottle(
    private val windowMillis: Long,
    private val nowMillis: () -> Long,
) {
    private val text = StringBuilder()
    private var lastPublishedAt: Long? = null
    private var dirty = false

    fun append(delta: String): String? {
        text.append(delta)
        dirty = true
        val now = nowMillis()
        val last = lastPublishedAt
        return if (last == null || now - last >= windowMillis) publish(now) else null
    }

    fun flush(): String? = if (dirty) publish(nowMillis()) else null

    private fun publish(now: Long): String {
        lastPublishedAt = now
        dirty = false
        return text.toString()
    }
}
