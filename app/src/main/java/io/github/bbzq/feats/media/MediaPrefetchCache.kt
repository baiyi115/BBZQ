package io.github.bbzq.feats.media

import io.github.bbzq.feats.RangeRequestBudget
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 前台与预读共享同一个正在增长的块；读者只读取已经发布、不会再修改的数组前缀。 */
object MediaPrefetchCache {
    private val cache = ProgressiveMediaCache { sources, range, total, budget, headers, sink ->
        MediaRangeFetcher.stream(sources, range, total, budget, headers, sink)
    }

    internal fun acquire(sources: List<String>, url: String, range: MediaProxyProtocol.Range, total: Long?) =
        cache.acquire(sources, url, range, total)

    fun prefetch(sources: List<String>, url: String, start: Long, length: Long, total: Long?) {
        if (length !in 1..MediaProxyProtocol.CHUNK_BYTES || start < 0 || start > Long.MAX_VALUE - length) return
        cache.prefetch(sources, url, MediaProxyProtocol.Range(start, start + length - 1), total)
    }

    fun clear() = cache.clear()
}

internal typealias MediaBlockLoader = (
    List<String>, MediaProxyProtocol.Range, Long?, RangeRequestBudget,
    (MediaRangeFetcher.Meta) -> Unit, (ByteArray, Int, Int) -> Unit,
) -> Unit

