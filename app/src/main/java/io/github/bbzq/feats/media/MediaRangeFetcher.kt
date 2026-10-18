package io.github.bbzq.feats.media

import io.github.bbzq.feats.HttpRange
import io.github.bbzq.feats.RangeRequestBudget
import io.github.bbzq.feats.download.DownloadHealth
import io.github.bbzq.feats.download.PiecePlanning
import io.github.bbzq.feats.hook.CustomCdnProcessor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 流式供流；值得拆分时才启用小首段和有界尾片，全部回源共用块预算。 */
object MediaRangeFetcher {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val pool by lazy {
        ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(), { runnable ->
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
    private class RepresentationMismatch(message: String) : IOException(message)

    internal fun stream(
        sources: List<String>, range: MediaProxyProtocol.Range, knownTotal: Long?,
        budget: RangeRequestBudget, onReady: (Meta) -> Unit, sink: (ByteArray, Int, Int) -> Unit,
    ) {
        val end = range.end ?: throw IOException("unresolved media range")
        val length = MediaProxyProtocol.length(range)
        val epoch = MediaSourcePerformance.epoch()
        val distinct = sources.distinct()
        if (distinct.isEmpty()) throw IOException("no media source available")
        if (range.start < 0 || length !in 1..MediaProxyProtocol.CHUNK_BYTES) throw IOException("invalid media range")
        // 冷却节点只作回退，不能因轮转分片又把它提升成某片的首选。
        val ready = distinct.filterNot { DownloadHealth.isHostCoolingDown(CustomCdnProcessor.hostOf(it)) }
        val ranked = MediaSourcePerformance.rankForRange(ready.ifEmpty { distinct }, length, budget.isSpeculative)
        val primaries = if (ready.isEmpty()) ranked.take(1) else ranked
        var tailPrimaries = MediaSourcePerformance.transferCandidates(primaries, length / 2)
        val canParallel = !budget.isSpeculative && length >= 2 * PiecePlanning.MIN_PROXY_PIECE_BYTES &&
            MediaSourcePerformance.shouldParallel(tailPrimaries, length)
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
                tailPrimaries = MediaSourcePerformance.transferCandidates(tailReady.ifEmpty { primaries }, (length - LEAD_BYTES) / 2)
                // 总长和强 ETag 均确认后才跨源拆片，避免拼接同长度的不同表示。
                val outputEnd = if (canParallel && total != null && response.strongEntityTag != null) minOf(end, total - 1) else response.end
                onReady(Meta(range.start, outputEnd, total, response.contentType))
                if (response.end < outputEnd) {
                    val start = response.end + 1
                    val remaining = outputEnd - response.end
                    val pieces = PiecePlanning.plan(start, remaining, tailPrimaries.size, tailPolicy)
                        ?: listOf(PiecePlanning.Piece(0, start, outputEnd))
                    pieces.forEachIndexed { index, piece ->
                        val tail = Tail(piece, MediaPieceBuffer(budget))
                        tails += tail
                        // 当前马上要消费的片留在加载线程；远端片才用后台槽，避免关键片排队。
                        if (index == 0) return@forEachIndexed
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
        val attempted = HashSet<String>()
        val slowSources = HashSet<String>()
        val slowFallbacks = LinkedHashSet<String>()
        while (attempted.size < ordered.size || slowFallbacks.isNotEmpty()) {
            budget.remainingNanos()
            val available = ordered.filter { it !in attempted }
            val source = available.firstOrNull {
                it !in failedSources && !DownloadHealth.isHostCoolingDown(CustomCdnProcessor.hostOf(it))
            } ?: available.firstOrNull() ?: slowFallbacks.first().also { slowFallbacks.remove(it) }
            val requestFailures = HashSet<String>()
            // 冷却源仅在前面的候选都失败后顺序尝试，不额外抢占竞速槽。
            val alternative = available.firstOrNull {
                it != source && it !in failedSources && !DownloadHealth.isHostCoolingDown(CustomCdnProcessor.hostOf(it))
            }
            var downstreamFailure = false
            try {
                MediaRangeRequest.read(client, source, alternative, available,
                    piece.start + delivered, responseEnd, entityTag, budget, epoch, slowSources,
                    validate = { meta ->
                        if (expectedTotal != null && expectedTotal != meta.total) throw RepresentationMismatch("media size changed between pieces")
                        if (entityTag != null && entityTag != meta.strongEntityTag) throw RepresentationMismatch("media entity changed between pieces")
                        if ((knownTotal != null || published) && meta.end != responseEnd) throw IOException("unexpected clipped piece")
                    }, onHeaders = { meta ->
                        if (!published) {
                            expectedTotal = meta.total ?: knownTotal
                            entityTag = meta.strongEntityTag ?: entityTag
                            responseEnd = meta.end
                            published = true
                            try { onHeaders(meta) } catch (e: Exception) { downstreamFailure = true; throw e }
                        }
                    }, onFailure = { url, failure ->
                        if (!downstreamFailure) {
                            requestFailures += url
                            if (failure is MediaRangeRequest.SlowSource) {
                                // 历史速率仅作换源提示，不能把仍在供流的 CDN 判成故障。
                                // 备源可能使用不同 ETag；全部不兼容时允许原源续传一次。
                                if (slowSources.add(url)) slowFallbacks += url
                            } else {
                                slowFallbacks.remove(url)
                                failedSources += url
                                // 表示不兼容只对当前块无效，不代表整台 CDN 网络故障。
                                if (failure !is RepresentationMismatch) {
                                    DownloadHealth.recordHostFailure(CustomCdnProcessor.hostOf(url))
                                }
                            }
                        }
                    }) { bytes, offset, count ->
                    try { write(bytes, offset, count) } catch (e: Exception) { downstreamFailure = true; throw e }
                    delivered += count
                }
                return
            } catch (e: IOException) {
                budget.remainingNanos()
                if (downstreamFailure) throw e
                // 已开始交付的区间只能用强 ETag 续传，竞速也不能放松内容一致性检查。
                if (published && (entityTag == null || expectedTotal == null)) throw e
                if (delivered == responseEnd - piece.start + 1) return
                val previousAttempts = attempted.size
                attempted += requestFailures
                // 被竞速取消的主源没有失败，仍可接替随后出错的胜出源。
                if (attempted.size == previousAttempts) attempted += source
                last = e
            }
        }
        throw IOException("piece ${piece.index} failed on every source", last)
    }
}
