package io.github.bbzq.feats.hook.mbgeb

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecSelectionTest {

    private val all = CodecSelection.VIDEO_CODEC_IDS

    @Test
    fun testKeepsOnlyHardwareDecodableCodecs() {
        // 本机没有 AV1 硬解（多数机型）
        assertEquals(
            setOf(CodecSelection.CODEC_AVC, CodecSelection.CODEC_HEVC),
            CodecSelection.narrow(all, setOf(CodecSelection.CODEC_AVC, CodecSelection.CODEC_HEVC)),
        )
        // 本机三种都有硬解
        assertEquals(all, CodecSelection.narrow(all, all))
        // 只留 AV1
        assertEquals(setOf(CodecSelection.CODEC_AV1), CodecSelection.narrow(all, setOf(CodecSelection.CODEC_AV1)))
    }

    @Test
    fun testNeverFiltersEverythingAway() {
        // 拿不到能力信息：不过滤
        assertEquals(all, CodecSelection.narrow(all, emptySet()))
        // 能力信息与实际流对不上：也不过滤，否则会黑屏
        assertEquals(all, CodecSelection.narrow(all, setOf(99L)))
        assertEquals(all, CodecSelection.narrow(all, setOf(8L, 9L)))
    }

    @Test
    fun testEmptyInputStaysEmpty() {
        assertEquals(emptySet<Long>(), CodecSelection.narrow(emptySet(), emptySet()))
        assertEquals(emptySet<Long>(), CodecSelection.narrow(emptySet(), setOf(CodecSelection.CODEC_AVC)))
    }
}
