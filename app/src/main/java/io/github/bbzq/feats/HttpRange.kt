package io.github.bbzq.feats

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 单段 Range 请求的公共部分。
 *
 * 下载器与本地媒体代理都要"发一个 `bytes=a-b` 请求、确认服务端真的按区间回、再把这
 * 恰好 N 个字节交给某个目的"。区别只在落到哪里（文件偏移 vs 内存）。
 *
 * 把请求头、206 契约、长度校验与 `Content-Range` 解析收在这一处：这几条是正确性
 * 关键，散成几份迟早有一份会漏掉某个校验。
 */
internal object HttpRange {

    /** 这几个头两边都一样：不要压缩（Range 会失效）、要 Referer、用桌面 UA。 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36"

    private const val READ_BUFFER_BYTES = 64 * 1024

    /** 解析 CDN 返回的 `Content-Range: bytes a-b/total`。 */
    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    fun parseContentRange(value: String?): ContentRange? {
        val raw = value?.trim() ?: return null
        if (!raw.startsWith(CONTENT_RANGE_UNIT, ignoreCase = true)) return null
        // Content-Range 用空格分隔单位（`bytes 0-99/1000`），Range 请求头才用等号
        // （`bytes=0-99`）。两者格式不同，不要混用同一个前缀。
        val body = raw.substring(CONTENT_RANGE_UNIT.length).removePrefix("=").trim()
        val slash = body.lastIndexOf('/')
        if (slash < 0) return null
        val span = body.substring(0, slash).trim()
        val dash = span.indexOf('-')
        if (dash < 0) return null
        val start = span.substring(0, dash).trim().toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val end = span.substring(dash + 1).trim().toLongOrNull()?.takeIf { it >= start } ?: return null
        val total = body.substring(slash + 1).trim().toLongOrNull()?.takeIf { it > 0L }
        return ContentRange(start, end, total)
    }

    /** 只关心总长度时的简写。 */
    fun totalOf(value: String?): Long? = parseContentRange(value)?.total

    class Response(val total: Long?, val contentType: String?)

    /**
     * 请求 `[start, end]` 并把响应体交给 [write]。返回服务端给出的总长度等信息。
     *
     * 要求服务端回 206：回了 200 说明它忽略了 Range，此时流里是从头开始的整段数据，
     * 位置对不上，必须报错而不是照单全收。
     *
     * **应读多少字节以服务端回报的 `Content-Range` 为准，不是以请求为准**：请求的终点
     * 可能越过文件末尾（未知总长度时按块大小取就会这样），此时服务端只给到文件末尾，
     * 按请求长度去等会一直等到读超时。
     *
     * [write] 会被按块调用，字节数之和保证等于实际可用长度。
     */
    fun read(
        client: OkHttpClient,
        url: String,
        start: Long,
        end: Long,
        write: (buffer: ByteArray, offset: Int, length: Int) -> Unit,
    ): Response {
        if (end < start) throw IOException("invalid range: $start-$end")

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
            .header("Referer", "https://www.bilibili.com")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code != 206) throw IOException("HTTP ${response.code} for $start-$end")
            val body = response.body ?: throw IOException("empty response body")

            val parsed = parseContentRange(response.header("Content-Range"))
            val expected = if (parsed != null && parsed.start == start) {
                parsed.end - parsed.start + 1
            } else {
                end - start + 1
            }
            if (expected > Int.MAX_VALUE) throw IOException("range too large: $expected")
            if (expected <= 0L) return Response(parsed?.total, response.header("Content-Type"))

            val buffer = ByteArray(READ_BUFFER_BYTES)
            var remaining = expected
            body.byteStream().use { input ->
                while (remaining > 0L) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read == -1) throw IOException("short body: ${expected - remaining}/$expected")
                    write(buffer, 0, read)
                    remaining -= read
                }
            }
            return Response(parsed?.total, response.header("Content-Type"))
        }
    }

    private const val CONTENT_RANGE_UNIT = "bytes"
}
