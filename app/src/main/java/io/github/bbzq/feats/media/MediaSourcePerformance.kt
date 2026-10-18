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

    @Synchronized fun rank(urls: List<String>, firstByte: Boolean): List<String> {
        if (urls.size < 2) return urls
        val now = clock()
        val measured = urls.map { url -> key(url)?.let { samples[it] }?.takeIf { now - it.at < MAX_AGE } }
        val best = if (firstByte) measured.mapNotNull { it?.firstByteNanos }.minOrNull()
            else measured.mapNotNull { it?.bytesPerNano }.maxOrNull()
        if (best == null) return urls
        // 未测节点留在已知慢节点前面，仍有机会通过实际分片获得样本。
        val unknown = if (firstByte) best * 1.2 else best / 1.2
        return urls.indices.sortedBy { index ->
            val value = if (firstByte) measured[index]?.firstByteNanos else measured[index]?.bytesPerNano
            if (firstByte) value ?: unknown else -(value ?: unknown)
        }.map(urls::get)
    }

    /** 明显慢于最快节点的已测源只保留作故障回退，避免它固定承担中间片拖住顺序输出。 */
    @Synchronized fun transferCandidates(urls: List<String>): List<String> {
        val ranked = rank(urls, firstByte = false)
        val now = clock()
        val rates = ranked.map { url -> key(url)?.let { samples[it] }?.takeIf { now - it.at < MAX_AGE }?.bytesPerNano }
        val best = rates.filterNotNull().maxOrNull() ?: return ranked
        return ranked.filterIndexed { index, _ -> rates[index]?.let { it >= best * 0.5 } ?: true }
    }

    private fun key(url: String): String? = runCatching {
        URI(url).rawAuthority?.lowercase(Locale.ROOT)
    }.getOrNull()

    companion object { private val MAX_AGE = TimeUnit.SECONDS.toNanos(30) }
}

internal val MediaSourcePerformance = RecentMediaSourcePerformance()
