package io.github.bbzq.feats.hook.mbgeb

/**
 * 按本机解码能力挑选可用的视频编码。
 *
 * 与 [CodecCapability] 分开是因为后者要引用 `android.media.MediaCodecList`，
 * 纯 JVM 单测碰不了；这里的判定没有 Android 依赖，可以直接覆盖。
 */
object CodecSelection {

    /** B 站 codecid：7 = AVC/H.264，12 = HEVC/H.265，13 = AV1。 */
    const val CODEC_AVC = 7L
    const val CODEC_HEVC = 12L
    const val CODEC_AV1 = 13L

    /** 既有集合策略的常用编码；不代表全部 DASH 编码（例如 VVC = 14）。 */
    val VIDEO_CODEC_IDS = setOf(CODEC_AVC, CODEC_HEVC, CODEC_AV1)

    /** No format-bound evidence: keep an official hardware name or a single unambiguous component. */
    fun chooseHardwareName(names: List<String>, preferred: String?): String? =
        names.firstOrNull { it == preferred } ?: names.distinct().singleOrNull()

    /** Empty/unknown capability or no intersection keeps the original codec set as fallback. */
    fun narrow(all: Set<Long>, supported: Set<Long>): Set<Long> {
        if (supported.isEmpty()) return all
        val kept = all.filterTo(HashSet()) { it in supported }
        return kept.ifEmpty { all }
    }
}
