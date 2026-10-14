package io.github.bbzq.feats.media

import io.github.bbzq.feats.HttpRange
import io.github.bbzq.feats.download.DownloadHealth
import io.github.bbzq.feats.download.PiecePlanning
import io.github.bbzq.feats.hook.CustomCdnProcessor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 从多台 CDN 并发取一段字节，并**按序边到边交**给调用方。
 *
 * 这是播放加速的取数核心：把播放器要的一块区间切成若干片，分散到不同的源上同时取，
 * 切分复用 [PiecePlanning]（与下载器同一份逻辑，只是参数不同）。
 *
 * 关键在于不等整块取齐：第一片到手就回调 [onReady]，此后每片就绪即交给 [sink]。
 * 整块拼好再给的话，播放器要为最慢的那一片等满一个 RTT，起播和块边界都会卡。
 *
 * 成功与失败都记进 [DownloadHealth]，选源时
 * [io.github.bbzq.feats.download.DownloadSourcePlan] 会据此把反复失败的源排到最后。
 */
object MediaRangeFetcher {

    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 20L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val pool by lazy {
        Executors.newFixedThreadPool(PiecePlanning.MEDIA_PROXY.maxPieces * 2) { runnable ->
            Thread(runnable, "bbzq-media-fetch").apply { isDaemon = true }
        }
    }

    /** 首片就绪时可知的信息：文件总长度与内容类型。 */
    class Meta(val total: Long?, val contentType: String?)

    private class Fetched(val bytes: ByteArray, val total: Long?, val contentType: String?)

    /**
     * 取 [range] 这一段。区间须先经 [MediaProxyProtocol.resolve] 定下终点。
     *
     * [knownTotal] 为文件总长度，未知时传 `null`：**总长度未知就不切分**。片的终点会
     * 越过文件末尾，那几片在每台源上都会失败。第一次请求顺便学到总长度，之后的请求
     * 才会切。
     *
     * 调用顺序保证：[onReady] 恰好一次，且在第一个 [sink] 之前；[sink] 按片序调用，
     * 收到的字节拼起来就是这一段。
     *
     * 第一片失败时抛异常且**尚未回调过**，调用方还能整条降级（比如回 302）；
     * 之后失败时已经有字节交出去了，调用方只能断开这条连接。
     */
    fun stream(
        sources: List<String>,
        range: MediaProxyProtocol.Range,
        knownTotal: Long?,
        onReady: (Meta) -> Unit,
        sink: (ByteArray) -> Unit,
    ) {
        if (sources.isEmpty()) throw IOException("no media source available")
        val end = range.end ?: throw IOException("range must be resolved before fetching")
        val length = end - range.start + 1

        val single = listOf(PiecePlanning.Piece(0, range.start, end))
        val pieces = if (knownTotal == null) {
            single
        } else {
            PiecePlanning.plan(range.start, length, sources.size, PiecePlanning.MEDIA_PROXY) ?: single
        }

        // 全部片一起提交：后面的片在前面的片等待输出时就已经在取了
        val futures: List<Future<Fetched>> = pieces.map { piece ->
            pool.submit(Callable { fetchPieceWithFallback(sources, piece) })
        }

        var announced = false
        futures.forEach { future ->
            val fetched = try {
                future.get()
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
            // 按片序输出，所以每次拿到的就是下一片
            if (!announced) {
                announced = true
                onReady(Meta(fetched.total, fetched.contentType))
            }
            sink(fetched.bytes)
        }
    }

    /** 一次取齐，供预读使用。 */
    fun fetch(
        sources: List<String>,
        range: MediaProxyProtocol.Range,
        knownTotal: Long?,
    ): Pair<ByteArray, Meta> {
        var meta: Meta? = null
        val collected = ArrayList<ByteArray>()
        var size = 0
        stream(sources, range, knownTotal, onReady = { meta = it }) { bytes ->
            collected += bytes
            size += bytes.size
        }
        val joined = ByteArray(size)
        var offset = 0
        collected.forEach { chunk ->
            chunk.copyInto(joined, offset)
            offset += chunk.size
        }
        return joined to (meta ?: Meta(null, null))
    }

    /** 一片在所有源上都试过才放弃；从分配到的源开始轮一圈。 */
    private fun fetchPieceWithFallback(sources: List<String>, piece: PiecePlanning.Piece): Fetched {
        var lastError: Throwable? = null
        for (offset in sources.indices) {
            val source = sources[(piece.index + offset) % sources.size]
            try {
                val fetched = fetchPiece(source, piece)
                DownloadHealth.recordHostSuccess(CustomCdnProcessor.hostOf(source))
                return fetched
            } catch (failure: Throwable) {
                lastError = failure
                DownloadHealth.recordHostFailure(CustomCdnProcessor.hostOf(source))
            }
        }
        throw IOException("piece ${piece.index} failed on every source", lastError)
    }

    /** 取单独一片，一次性读进内存。206 契约与长度校验由 [HttpRange.read] 负责。 */
    private fun fetchPiece(url: String, piece: PiecePlanning.Piece): Fetched {
        if (piece.length > Int.MAX_VALUE) throw IOException("piece too large: ${piece.length}")
        val target = ByteArray(piece.length.toInt())
        var offset = 0
        val response = HttpRange.read(client, url, piece.start, piece.end) { buffer, from, length ->
            buffer.copyInto(target, offset, from, from + length)
            offset += length
        }
        return Fetched(target, response.total, response.contentType)
    }
}
