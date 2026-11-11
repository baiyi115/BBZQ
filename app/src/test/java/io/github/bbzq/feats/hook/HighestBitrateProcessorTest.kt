package io.github.bbzq.feats.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections

class HighestBitrateProcessorTest {
    class Video(private val codec: Long = 7, private val url: String = "https://example.invalid/video", private val bandwidth: Long = 2_000_000L) {
        fun getCodecid() = codec
        fun getBaseUrl() = url
        fun getWidth() = 1920
        fun getHeight() = 1080
        fun getBandwidth() = bandwidth
        fun getFrameRate() = "30"
    }
    class Info(private val qn: Long) { fun getQuality() = qn }
    class Stream(private val qn: Long, private val present: Boolean = true, private val video: Video = Video()) {
        fun hasDashVideo() = present
        fun getDashVideo() = video
        fun getStreamInfo() = Info(qn)
    }
    class Container(private var quality: Long, private val streams: List<Stream>) {
        fun getQuality() = quality
        fun setQuality(value: Long) { quality = value }
        fun getStreamListList() = streams
    }
    class Response(private val container: Any) { fun getVideoInfo() = container }
    class FieldContainer(private var quality: Long, @JvmField var streamList_: List<Stream>) {
        fun getQuality() = quality
        fun setQuality(value: Long) { quality = value }
        fun getStreamListList() = streamList_
    }
    class MutableContainer(private var quality: Long, private val streams: MutableList<Stream>) {
        fun getQuality() = quality
        fun setQuality(value: Long) { quality = value }
        fun getStreamListList() = streams
    }
    class NestedResponse(private val container: Any) {
        fun getViewInfo() = Response(container)
    }

    private fun processor() = HighestBitrateProcessor { message, error -> throw AssertionError(message, error) }

    @Test fun singleCandidateCorrectsStaleSelectedQuality() {
        val container = Container(127, listOf(Stream(80)))
        assertEquals(80L, processor().preferHighestBitrate(Response(container))!!.quality)
        assertEquals(80L, container.getQuality())
    }

    @Test fun absentDashMessageIsNotPlayableEvenWithDefaultObject() {
        val container = Container(127, listOf(Stream(127, present = false), Stream(80)))
        assertEquals(80L, processor().preferHighestBitrate(Response(container))!!.quality)
    }

    @Test fun emptyUrlDefaultMessageDoesNotCreateStats() {
        val container = Container(80, listOf(Stream(80, video = Video(url = ""))))
        assertNull(processor().readStats(Response(container)))
    }

    @Test fun nestedVideoInfoStatsAreRead() {
        val container = Container(80, listOf(Stream(80)))
        assertEquals(80L, processor().readStats(NestedResponse(container))!!.quality)
    }

    @Test fun hdrAvoidanceKeepsFallbackAndSelectsAvailableSdr() {
        val streams = Collections.unmodifiableList(listOf(Stream(125), Stream(80)))
        val container = Container(125, streams)
        assertEquals(80L, processor().preferHighestBitrate(Response(container), avoidHdrDolby = true)!!.quality)
        assertEquals(2, container.getStreamListList().size)
    }

    @Test fun onlyHdrRemainsPlayableRatherThanBeingDeleted() {
        val container = Container(125, listOf(Stream(125)))
        assertEquals(125L, processor().preferHighestBitrate(Response(container), avoidHdrDolby = true)!!.quality)
        assertEquals(1, container.getStreamListList().size)
    }

    @Test fun immutableGetterUsesCompatibleFieldWithReadback() {
        val low = Stream(80, video = Video(bandwidth = 1_000_000))
        val high = Stream(80, video = Video(bandwidth = 3_000_000))
        val container = FieldContainer(80, Collections.unmodifiableList(listOf(low, high)))
        assertEquals(3_000_000L, processor().preferHighestBitrate(Response(container))!!.bandwidth)
        assertEquals(listOf(high, low), container.getStreamListList())
    }

    @Test fun mutableGetterReorderKeepsBothCandidates() {
        val low = Stream(80, video = Video(bandwidth = 1_000_000))
        val high = Stream(80, video = Video(bandwidth = 3_000_000))
        val container = MutableContainer(80, mutableListOf(low, high))
        assertEquals(3_000_000L, processor().preferHighestBitrate(Response(container))!!.bandwidth)
        assertEquals(listOf(high, low), container.getStreamListList())
    }

    @Test fun unsupportedWriterReportsFailureAndKeepsOriginals() {
        val low = Stream(80, video = Video(bandwidth = 1_000_000))
        val high = Stream(80, video = Video(bandwidth = 3_000_000))
        val container = Container(80, Collections.unmodifiableList(listOf(low, high)))
        var failures = 0
        HighestBitrateProcessor { _, _ -> failures++ }.preferHighestBitrate(Response(container))
        assertEquals(1, failures)
        assertEquals(listOf(low, high), container.getStreamListList())
    }

    @Test fun unknownCodecIsReportedNotDeleted() {
        val container = Container(80, listOf(Stream(80, video = Video(codec = 99))))
        assertEquals(99L, processor().readStats(Response(container))!!.codecId)
    }
}
