package top.mvpdark.lx.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientPerformancePolicyTest {
    @Test
    fun historyKeepsOnlyTenNewestImageReferences() {
        val history = (1..10).map { ImageHistoryReference("cache://$it", "op$it") }

        val result = appendImageHistory(history, ImageHistoryReference("cache://11", "op11"))

        assertEquals((2..11).map { "cache://$it" }, result.retained.map { it.imageRef })
        assertEquals(listOf("cache://1"), result.evictedImageRefs)
    }

    @Test
    fun cleanupCandidatesExcludeReferencesStillUsedByHistory() {
        val retained = listOf(
            ImageHistoryReference("cache://same", "first"),
            ImageHistoryReference("cache://new", "second"),
        )

        val candidates = unreferencedImageRefs(
            evicted = listOf("cache://same", "cache://old", "cache://old"),
            retained = retained,
            currentImageRef = "cache://current",
        )

        assertEquals(listOf("cache://old"), candidates)
    }

    @Test
    fun textThrottlePublishesAtMostOncePerWindowAndFlushesRemainder() {
        var now = 0L
        val throttle = TextUpdateThrottle(windowMillis = 50, nowMillis = { now })

        assertEquals("a", throttle.append("a"))
        now = 10
        assertEquals(null, throttle.append("b"))
        now = 49
        assertEquals(null, throttle.append("c"))
        now = 50
        assertEquals("abcd", throttle.append("d"))
        now = 55
        assertEquals("abcd", throttle.flush())
        assertEquals(null, throttle.flush())
    }

    @Test
    fun singleFlightGateRejectsConcurrentStartUntilFinished() {
        val gate = SingleFlightGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }
}
