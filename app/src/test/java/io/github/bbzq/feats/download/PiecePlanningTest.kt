package io.github.bbzq.feats.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiecePlanningTest {

    private val mib = 1024L * 1024L

    private fun download(offset: Long, length: Long, sources: Int) =
        PiecePlanning.plan(offset, length, sources, PiecePlanning.DOWNLOAD)

    @Test
    fun testDownloadSkipsSmallFilesAndSingleSource() {
        assertNull(download(0, 64 * mib, 1))
        assertNull(download(0, 4 * mib, 4))
        assertNull(download(0, 0, 4))
    }

    @Test
    fun testPiecesTileTheWholeRange() {
        val pieces = download(0, 32 * mib, 4)!!
        assertTrue(pieces.size >= 2)

        // 首尾对齐，区间连续、无空隙也无重叠
        assertEquals(0L, pieces.first().start)
        assertEquals(32 * mib - 1, pieces.last().end)
        pieces.zipWithNext().forEach { (left, right) -> assertEquals(left.end + 1, right.start) }
        assertEquals(32 * mib, pieces.sumOf { it.length })
        assertEquals(pieces.indices.toList(), pieces.map { it.index })
    }

    @Test
    fun testStartOffsetIsHonoured() {
        // 断点续传的片偏移必须落在剩余下载区间。
        val start = 100L * mib
        val length = 16 * mib
        val pieces = download(start, length, 4)!!
        assertEquals(start, pieces.first().start)
        assertEquals(start + length - 1, pieces.last().end)
        assertEquals(length, pieces.sumOf { it.length })
    }

    @Test
    fun testPieceCountIsBoundedBySourcesAndCap() {
        assertEquals(4, download(0, 512 * mib, 2)!!.size)
        assertEquals(8, download(0, 512 * mib, 4)!!.size)
        assertEquals(PiecePlanning.DOWNLOAD.maxPieces, download(0, 512 * mib, 64)!!.size)
    }

    @Test
    fun testAssignIsRoundRobin() {
        val pieces = download(0, 32 * mib, 4)!!
        val buckets = PiecePlanning.assign(pieces, 4)
        assertEquals(4, buckets.size)
        buckets.forEachIndexed { sourceIndex, bucket ->
            bucket.forEach { piece -> assertEquals(sourceIndex, piece.index % 4) }
        }
        assertEquals(pieces.size, buckets.sumOf { it.size })
    }

    @Test
    fun testAssignBalancesAndHandlesDegenerateInput() {
        val pieces = download(0, 64 * mib, 4)!!
        assertEquals(8, pieces.size)
        assertEquals(listOf(2, 2, 2, 2), PiecePlanning.assign(pieces, 4).map { it.size })

        assertTrue(PiecePlanning.assign(pieces, 0).isEmpty())
    }
}
