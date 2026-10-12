package io.github.bbzq.feats.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CdnSpeedModelTest {

    @Before
    fun setUp() = CdnSpeedModel.clear()

    @After
    fun tearDown() = CdnSpeedModel.clear()

    private fun recordFullSample(host: String, elapsedMs: Long) {
        // 100 KiB 足以越过最短样本门槛
        CdnSpeedModel.record(host = host, ttfbMs = 100, bytes = 100L * 1024L, elapsedMs = elapsedMs)
    }

    @Test
    fun testMedianRate() {
        recordFullSample("a", 100L)
        recordFullSample("a", 100L)
        recordFullSample("a", 100L)
        assertEquals(1024.0, CdnSpeedModel.estimateRate("a")!!, 1.0)

        // 中位数：三次里取中间那个（600ms → 约 170 B/ms）
        recordFullSample("b", 100L)
        recordFullSample("b", 600L)
        recordFullSample("b", 2000L)
        assertEquals(1024 / 6.0, CdnSpeedModel.estimateRate("b")!!, 1.0)
    }

    @Test
    fun testTtfbIsRecordedEvenForTinyTransfers() {
        // 传输太短，速率不可信：不记速率，但仍记首字节耗时
        CdnSpeedModel.record(host = "x", ttfbMs = 42, bytes = 1000L, elapsedMs = 10L)
        assertNull(CdnSpeedModel.estimateRate("x"))
        assertEquals(42L, CdnSpeedModel.estimateTtfb("x")!!)
    }

    @Test
    fun testNoMeasurementKeepsConfiguredOrder() {
        val hosts = listOf("c", "a", "b")
        // 一个都没测过：保持用户配置的顺序
        assertEquals(hosts, CdnSpeedModel.rank(hosts))
        // 只测过一个：不足以重排
        recordFullSample("b", 100L)
        assertEquals(hosts, CdnSpeedModel.rank(hosts))
    }

    @Test
    fun testRankPutsMeasuredFastestFirst() {
        // a 比 b 快一倍
        recordFullSample("a", 100L)
        recordFullSample("b", 200L)
        // 未测量的 c 排在已测量的后面，且保持原有相对顺序
        assertEquals(listOf("a", "b", "c"), CdnSpeedModel.rank(listOf("b", "a", "c")))

        // 交换快慢，顺序跟着反转
        CdnSpeedModel.clear()
        recordFullSample("a", 400L)
        recordFullSample("b", 100L)
        assertEquals(listOf("b", "a", "c"), CdnSpeedModel.rank(listOf("a", "b", "c")))
    }

    @Test
    fun testTtfbOnlySamplesDoNotDriveOrdering() {
        // a 只有首字节样本（没有速率样本），b 有完整样本
        CdnSpeedModel.record(host = "a", ttfbMs = 20, bytes = 1000L, elapsedMs = 10L)
        recordFullSample("b", 200L)
        // 只有 b 可比较，不足以重排：保持配置顺序
        assertEquals(listOf("b", "a"), CdnSpeedModel.rank(listOf("b", "a")))
    }

    @Test
    fun testSamplesAreBoundedKeepingTheMostRecent() {
        repeat(40) { index -> recordFullSample("a", index + 1L) }
        // 只留最近 8 个样本 → 耗时 33..40，中位数是 36ms 那个
        assertEquals(102400.0 / 36.0, CdnSpeedModel.estimateRate("a")!!, 1.0)
    }

    @Test
    fun testClearDropsEverything() {
        recordFullSample("a", 100L)
        CdnSpeedModel.clear()
        assertNull(CdnSpeedModel.estimateRate("a"))
        assertEquals(listOf("a"), CdnSpeedModel.rank(listOf("a")))
    }
}
