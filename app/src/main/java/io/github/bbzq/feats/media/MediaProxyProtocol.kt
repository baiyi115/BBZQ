package io.github.bbzq.feats.media

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 本地媒体代理的请求/响应格式。
 *
 * 播放器拿到的地址形如 `http://127.0.0.1:<port>/media?u=<编码后的原始地址>`。
 * 原始地址带签名（`upsig` / `deadline` 等），必须原样带过来，所以整串编码后放进
 * 查询参数，而不是拆开路径与参数。
 *
 * 这里只做纯解析与拼接，不碰 socket，便于单测。
 */
object MediaProxyProtocol {

    const val PATH = "/media"
    const val QUERY_PARAM = "u"

    /**
     * 播放器发来的 Range 请求。
     *
     * [end] 为 `null` 表示开放式区间（`bytes=1024-`），播放器流式播放时基本都这样：
     * 它只说"从哪开始"，由服务端决定给多少。代理因此按 [CHUNK_BYTES] 一块一块给。
     */
    data class Range(val start: Long, val end: Long?)

    /** 单次响应最多给多少字节。播放器会接着请求下一块。 */
    const val CHUNK_BYTES = 4L * 1024L * 1024L

    fun proxyUrl(port: Int, originalUrl: String): String =
        "http://127.0.0.1:$port$PATH?$QUERY_PARAM=${encode(originalUrl)}"

    /** 从请求行里取回原始地址；不是本代理的请求或解析失败时返回 `null`。 */
    fun originalUrlOf(path: String, query: String?): String? {
        if (path != PATH) return null
        if (query.isNullOrEmpty()) return null
        val encoded = query.split('&')
            .firstOrNull { it.startsWith("$QUERY_PARAM=") }
            ?.substringAfter('=')
            ?: return null
        val decoded = runCatching { decode(encoded) }.getOrNull() ?: return null
        // 只接受 http(s)，避免被当成任意本地请求的跳板
        return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /**
     * 解析 `Range: bytes=a-b`。只接受单段区间：多段没法用一次 206 表达。
     *
     * 后缀形式（`bytes=-500`，"最后 500 字节"）返回 `null`：定位它需要先知道总长度，
     * 而调用方此时还没有，交给回源处理更简单。
     */
    fun parseRange(value: String?): Range? {
        val spec = value?.trim()?.takeIf { it.startsWith(RANGE_PREFIX, ignoreCase = true) }
            ?.substring(RANGE_PREFIX.length)
            ?: return null
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        if (startText.isEmpty()) return null
        val start = startText.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val end = if (endText.isEmpty()) null else endText.toLongOrNull()?.takeIf { it >= start } ?: return null
        return Range(start, end)
    }

    /**
     * 这次实际服务的区间。
     *
     * 一律封顶在 [CHUNK_BYTES]：播放器常发 `bytes=0-` 这种开放式区间，逐个满足会把
     * 整个文件读进内存；给少一点是 HTTP 允许的（206 里如实回报实际区间），播放器会
     * 接着请求下一块。已知总长度时再夹到文件末尾。
     */
    fun resolve(requested: Range?, total: Long?): Range {
        val start = requested?.start ?: 0L
        val wanted = requested?.end ?: Long.MAX_VALUE
        val capped = minOf(wanted, start + CHUNK_BYTES - 1)
        val end = if (total != null) minOf(capped, total - 1) else capped
        return Range(start, maxOf(end, start))
    }

    fun length(range: Range): Long = range.end!! - range.start + 1

    fun contentRange(range: Range, total: Long?): String =
        "bytes ${range.start}-${range.end}/${total ?: "*"}"

    fun contentLength(range: Range): String = length(range).toString()

    /** 把 `?u=` 里的值编码成查询参数安全的形式；整串编码，不保留 `/` 与 `:`。 */
    internal fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    internal fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private const val RANGE_PREFIX = "bytes="
}
