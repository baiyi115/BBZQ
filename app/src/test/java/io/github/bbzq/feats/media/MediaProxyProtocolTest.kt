package io.github.bbzq.feats.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaProxyProtocolTest {

    private val original = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/1/2/3.m4s?upsig=abc&deadline=2000000000"

    @Test
    fun testProxyUrlRoundTrip() {
        val url = MediaProxyProtocol.proxyUrl(18888, original)
        assertEquals(true, url.startsWith("http://127.0.0.1:18888/media?u="))

        // 原始地址带签名与 & ，编码后必须原样还原
        val path = "/media"
        val query = url.substringAfter("?")
        assertEquals(original, MediaProxyProtocol.originalUrlOf(path, query))
    }

    @Test
    fun testOriginalUrlRejectsForeignRequests() {
        assertNull(MediaProxyProtocol.originalUrlOf("/other", "u=https%3A%2F%2Fa.com"))
        assertNull(MediaProxyProtocol.originalUrlOf("/media", null))
        assertNull(MediaProxyProtocol.originalUrlOf("/media", "x=1"))
        // 只放行 http(s)，避免变成任意本地请求的跳板
        assertNull(MediaProxyProtocol.originalUrlOf("/media", "u=file%3A%2F%2F%2Fetc%2Fpasswd"))
        assertNull(MediaProxyProtocol.originalUrlOf("/media", "u=not%20a%20url"))
    }

    @Test
    fun testParseRange() {
        val closed = MediaProxyProtocol.parseRange("bytes=0-1023")!!
        assertEquals(0L, closed.start)
        assertEquals(1023L, closed.end)

        // 开放式区间：播放器流式播放时基本都这样
        val open = MediaProxyProtocol.parseRange("bytes=1024-")!!
        assertEquals(1024L, open.start)
        assertNull(open.end)

        assertEquals(7L, MediaProxyProtocol.parseRange("Bytes=7-9")!!.start)

        // 不接受的形式一律交给回源处理
        assertNull(MediaProxyProtocol.parseRange(null))
        assertNull(MediaProxyProtocol.parseRange("items=0-1"))
        assertNull(MediaProxyProtocol.parseRange("bytes=0-9,20-29"))
        assertNull(MediaProxyProtocol.parseRange("bytes=-500"))
        assertNull(MediaProxyProtocol.parseRange("bytes=abc-"))
        assertNull(MediaProxyProtocol.parseRange("bytes=99-0"))
    }

    @Test
    fun testResolveAlwaysCapsAtChunkSize() {
        // 开放式区间被封顶：否则一个 bytes=0- 会把整个文件读进内存
        val open = MediaProxyProtocol.resolve(MediaProxyProtocol.parseRange("bytes=0-"), null)
        assertEquals(0L, open.start)
        assertEquals(MediaProxyProtocol.CHUNK_BYTES - 1, open.end)

        // 显式要得更多也封顶
        val huge = MediaProxyProtocol.resolve(
            MediaProxyProtocol.parseRange("bytes=0-999999999"),
            null,
        )
        assertEquals(MediaProxyProtocol.CHUNK_BYTES - 1, huge.end)
    }

    @Test
    fun testResolveClampsToFileEnd() {
        // 区间越过文件末尾时夹到末尾
        val clamped = MediaProxyProtocol.resolve(
            MediaProxyProtocol.parseRange("bytes=100-99999"),
            200L,
        )
        assertEquals(100L, clamped.start)
        assertEquals(199L, clamped.end)

        // 从最后几个字节开始
        val tail = MediaProxyProtocol.resolve(MediaProxyProtocol.parseRange("bytes=199-"), 200L)
        assertEquals(199L, tail.start)
        assertEquals(199L, tail.end)
    }

    @Test
    fun testNoRangeMeansWholeFileHead() {
        val resolved = MediaProxyProtocol.resolve(null, null)
        assertEquals(0L, resolved.start)
        assertEquals(MediaProxyProtocol.CHUNK_BYTES - 1, resolved.end)
        assertEquals(MediaProxyProtocol.CHUNK_BYTES, MediaProxyProtocol.length(resolved))
    }

    @Test
    fun testContentRangeHeader() {
        val range = MediaProxyProtocol.Range(100, 199)
        assertEquals("bytes 100-199/1000", MediaProxyProtocol.contentRange(range, 1000))
        assertEquals("bytes 100-199/*", MediaProxyProtocol.contentRange(range, null))
    }
}