/** 无 Android 依赖，可用受控的流式 loader 验证并发、内存计账和取消。 */
internal class ProgressiveMediaCache(
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val timeoutMs: Long = 30_000,
    private val maxEntries: Int = 64,
    private val loader: MediaBlockLoader,
) : Closeable {
    init { require(maxBytes > 0 && maxEntries > 0) }

    private val lock = Object()
    private val entries = LinkedHashMap<Long, Entry>(16, 0.75f, true)
    private var nextId = 0L
    private var reservedBytes = 0L
    private var activeLoads = 0
    private var speculativeLoads = 0
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, ArrayBlockingQueue(3), { runnable ->
        Thread(runnable, "bbzq-media-block").apply { isDaemon = true }
    })

    internal class Entry(
        val id: Long, val url: String, val range: MediaProxyProtocol.Range,
        var speculative: Boolean, val budget: RangeRequestBudget,
    ) {
        val reserved = MediaProxyProtocol.length(range)
        var meta: MediaRangeFetcher.Meta? = null
        var bytes: ByteArray? = null
        var available = 0
        var readers = 0
        var done = false
        var workerDone = false
        var resident = true
        var accounted = true
        var failure: Exception? = null
    }

    internal inner class Lease internal constructor(private val entry: Entry, private val requested: MediaProxyProtocol.Range) : Closeable {
        private var closed = false
        fun stream(onReady: (MediaRangeFetcher.Meta) -> Unit, sink: (ByteArray, Int, Int) -> Unit): MediaRangeFetcher.Meta {
            val meta = synchronized(lock) {
                while (entry.meta == null) {
                    checkOpen()
                    await(entry)
                }
                checkOpen()
                val source = entry.meta!!
                val end = minOf(requested.end!!, source.end)
                if (end < requested.start) throw IOException("range is beyond EOF")
                MediaRangeFetcher.Meta(requested.start, end, source.total, source.contentType)
            }
            onReady(meta)
            var offset = (requested.start - entry.range.start).toInt()
            val limit = (meta.end - entry.range.start + 1).toInt()
            while (offset < limit) {
                val chunk = synchronized(lock) {
                    while (entry.available <= offset) {
                        checkOpen()
                        if (entry.done) throw IOException("incomplete media block")
                        await(entry)
                    }
                    checkOpen()
                    entry.bytes!! to minOf(entry.available - offset, limit - offset, 64 * 1024)
                }
                sink(chunk.first, offset, chunk.second)
                offset += chunk.second
            }
            return meta
        }

        private fun checkOpen() {
            if (closed) throw IOException("media reader closed")
            entry.failure?.let { throw IOException("media block failed", it) }
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                entry.readers--
                // 最后一批字节发布后，loader 仍可能在退出/健康统计阶段；不能误取消完整缓存。
                val bodyComplete = entry.bytes?.let { entry.available == it.size } == true
                if (entry.readers == 0 && !entry.speculative && !entry.done && !bodyComplete) {
                    discard(entry, IOException("media reader left"))
                }
                releaseIfUnused(entry)
                lock.notifyAll()
            }
        }
    }

    fun acquire(sources: List<String>, url: String, range: MediaProxyProtocol.Range, total: Long?): Lease = synchronized(lock) {
        val entry = find(url, range) ?: run {
            // Seek 后旧位置的在途预读不再抢占当前播放；完成的缓存仍可供回退使用。
            entries.values.filter { it.url == url && it.speculative && !it.workerDone && it.readers == 0 }
                .forEach { discard(it, IOException("media prefetch superseded")) }
            create(sources, url, range, total, false)
        }
        if (entry.speculative && !entry.workerDone) speculativeLoads--
        entry.speculative = false
        entry.budget.promoteToForeground()
        entry.readers++
        lease(entry, range)
    }

    private fun lease(entry: Entry, range: MediaProxyProtocol.Range): Lease = Lease(entry, range)

    fun prefetch(sources: List<String>, url: String, range: MediaProxyProtocol.Range, total: Long?) {
        synchronized(lock) {
            if (speculativeLoads >= 1 || activeLoads >= 2 || find(url, range) != null) return
            // 预读是可丢弃的；不排无限任务，也不给播放器线程制造异常。
            try { create(sources, url, range, total, true) } catch (_: IOException) { }
        }
    }

    private fun find(url: String, range: MediaProxyProtocol.Range): Entry? {
        val entry = entries.values.firstOrNull { entry ->
            entry.failure == null && entry.url == url && entry.range.start <= range.start &&
                (entry.range.end!! >= range.end!! ||
                    (entry.meta?.let { it.total == it.end + 1 && it.end >= range.start } == true))
        } ?: return null
        return entries[entry.id] // 刷新 LRU；包含关系命中不要求起点完全一致。
    }

    private fun create(sources: List<String>, url: String, range: MediaProxyProtocol.Range, total: Long?, speculative: Boolean): Entry {
        val length = range.end?.let { it - range.start + 1 } ?: 0
        if (sources.isEmpty() || range.start < 0 || length !in 1..MediaProxyProtocol.CHUNK_BYTES) throw IOException("invalid cache range")
        if (activeLoads >= 6) throw IOException("media loader busy")
        val iterator = entries.values.iterator()
        while ((reservedBytes + length > maxBytes || entries.size >= maxEntries) && iterator.hasNext()) {
            val old = iterator.next()
            if (old.workerDone && old.readers == 0) {
                iterator.remove()
                old.resident = false
                releaseIfUnused(old)
            }
        }
        if (reservedBytes + length > maxBytes || entries.size >= maxEntries) throw IOException("media cache busy")
        val entry = Entry(++nextId, url, range, speculative, RangeRequestBudget(timeoutMs, speculative))
        entries[entry.id] = entry
        reservedBytes += length
        activeLoads++
        if (speculative) speculativeLoads++
        try {
            workers.execute { load(entry, sources, total) }
        } catch (e: Exception) {
            entry.workerDone = true
            activeLoads--
            if (entry.speculative) speculativeLoads--
            discard(entry, e)
            throw IOException("media loader rejected", e)
        }
        return entry
    }

    private fun load(entry: Entry, sources: List<String>, total: Long?) {
        try {
            entry.budget.remainingNanos()
            loader(sources, entry.range, total, entry.budget, { meta ->
                synchronized(lock) {
                    entry.budget.remainingNanos()
                    if (meta.start != entry.range.start || meta.length !in 1..entry.reserved || entry.meta != null) {
                        throw IOException("invalid media metadata")
                    }
                    entry.meta = meta
                    entry.bytes = ByteArray(meta.length.toInt())
                    lock.notifyAll()
                }
            }) { bytes, offset, length ->
                synchronized(lock) {
                    entry.budget.remainingNanos()
                    val target = entry.bytes ?: throw IOException("media body before headers")
                    if (length > target.size - entry.available) throw IOException("media body too long")
                    bytes.copyInto(target, entry.available, offset, offset + length)
                    entry.available += length
                    lock.notifyAll()
                }
            }
            synchronized(lock) {
                if (entry.bytes == null || entry.available != entry.bytes!!.size) throw IOException("short media body")
                entry.done = true
            }
        } catch (e: Exception) {
            synchronized(lock) { discard(entry, e) }
        } finally {
            entry.budget.cancel()
            synchronized(lock) {
                entry.workerDone = true
                activeLoads--
                if (entry.speculative) speculativeLoads--
                releaseIfUnused(entry)
                lock.notifyAll()
            }
        }
    }

    private fun await(entry: Entry) {
        val remaining = entry.budget.remainingNanos()
        try {
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("media reader interrupted").apply { initCause(e) }
        }
    }

    private fun discard(entry: Entry, reason: Exception) {
        entry.failure = reason
        entry.done = true
        if (entry.resident) entries.remove(entry.id)
        entry.resident = false
        entry.budget.cancel()
        releaseIfUnused(entry)
        lock.notifyAll()
    }

    private fun releaseIfUnused(entry: Entry) {
        if (!entry.resident && entry.workerDone && entry.readers == 0 && entry.accounted) {
            entry.accounted = false
            reservedBytes -= entry.reserved
            entry.bytes = null
        }
    }

    fun clear() = synchronized(lock) {
        entries.values.toList().forEach { discard(it, IOException("media cache cleared")) }
    }

    internal fun reservedByteCount(): Long = synchronized(lock) { reservedBytes }
    internal fun activeLoadCount(): Int = synchronized(lock) { activeLoads }

    override fun close() {
        clear()
        workers.shutdown() // 已取消的排队任务仍运行 finally，归还预留容量。
    }
}
