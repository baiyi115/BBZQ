package io.github.bbzq.feats.hook.mbgeb

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * 查询本机有哪些视频编码带硬件解码器。
 *
 * 多数机型没有 AV1 硬解，只能软解 —— 1080p 软解 AV1 会明显掉帧、发热并加快耗电。
 * Web 端的做法是 patch `canPlayType` / `isTypeSupported`，让页面认为浏览器不支持
 * AV1 从而不选它。移动端可以直接问系统的编解码器清单，比"一律禁 AV1"更准确：
 * 有 AV1 硬解的机型不受影响，同时 HEVC 软解的情况也被覆盖。
 */
object CodecCapability {

    /** B 站 codecid → MIME。 */
    private val CODEC_MIME = mapOf(
        CodecSelection.CODEC_AVC to "video/avc",
        CodecSelection.CODEC_HEVC to "video/hevc",
        CodecSelection.CODEC_AV1 to "video/av01",
    )

    /** 软解实现的名字里常见这些标记，用于 API 28 的兜底判断。 */
    private val SOFTWARE_DECODER_MARKERS =
        listOf("google", "sw.", "software", "ffmpeg", "omx.google", "c2.android")

    @Volatile
    private var cachedHardwareCodecIds: Set<Long>? = null

    /**
     * 本机有硬件解码器的那些 codecid。查询失败或清单为空时返回空集。
     *
     * 结果缓存：`MediaCodecList` 的构建不便宜，而设备能力在一次运行内不会变。
     */
    fun hardwareDecodableCodecIds(): Set<Long> {
        cachedHardwareCodecIds?.let { return it }
        val resolved = runCatching { queryHardwareDecoders() }.getOrDefault(emptySet())
        cachedHardwareCodecIds = resolved
        return resolved
    }

    /** 本机可用（= 有硬解）的 codecid，用于 [CodecSelection.narrow]。 */
    fun usableCodecIds(all: Set<Long>): Set<Long> =
        CodecSelection.narrow(all, hardwareDecodableCodecIds())

    private fun queryHardwareDecoders(): Set<Long> {
        val hardwareMimes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> !info.isEncoder && isHardwareDecoder(info) }
            .flatMap { info -> info.supportedTypes.asSequence() }
            .map { it.lowercase() }
            .toHashSet()
        if (hardwareMimes.isEmpty()) return emptySet()
        return CODEC_MIME.filterValues { mime -> mime in hardwareMimes }.keys.toSet()
    }

    private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 查询失败时按"有硬解"处理：宁可不过滤，也不要误删可用的流
            return runCatching { info.isHardwareAccelerated }.getOrDefault(true)
        }
        // API 29 以下没有该属性，只能按实现名判断
        val name = info.name.lowercase()
        return SOFTWARE_DECODER_MARKERS.none { marker -> name.contains(marker) }
    }
}
