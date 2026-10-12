package io.github.bbzq.feats.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiecePlanningTest {

    private val mib = 1024L * 1024L

    @Test
    fun testNotWorthParallelizing() {
        // 只有一个源
        assertNull(PiecePlanning.plan(64 * mib, 1))
        // 文件太小，探测与多连接的固定开销占了大头
        assertNull(PiecePlanning.plan(4 * mib, 4))
        assertNull(PiecePlanning.plan(0, 4))
    }

    @Test
    fun testPiecesTileTheWholeFile() {
        val total = 32 * mib
        val pieces = PiecePlanning.plan(total, 4)!!
        assertTrue(pieces.size >= 2)

        // 首尾对齐，区间连续、无空隙也无重叠
        assertEquals(0L, pieces.first().start)
        assertEquals(total - 1, pieces.last().end)
        pieces.zipWithNext().forEach { (left, right) ->
            assertEquals(left.end + 1, right.start)
        }
        // 长度之和等于文件总长
        assertEquals(total, pieces.sumOf { it.length })
        // 索引连续
        assertEquals(pieces.indices.toList(), pieces.map { it.index })
    }

    @Test
    fun testPieceCountIsBoundedByHostsAndCap() {
        // 分片数不超过主机数的两倍
        assertEquals(4, PiecePlanning.plan(512 * mib, 2)!!.size)
        assertEquals(8, PiecePlanning.plan(512 * mib, 4)!!.size)
        // 也不超过上限
        assertEquals(PiecePlanning.MAX_PIECES, PiecePlanning.plan(512 * mib, 64)!!.size)
    }

    @Test
    fun testAssignIsRoundRobin() {
        val pieces = PiecePlanning.plan(32 * mib, 4)!!
        val buckets = PiecePlanning.assign(pieces, 4)
        assertEquals(4, buckets.size)
        // 第 i 片分给第 i % hostCount 个主机
        buckets.forEachIndexed { hostIndex, bucket ->
            bucket.forEach { piece -> assertEquals(hostIndex, piece.index % 4) }
        }
        // 所有分片都被分出去了，且没有重复
        assertEquals(pieces.size, buckets.sumOf { it.size })
    }

    @Test
    fun testAssignBalancesPiecesAcrossHosts() {
        // 64 MiB / 4 源 → 8 片，正好每源 2 片
        val pieces = PiecePlanning.plan(64 * mib, 4)!!
        assertEquals(8, pieces.size)
        val buckets = PiecePlanning.assign(pieces, 4)
        assertEquals(4, buckets.size)
        assertEquals(listOf(2, 2, 2, 2), buckets.map { it.size })
    }

    @Test
    fun testAssignWithFewerPiecesThanHosts() {
        // 源多于分片数：只有拿到分片的主机才有 bucket
        val pieces = PiecePlanning.plan(32 * mib, 64)!!  // 16 片
        assertEquals(16, pieces.size)
        val buckets = PiecePlanning.assign(pieces, 64)
        assertEquals(16, buckets.size)
        assertTrue(buckets.all { it.size == 1 })
    }

    @Test
    fun testAssignWithNoHosts() {
        val pieces = PiecePlanning.plan(32 * mib, 4)!!
        assertTrue(PiecePlanning.assign(pieces, 0).isEmpty())
    }
}
