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

    /** B 站 DASH 视频流可能出现的全部 codecid。 */
    val VIDEO_CODEC_IDS = setOf(CODEC_AVC, CODEC_HEVC, CODEC_AV1)

    /**
     * 从 [all] 里挑出有硬件解码器的 codecid。
     *
     * 两种情况都返回 [all]：
     * - [supported] 为空 —— 问不到本机能力（清单为空或查询失败）
     * - 交集为空 —— 能力信息与实际流不匹配
     *
     * 这两种情况都不该把流筛光，那会直接黑屏；宁可退回"不做筛选"。
     */
    fun narrow(all: Set<Long>, supported: Set<Long>): Set<Long> {
        if (supported.isEmpty()) return all
        val kept = all.filterTo(HashSet()) { it in supported }
        return kept.ifEmpty { all }
    }
}
