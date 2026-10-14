package io.github.bbzq.feats.media

import java.util.concurrent.Executors

/**
 * 已取回的媒体块的内存缓存，并负责提前取下一块。
 *
 * 播放器是按顺序读的：当前块读完必然来要下一块。提前把下一块取回来，块边界那次请求
 * 就能零等待命中内存，而不是再等一个往返。这是 Web 端 thread-ripper 的 read-ahead
 * 在本地代理里的对应物。
 *
 * 容量按**总字节数**封顶并按 LRU 淘汰，不按条目数：块有大有小，按条数会在块变大时
 * 悄悄吃掉几百兆。默认 24 MiB，约等于 4 MiB 一块的六块。
 *
 * 取数据的动作**不在锁内**：那是秒级的网络操作，持锁会把同时到达的其他请求全堵住。
 */
object MediaPrefetchCache {

    private const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
    private const val MAX_IN_FLIGHT = 2

    private class Entry(val start: Long, val bytes: ByteArray, val total: Long?)

    /** 访问顺序的 LRU：get 也算一次访问。 */
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    private var cachedBytes = 0L
    private val inFlight = HashSet<String>()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bbzq-media-prefetch").apply { isDaemon = true }
    }

    /**
     * 缓存里是否完整覆盖了 `[start, end]`。命中时返回对应的一段。
     *
     * 只返回被覆盖的部分：播放器可能只要其中一小段，多给反而不合规。
     */
    @Synchronized
    fun get(url: String, start: Long, end: Long): Pair<ByteArray, Long?>? {
        val entry = entries[key(url, start)] ?: return null
        if (start < entry.start) return null
        val offset = (start - entry.start).toInt()
        val length = (end - start + 1).toInt()
        if (offset < 0 || offset + length > entry.bytes.size) return null
        return entry.bytes.copyOfRange(offset, offset + length) to entry.total
    }

    /**
     * 后台把 `[start, start + length - 1]` 取回来存着。
     *
     * 已经缓存过或正在取的直接跳过，避免同一个块被重复拉。
     */
    fun prefetch(sources: List<String>, url: String, start: Long, length: Long, total: Long?) {
        if (length <= 0L || length > MAX_TOTAL_BYTES) return
        if (sources.isEmpty()) return
        val key = key(url, start)
        synchronized(this) {
            if (entries.containsKey(key) || key in inFlight) return
            if (inFlight.size >= MAX_IN_FLIGHT) return
            inFlight += key
        }
        worker.execute {
            try {
                val (bytes, meta) = MediaRangeFetcher.fetch(
                    sources,
                    MediaProxyProtocol.Range(start, start + length - 1),
                    total,
                )
                synchronized(this) { put(key, Entry(start, bytes, meta.total)) }
            } catch (t: Throwable) {
                // 预读失败无所谓：播放器来要时会走正常路径重取
            } finally {
                synchronized(this) { inFlight -= key }
            }
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        cachedBytes = 0L
    }

    /** 调用方须持有本对象的锁。 */
    private fun put(key: String, entry: Entry) {
        entries.put(key, entry)?.let { cachedBytes -= it.bytes.size }
        cachedBytes += entry.bytes.size
        val iterator = entries.entries.iterator()
        while (cachedBytes > MAX_TOTAL_BYTES && iterator.hasNext()) {
            cachedBytes -= iterator.next().value.bytes.size
            iterator.remove()
        }
    }

    private fun key(url: String, start: Long): String = "$start@$url"
}
