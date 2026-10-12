package io.github.bbzq.feats.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadHealthTest {

    private var now = 1_000_000L

    @Before
    fun setUp() {
        DownloadHealth.reset()
        DownloadHealth.useClock { now }
    }

    @After
    fun tearDown() = DownloadHealth.reset()

    @Test
    fun testSingleFailureDoesNotCoolDown() {
        DownloadHealth.recordHostFailure("a")
        assertFalse(DownloadHealth.isHostCoolingDown("a"))
    }

    @Test
    fun testRepeatedFailuresCoolDownTheHost() {
        DownloadHealth.recordHostFailure("a")
        DownloadHealth.recordHostFailure("a")
        assertTrue(DownloadHealth.isHostCoolingDown("a"))
        // 别的主机不受影响
        assertFalse(DownloadHealth.isHostCoolingDown("b"))
    }

    @Test
    fun testCooldownExpires() {
        repeat(2) { DownloadHealth.recordHostFailure("a") }
        assertTrue(DownloadHealth.isHostCoolingDown("a"))
        // 冷却 5 分钟
        now += 4 * 60 * 1000L
        assertTrue(DownloadHealth.isHostCoolingDown("a"))
        now += 2 * 60 * 1000L
        assertFalse(DownloadHealth.isHostCoolingDown("a"))
    }

    @Test
    fun testSuccessClearsFailureHistoryAndCooldown() {
        repeat(2) { DownloadHealth.recordHostFailure("a") }
        assertTrue(DownloadHealth.isHostCoolingDown("a"))
        DownloadHealth.recordHostSuccess("a")
        assertFalse(DownloadHealth.isHostCoolingDown("a"))

        // 成功一次之后，再失败一次不应立刻冷却（历史已清空）
        now += 1000L
        DownloadHealth.recordHostFailure("a")
        assertFalse(DownloadHealth.isHostCoolingDown("a"))
    }

    @Test
    fun testFailuresOutsideTheWindowDoNotAccumulate() {
        DownloadHealth.recordHostFailure("a")
        // 超过 60 秒的窗口，上一次失败不再计入
        now += 61_000L
        DownloadHealth.recordHostFailure("a")
        assertFalse(DownloadHealth.isHostCoolingDown("a"))
    }

    @Test
    fun testRankLastCooling() {
        repeat(2) { DownloadHealth.recordHostFailure("b") }
        assertTrue(DownloadHealth.isHostCoolingDown("b"))
        // 冷却中的主机排到最后，其余的保持相对顺序
        assertEquals(listOf("a", "c", "b"), DownloadHealth.rankLastCooling(listOf("a", "b", "c")))
        // 没有冷却的就不动
        assertEquals(listOf("a", "c"), DownloadHealth.rankLastCooling(listOf("a", "c")))
    }

    @Test
    fun testParallelIsDisabledAfterRepeatedFailures() {
        assertFalse(DownloadHealth.shouldSkipParallel())

        DownloadHealth.recordParallelFailure()
        assertFalse(DownloadHealth.shouldSkipParallel())

        DownloadHealth.recordParallelFailure()
        assertTrue(DownloadHealth.shouldSkipParallel())
        assertTrue(DownloadHealth.parallelOffRemainingMs() > 0L)

        // 关 30 秒
        now += 29_000L
        assertTrue(DownloadHealth.shouldSkipParallel())
        now += 2_000L
        assertFalse(DownloadHealth.shouldSkipParallel())
        assertEquals(0L, DownloadHealth.parallelOffRemainingMs())
    }
}
