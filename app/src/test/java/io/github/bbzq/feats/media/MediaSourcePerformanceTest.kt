package io.github.bbzq.feats.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MediaSourcePerformanceTest {
    private var now = 0L
    private val model = RecentMediaSourcePerformance { now }
    private val slow = "https://slow.example/video"
    private val fast = "https://fast.example/video"

    @Test fun smallIndexLearnsLatencyWithoutInventingThroughput() {
        model.record(model.epoch(), slow, ms(400), 0, 0)
        model.record(model.epoch(), fast, ms(40), 0, 0)
        assertEquals(listOf(fast, slow), model.rankForRange(listOf(slow, fast), 16, false))
        assertEquals(800L, model.hedgeDelayMs(slow))
        assertEquals(listOf(slow, fast), model.rankForRange(listOf(slow, fast), 1024 * 1024, false))
        assertEquals(listOf(slow), model.transferCandidates(listOf(slow, fast), 1024 * 1024))
        assertFalse(model.shouldParallel(listOf(slow, fast), 4 * 1024 * 1024))
        assertEquals(0.0, model.rescueRate(slow, listOf(slow, fast)), 0.0)
    }

    @Test fun largeRangeStillUsesThroughputInsteadOfOnlyLatency() {
        model.record(model.epoch(), slow, ms(20), ms(2000), 1024 * 1024)
        model.record(model.epoch(), fast, ms(100), ms(100), 1024 * 1024)
        assertEquals(slow, model.rankForRange(listOf(slow, fast), 16, false).first())
        assertEquals(fast, model.rankForRange(listOf(slow, fast), 1024 * 1024, false).first())
        val rate = model.rescueRate(slow, listOf(slow, fast))
        now = ms(1000)
        model.record(model.epoch(), fast, ms(50), 1, 16)
        assertEquals(rate, model.rescueRate(slow, listOf(slow, fast)), 0.0)
    }

    @Test fun repeatedSmallRequestsDoNotKeepOldThroughputAlive() {
        model.record(model.epoch(), fast, ms(100), ms(100), 1024 * 1024)
        now = ms(20000)
        model.record(model.epoch(), fast, ms(100), 0, 0)
        now = ms(31000)
        assertEquals(0.0, model.rescueRate(slow, listOf(slow, fast)), 0.0)
        assertEquals(250L, model.hedgeDelayMs(fast))
        model.record(model.epoch(), fast, ms(100), ms(2000), 1024 * 1024)
        assertEquals(1024.0 * 1024 / ms(2000) * 0.35, model.rescueRate(slow, listOf(slow, fast)), 1e-12)
    }

    @Test fun scopeChangeRejectsLateObservationsAndExpiresLatency() {
        model.updateScope(false)
        val oldEpoch = model.epoch()
        model.record(oldEpoch, slow, ms(400), ms(100), 1024 * 1024)
        model.updateScope(true)
        model.record(oldEpoch, slow, ms(400), ms(100), 1024 * 1024)
        assertEquals(350L, model.hedgeDelayMs(slow))
        model.record(model.epoch(), slow, ms(400), 0, 0)
        assertEquals(800L, model.hedgeDelayMs(slow))
        now = ms(31000)
        assertEquals(350L, model.hedgeDelayMs(slow))
    }

    private fun ms(value: Long) = TimeUnit.MILLISECONDS.toNanos(value)
}
