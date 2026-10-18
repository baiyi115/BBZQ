package io.github.bbzq.feats.media

import io.github.bbzq.feats.HttpRange
import io.github.bbzq.feats.RangeRequestBudget
import io.github.bbzq.feats.download.DownloadHealth
import io.github.bbzq.feats.download.PiecePlanning
import io.github.bbzq.feats.hook.CustomCdnProcessor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 小首段直接流出，尾片有界并行、按序渐进交付；排队和回源共用块预算。 */
object MediaRangeFetcher {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val pool by lazy {
        ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8), { runnable ->
            Thread(runnable, "bbzq-media-piece").apply { isDaemon = true }
        })
    }
    private const val LEAD_BYTES = 256L * 1024
    private val tailPolicy = PiecePlanning.MEDIA_PROXY.copy(maxPieces = 3)

    class Meta(val start: Long, val end: Long, val total: Long?, val contentType: String?) {
        val length: Long get() = end - start + 1
    }

    private class Tail(val piece: PiecePlanning.Piece, val buffer: MediaPieceBuffer, var future: Future<*>? = null)
    private class Representation(val total: Long?, val entityTag: String?)

    internal fun stream(
        sources: List<String>, range: MediaProxyProtocol.Range, knownTotal: Long?,
        budget: RangeRequestBudget, onReady: (Meta) -> Unit, sink: (ByteArray, Int, Int) -> Unit,
    ) {
        val epoch = MediaSourcePerformance.epoch()
        val distinct = MediaSourcePerformance.rank(sources.distinct(), firstByte = true)
        if (distinct.isEmpty()) throw IOException("no media source available")
        val end = range.end ?: throw IOException("unresolved media range")
        val length = MediaProxyProtocol.length(range)
        if (range.start < 0 || length !in 1..MediaProxyProtocol.CHUNK_BYTES) throw IOException("invalid media range")
        // 冷却节点只作回退，不能因轮转分片又把它提升成某片的首选。
        val ready = distinct.filterNot { DownloadHealth.isHostCoolingDown(CustomCdnProcessor.hostOf(it)) }
        val primaries = ready.ifEmpty { distinct.take(1) }
        var tailPrimaries = primaries
        val canParallel = !budget.isSpeculative && primaries.size > 1 && length >= 2 * PiecePlanning.MIN_PROXY_PIECE_BYTES
        val leadEnd = if (canParallel) range.start + LEAD_BYTES - 1 else end
        val failedSources = ConcurrentHashMap.newKeySet<String>()
        val tails = ArrayList<Tail>(3)
        var representation: Representation? = null
        try {
            readWithFallback(distinct, primaries, PiecePlanning.Piece(0, range.start, leadEnd), knownTotal, null, budget, epoch, failedSources, { response ->
                val total = response.total ?: knownTotal
                representation = Representation(total, response.strongEntityTag)
                val tailReady = primaries.filterNot {
                    it in failedSources || DownloadHealth.isHostCoolingDown(CustomCdnProcessor.hostOf(it))
                }
                tailPrimaries = MediaSourcePerformance.transferCandidates(tailReady.ifEmpty { primaries })
                // 首段学到总长后，冷起播也可以并行；总长仍未知时只承诺已经验证的首段。
                val outputEnd = if (canParallel && total != null) minOf(end, total - 1) else response.end
                onReady(Meta(range.start, outputEnd, total, response.contentType))
                if (response.end < outputEnd) {
                    val start = response.end + 1
                    val remaining = outputEnd - response.end
                    val pieces = PiecePlanning.plan(start, remaining, tailPrimaries.size, tailPolicy)
                        ?: listOf(PiecePlanning.Piece(0, start, outputEnd))
                    pieces.forEach { piece ->
                        val tail = Tail(piece, MediaPieceBuffer(budget))
                        tails += tail
                        try {
                            tail.future = pool.submit {
                                var error: Exception? = null
                                try {
                                    readWithFallback(distinct, tailPrimaries, tail.piece, total, representation, budget, epoch, failedSources, {}, tail.buffer::write)
                                } catch (e: Exception) { error = e }
                                finally { tail.buffer.finish(error) }
                            }
                        } catch (_: RejectedExecutionException) {
                            // 资源繁忙时按序在当前加载线程取这片，不让线程池拒绝破坏已提交响应。
                        }
                    }
                }
            }, sink)
            tails.forEach { tail ->
                if (tail.future == null) {
                    readWithFallback(distinct, tailPrimaries, tail.piece, representation?.total, representation, budget, epoch, failedSources, {}, sink)
                } else {
                    tail.buffer.streamTo(sink)
                }
            }
        } finally {
            budget.cancel()
            tails.forEach { it.buffer.cancel(); it.future?.cancel(true) }
            if (tails.isNotEmpty()) pool.purge()
        }
    }

    /** 测试/离线使用：只分配实际响应长度，不返回补零的数组尾部。 */
    fun fetch(sources: List<String>, range: MediaProxyProtocol.Range, knownTotal: Long?): Pair<ByteArray, Meta> {
        var meta: Meta? = null
        var bytes = ByteArray(0)
        var offset = 0
        stream(sources, range, knownTotal, RangeRequestBudget(), onReady = {
            meta = it
            bytes = ByteArray(it.length.toInt())
        }) { buffer, from, size ->
            buffer.copyInto(bytes, offset, from, from + size)
            offset += size
        }
        return bytes to (meta ?: throw IOException("no media metadata"))
    }

    private fun readWithFallback(
        sources: List<String>, primaries: List<String>, piece: PiecePlanning.Piece,
        knownTotal: Long?, representation: Representation?, budget: RangeRequestBudget, epoch: Long,
        failedSources: MutableSet<String>,
        onHeaders: (HttpRange.Response) -> Unit, write: (ByteArray, Int, Int) -> Unit,
    ) {
        val ranked = primaries + sources.filter { it !in primaries }
        val (ready, fallback) = ranked.partition {
            it !in failedSources && !DownloadHealth.isHostCoolingDown(CustomCdnProcessor.hostOf(it))
        }
        val assigned = primaries[piece.index % primaries.size]
        val preferred = assigned.takeIf { it in ready } ?: ready.firstOrNull() ?: assigned
        val ordered = listOf(preferred) + (ready + fallback).filter { it != preferred }
        var delivered = 0L
        var responseEnd = piece.end
        var expectedTotal = representation?.total ?: knownTotal
        var entityTag = representation?.entityTag
        var published = false
        var last: IOException? = null
        for (source in ordered) {
            budget.remainingNanos()
            var downstreamFailure = false
            val started = System.nanoTime()
            val before = delivered
            var firstByte = 0L
            var sinkNanos = 0L
            try {
                HttpRange.read(client, source, piece.start + delivered, responseEnd, onHeaders = { meta ->
                    if (expectedTotal != null && expectedTotal != meta.total) throw IOException("media size changed between pieces")
                    if (entityTag != null && entityTag != meta.strongEntityTag) throw IOException("media entity changed between pieces")
                    if ((knownTotal != null || published) && meta.end != responseEnd) throw IOException("unexpected clipped piece")
                    if (!published) {
                        expectedTotal = meta.total ?: knownTotal
                        entityTag = meta.strongEntityTag ?: entityTag
                        responseEnd = meta.end
                        published = true
                        try { onHeaders(meta) } catch (e: Exception) { downstreamFailure = true; throw e }
                    }
                }, budget = budget, ifRange = entityTag,
                    firstByteTimeoutMs = if (ordered.size > 1) 2500 else 0) { buffer, from, size ->
                    val now = System.nanoTime()
                    if (firstByte == 0L) firstByte = now
                    try { write(buffer, from, size) } catch (e: Exception) { downstreamFailure = true; throw e }
                    finally { sinkNanos += System.nanoTime() - now }
                    delivered += size
                }
                MediaSourcePerformance.record(epoch, source, firstByte - started,
                    System.nanoTime() - started - sinkNanos, delivered - before)
                DownloadHealth.recordHostSuccess(CustomCdnProcessor.hostOf(source))
                return
            } catch (e: IOException) {
                budget.remainingNanos()
                if (downstreamFailure) throw e
                failedSources += source
                DownloadHealth.recordHostFailure(CustomCdnProcessor.hostOf(source))
                // 只有强 ETag 能验证续传是同一内容；从已交付位置继续，绝不重放前缀。
                if (published && (entityTag == null || expectedTotal == null)) throw e
                if (delivered == responseEnd - piece.start + 1) return
                last = e
            }
        }
        throw IOException("piece ${piece.index} failed on every source", last)
    }
}
