package io.github.bbzq.feats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRangeTest {

    @Test
    fun testParseContentRange() {
        val parsed = HttpRange.parseContentRange("bytes 0-99/1000")!!
        assertEquals(0L, parsed.start)
        assertEquals(99L, parsed.end)
        assertEquals(1000L, parsed.total)

        // 大小写与多余空白都容忍
        val spaced = HttpRange.parseContentRange("  Bytes 10-20/30  ")!!
        assertEquals(10L, spaced.start)
        assertEquals(20L, spaced.end)
        assertEquals(30L, spaced.total)
    }

    @Test
    fun testTotalUnknownOrMalformed() {
        // 区间终点与总长度是两回事，不要看错
        val parsed = HttpRange.parseContentRange("bytes 0-100/999")!!
        assertEquals(100L, parsed.end)
        assertEquals(999L, parsed.total)

        // 服务端不知道总长度
        assertNull(HttpRange.parseContentRange("bytes 0-99/*")!!.total)
        assertNull(HttpRange.parseContentRange("garbage"))
        assertNull(HttpRange.parseContentRange(null))
        // 结束位置早于起点
        assertNull(HttpRange.parseContentRange("bytes 99-0/100"))
    }

    @Test
    fun testTotalOf() {
        assertEquals(1000L, HttpRange.totalOf("bytes 0-99/1000"))
        assertNull(HttpRange.totalOf("bytes 0-99/*"))
        assertNull(HttpRange.totalOf(null))
    }
}
