package io.github.bbzq.feats.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HardwareStreamPreferenceTest {
    private data class Stream(val name: String, val group: String?, val hardware: Boolean)

    private fun prefer(streams: List<Stream>) = HardwareStreamPreference.reorder(
        streams, key = { it.group }, preferred = { it.hardware },
    )

    @Test fun hardwareMovesBeforeSoftwareInEquivalentSlots() {
        val soft = Stream("soft", "1080p30", false)
        val hard = Stream("hard", "1080p30", true)
        assertEquals(listOf(hard, soft), prefer(listOf(soft, hard)))
    }

    @Test fun differentQualitiesAndFrameRatesKeepTheirSlots() {
        val soft = Stream("soft", "1080p30", false)
        val other = Stream("60fps", "1080p60", true)
        val hard = Stream("hard", "1080p30", true)
        assertEquals(listOf(hard, other, soft), prefer(listOf(soft, other, hard)))
    }

    @Test fun unknownAndPlaceholderPositionsDoNotMove() {
        val soft = Stream("soft", "720p", false)
        val unknown = Stream("unknown", null, true)
        val hard = Stream("hard", "720p", true)
        val result = prefer(listOf(soft, unknown, hard))
        assertSame(unknown, result[1])
        assertEquals(listOf(hard, unknown, soft), result)
    }

    @Test fun noHardwareKeepsAllSoftwareFallbacks() {
        val streams = listOf(Stream("a", "720p", false), Stream("b", "720p", false))
        assertEquals(streams, prefer(streams))
    }

    @Test fun stableWithinEachPreferenceGroup() {
        val streams = listOf(
            Stream("s1", "720p", false), Stream("h1", "720p", true),
            Stream("s2", "720p", false), Stream("h2", "720p", true),
        )
        assertEquals(listOf("h1", "h2", "s1", "s2"), prefer(streams).map { it.name })
        assertEquals(streams.toSet(), prefer(streams).toSet())
    }

    @Test fun duplicateFallbacksAreNotDropped() {
        val soft = Stream("soft", "720p", false)
        val hard = Stream("hard", "720p", true)
        assertEquals(listOf(hard, soft, soft), prefer(listOf(soft, hard, soft)))
    }

    @Test fun oneCandidateAndEmptyInputArePreserved() {
        val only = Stream("only", "720p", false)
        assertEquals(listOf(only), prefer(listOf(only)))
        assertEquals(emptyList<Stream>(), prefer(emptyList()))
    }

    @Test fun rationalFrameRateIsParsedWithoutRounding() {
        assertEquals(30000.0 / 1001.0, HardwareStreamPreference.frameRate("30000/1001")!!, 0.000001)
        assertEquals(60.0, HardwareStreamPreference.frameRate("60")!!, 0.0)
    }

    @Test fun invalidFrameRatesStayUnknown() {
        listOf("", "NaN", "Infinity", "-30", "0", "30/0", "1/-2", "abc", "1/2/3").forEach {
            assertNull(it, HardwareStreamPreference.frameRate(it))
        }
    }
}
