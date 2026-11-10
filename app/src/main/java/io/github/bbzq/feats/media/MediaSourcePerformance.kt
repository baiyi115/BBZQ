package io.github.bbzq.feats.media

import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 仅使用实际播放请求的观测值，不发送测速流量；短期学习与下载测速相互独立。 */
internal class RecentMediaSourcePerformance(private val clock: () -> Long = System::nanoTime) {
    private data class Sample(
        var firstByteNanos: Double, var bytesPerNano: Double?, var at: Long, var rateAt: Long,
    )
    private val samples = object : LinkedHashMap<String, Sample>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sample>): Boolean = size > 32
    }
    private var scope: Boolean? = null
    private var generation = 0L

    @Synchronized fun updateScope(cellular: Boolean) {
        if (scope != cellular) { scope = cellular; samples.clear(); generation++ }
    }
    @Synchronized fun epoch(): Long = generation
    @Synchronized fun clear() { samples.clear(); scope = null; generation++ }

    @Synchronized fun record(epoch: Long, url: String, firstByteNanos: Long, transferNanos: Long, bytes: Long) {
        if (epoch != generation || firstByteNanos < 0 || bytes < 0 || transferNanos < 0) return
        val now = clock()
        val key = key(url) ?: return
        val old = samples[key]?.takeIf { now - it.at < MAX_AGE }
        // 索引/小 Range 可提供真实首字节延迟，但不能据此推算持续吞吐。
        val rate = if (bytes >= 64 * 1024 && transferNanos > 0) bytes.toDouble() / transferNanos else null
        if (old == null) samples[key] = Sample(firstByteNanos.toDouble(), rate, now, now)
        else {
            old.firstByteNanos += 0.25 * (firstByteNanos - old.firstByteNanos)
            val previousRate = old.rate(now)
            old.bytesPerNano = if (rate == null) previousRate
                else previousRate?.let { it + 0.25 * (rate - it) } ?: rate
            if (rate != null) old.rateAt = now
            old.at = now
        }
    }

    /** 大区间按预计完成时间选源；小索引优先首字节，不把未知吞吐当成快源。 */
    @Synchronized fun rankForRange(urls: List<String>, bytes: Long, speculative: Boolean): List<String> {
        val now = clock()
        val estimates = urls.map { url ->
            val sample = sample(url, now)
            url to if (bytes <= 64 * 1024) sample?.firstByteNanos else sample?.eta(bytes, now)
        }
        val ranked = estimates.sortedBy { it.second ?: Double.POSITIVE_INFINITY }.map { it.first }
        val best = estimates.mapNotNull { it.second }.minOrNull()
        // 慢线路的下一块预读兼作学习：仍读取实际播放数据，不另发测速请求。
        if (speculative && best != null && best >= TimeUnit.SECONDS.toNanos(1)) {
            val unknown = estimates.firstOrNull { it.second == null }?.first
            if (unknown != null) return listOf(unknown) + ranked.filter { it != unknown }
        }
        return ranked
    }

    /** 尾片只交给吞吐已测且预计耗时接近的源，避免未知慢节点堵住顺序供流。 */
    @Synchronized fun transferCandidates(urls: List<String>, bytes: Long): List<String> {
        val now = clock()
        val measured = urls.mapNotNull { url -> sample(url, now)?.eta(bytes, now)?.let { url to it } }
            .sortedBy { it.second }
        val best = measured.firstOrNull()?.second ?: return urls.take(1)
        return measured.filter { it.second <= best * 1.25 }.map { it.first }
    }

    /** 好线路上一块很快就能传完时，避免为拆分再付一次首字节和线程调度开销。 */
    @Synchronized fun shouldParallel(urls: List<String>, bytes: Long): Boolean {
        val now = clock()
        val measured = urls.mapNotNull { sample(it, now)?.let { s -> s.eta(bytes, now)?.let { s to it } } }
        val (best, eta) = measured.minByOrNull { it.second } ?: return false
        val rate = best.rate(now) ?: return false
        return urls.size > 1 && eta >= TimeUnit.MILLISECONDS.toNanos(400) &&
            bytes >= (2 * rate * best.firstByteNanos).coerceIn(1024.0 * 1024, 4.0 * 1024 * 1024)
    }

    @Synchronized fun hedgeDelayMs(url: String): Long =
        sample(url, clock())?.let { (it.firstByteNanos * 2 / 1_000_000).toLong().coerceIn(250, 1000) } ?: 350

    @Synchronized fun stallTimeoutMs(url: String): Long =
        sample(url, clock())?.let { (it.firstByteNanos * 4 / 1_000_000).toLong().coerceIn(1500, 3000) } ?: 2000

    /** 只有另一条线路已经实测可用，才用它的保守速率判断持续慢传。 */
    @Synchronized fun rescueRate(url: String, alternatives: List<String>): Double {
        val now = clock()
        return alternatives.filter { key(it) != key(url) }.mapNotNull { sample(it, now)?.rate(now) }
            .maxOrNull()?.times(0.35) ?: 0.0
    }

    private fun sample(url: String, now: Long): Sample? =
        key(url)?.let { samples[it] }?.takeIf { now - it.at < MAX_AGE }

    private fun Sample.rate(now: Long): Double? = bytesPerNano?.takeIf { now - rateAt < MAX_AGE }
    private fun Sample.eta(bytes: Long, now: Long): Double? = rate(now)?.let { firstByteNanos + bytes / it }

    private fun key(url: String): String? = runCatching {
        URI(url).rawAuthority?.lowercase(Locale.ROOT)
    }.getOrNull()

    companion object {
        private val MAX_AGE = TimeUnit.SECONDS.toNanos(30)
    }
}

internal val MediaSourcePerformance = RecentMediaSourcePerformance()
