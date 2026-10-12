package io.github.bbzq.feats.download

/**
 * 下载源的速度模型：**被动**测量，零额外流量。
 *
 * 现有的 [io.github.bbzq.ui.CdnSpeedTestDialog] 是主动测速：手动触发、固定下一段
 * 数据、得到的是某一时刻的快照。这里的做法来自 Make Bilibili Great Than Ever
 * Before 的 host 速度模型：真实下载本来就在发请求，顺手把首字节耗时与速率记下来，
 * 不产生任何额外流量，而且反映的是当前网络下的真实表现。
 *
 * 只用字符串/数值运算，无 Android 依赖，纯 JVM 单测可直接覆盖。
 */
object CdnSpeedModel {

    /** 最多记住这么多主机的样本；超出的按最近最少使用淘汰。 */
    private const val MAX_HOSTS = 32

    /** 每台主机保留的样本数。 */
    private const val MAX_SAMPLES = 8

    /**
     * 短于这个长度的传输，耗时主要来自往返而不是带宽，速率不可信，不记入速率样本。
     */
    private const val MIN_SAMPLE_BYTES = 64L * 1024L

    private class Samples {
        val ttfb = ArrayDeque<Long>()
        val rate = ArrayDeque<Double>()
    }

    private val hosts = object : LinkedHashMap<String, Samples>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Samples>): Boolean =
            size > MAX_HOSTS
    }

    /**
     * 记录一次真实下载的结果。速率样本过短时只记首字节耗时。
     *
     * @param ttfbMs 从发起请求到收到第一个字节的毫秒数；未知时传负数
     * @param bytes 实际收到的字节数
     * @param elapsedMs 从收到第一个字节到读完的毫秒数
     */
    @Synchronized
    fun record(host: String, ttfbMs: Long, bytes: Long, elapsedMs: Long) {
        if (host.isEmpty()) return
        val samples = hosts.getOrPut(host) { Samples() }
        if (ttfbMs >= 0) push(samples.ttfb, ttfbMs)
        if (bytes >= MIN_SAMPLE_BYTES && elapsedMs > 0L) {
            push(samples.rate, bytes.toDouble() / elapsedMs.toDouble())
        }
    }

    private fun push(samples: ArrayDeque<Long>, value: Long) {
        samples.addLast(value)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    private fun push(samples: ArrayDeque<Double>, value: Double) {
        samples.addLast(value)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    /** 该主机的中位速率（字节/毫秒）；没有可用样本时返回 `null`。 */
    @Synchronized
    fun estimateRate(host: String): Double? = hosts[host]?.rate?.takeIf { it.isNotEmpty() }?.let(::median)

    /** 该主机的中位首字节耗时（毫秒）；没有样本时返回 `null`。 */
    @Synchronized
    fun estimateTtfb(host: String): Long? = hosts[host]?.ttfb?.takeIf { it.isNotEmpty() }?.let(::median)

    private fun median(values: ArrayDeque<Double>): Double =
        values.sorted()[values.size / 2]

    private fun median(values: ArrayDeque<Long>): Long =
        values.sorted()[values.size / 2]

    /**
     * 按实测速率给候选主机排序：有测量的排前面，快的更前。
     *
     * 只按**速率**样本排序，不用首字节耗时兜底。两者量纲不同（字节/毫秒 对 毫秒），
     * 混在一起比会把只有首字节样本的主机永远压到最后。首字节仍会记录，供展示用。
     *
     * 没有测量的主机不参与比较（速度未知既可能没缓存，也可能极快），它们保持原有
     * 相对顺序跟在后面，这样首次使用时仍是用户配置的优先级。排在前面也只是"先试"，
     * 失败照样落到下一个。
     */
    @Synchronized
    fun rank(candidates: List<String>): List<String> {
        if (candidates.size < 2) return candidates
        val measured = candidates.mapNotNull { host ->
            estimateRate(host)?.takeIf { it > 0.0 }?.let { rate -> host to rate }
        }
        // 至少两个有测量才谈得上排序，否则保持用户配置的顺序
        if (measured.size < 2) return candidates
        val ordered = measured.sortedByDescending { it.second }.map { it.first }
        if (ordered == candidates) return candidates
        val measuredHosts = measured.mapTo(HashSet()) { it.first }
        return ordered + candidates.filterNot { it in measuredHosts }
    }

    @Synchronized
    fun clear() = hosts.clear()
}
