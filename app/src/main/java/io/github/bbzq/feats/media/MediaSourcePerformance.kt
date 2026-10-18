package io.github.bbzq.feats.media

import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 仅使用实际播放请求的观测值，不发送测速流量；短期学习与下载测速相互独立。 */
internal class RecentMediaSourcePerformance(private val clock: () -> Long = System::nanoTime) {
    private data class Sample(var firstByteNanos: Double, var bytesPerNano: Double, var at: Long)
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
        if (epoch != generation || bytes < 64 * 1024 || firstByteNanos < 0 || transferNanos <= 0) return
        val now = clock()
        val key = key(url) ?: return
        val old = samples[key]?.takeIf { now - it.at < MAX_AGE }
        val rate = bytes.toDouble() / transferNanos
        if (old == null) samples[key] = Sample(firstByteNanos.toDouble(), rate, now)
        else {
            old.firstByteNanos += 0.25 * (firstByteNanos - old.firstByteNanos)
            old.bytesPerNano += 0.25 * (rate - old.bytesPerNano)
            old.at = now
        }
    }

    /** 按整个区间的预计完成时间选源，不能只看首字节或把未知源当成快源。 */
    @Synchronized fun rankForRange(urls: List<String>, bytes: Long, speculative: Boolean): List<String> {
        val now = clock()
        val estimates = urls.map { url -> url to sample(url, now)?.eta(bytes) }
        val ranked = estimates.sortedBy { it.second ?: Double.POSITIVE_INFINITY }.map { it.first }
        val best = estimates.mapNotNull { it.second }.minOrNull()
        // 慢线路的下一块预读兼作学习：仍读取实际播放数据，不另发测速请求。
        // 当前播放不为未知节点让路，已足够快的线路也不必付出探索成本。
        if (speculative && best != null && best >= TimeUnit.SECONDS.toNanos(1)) {
            val unknown = estimates.firstOrNull { it.second == null }?.first
            if (unknown != null) return listOf(unknown) + ranked.filter { it != unknown }
        }
        return ranked
    }

    /** 前台尾片只交给预计耗时接近的已测源，避免未知慢节点堵住顺序供流。 */
    @Synchronized fun transferCandidates(urls: List<String>, bytes: Long): List<String> {
        val now = clock()
        val measured = urls.mapNotNull { url -> sample(url, now)?.let { url to it.eta(bytes) } }
            .sortedBy { it.second }
        val best = measured.firstOrNull()?.second ?: return urls.take(1)
        return measured.filter { it.second <= best * 1.25 }.map { it.first }
    }

    /** 好线路上一块很快就能传完时，避免为拆分再付一次首字节和线程调度开销。 */
    @Synchronized fun shouldParallel(urls: List<String>, bytes: Long): Boolean {
        val now = clock()
        val best = urls.mapNotNull { sample(it, now) }.minByOrNull { it.eta(bytes) } ?: return false
        return urls.size > 1 && best.eta(bytes) >= TimeUnit.MILLISECONDS.toNanos(400) &&
            bytes >= (2 * best.bytesPerNano * best.firstByteNanos).coerceIn(1024.0 * 1024, 4.0 * 1024 * 1024)
    }

    @Synchronized fun hedgeDelayMs(url: String): Long =
        sample(url, clock())?.let { (it.firstByteNanos * 2 / 1_000_000).toLong().coerceIn(250, 1000) } ?: 350

    @Synchronized fun stallTimeoutMs(url: String): Long =
        sample(url, clock())?.let { (it.firstByteNanos * 4 / 1_000_000).toLong().coerceIn(1500, 3000) } ?: 2000

    /** 只有另一条线路已经实测可用，才用它的保守速率判断持续慢传。 */
    @Synchronized fun rescueRate(url: String, alternatives: List<String>): Double =
        alternatives.filter { key(it) != key(url) }.mapNotNull { sample(it, clock())?.bytesPerNano }
            .maxOrNull()?.times(0.35) ?: 0.0

    private fun sample(url: String, now: Long): Sample? =
        key(url)?.let { samples[it] }?.takeIf { now - it.at < MAX_AGE }

    private fun Sample.eta(bytes: Long): Double = firstByteNanos + bytes / bytesPerNano

    private fun key(url: String): String? = runCatching {
        URI(url).rawAuthority?.lowercase(Locale.ROOT)
    }.getOrNull()

    companion object {
        private val MAX_AGE = TimeUnit.SECONDS.toNanos(30)
    }
}

internal val MediaSourcePerformance = RecentMediaSourcePerformance()
