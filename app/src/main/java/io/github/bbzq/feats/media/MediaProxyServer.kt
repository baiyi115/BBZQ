package io.github.bbzq.feats.media

import android.content.Context
import io.github.bbzq.ModuleSettings
import io.github.bbzq.ModuleSettingsBridge
import io.github.bbzq.feats.download.DownloadSourcePlan
import io.github.bbzq.feats.hook.NetworkTypeDetector
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程内的本地媒体代理。
 *
 * 播放器拿到的媒体地址被改写成
 * `http://127.0.0.1:<port>/media?u=<原始地址>`，于是它来读本代理；代理再按模块里
 * 配好的 CDN 节点并发取数，把字节原样交给播放器。
 *
 * 为什么是这个方向：Android 端拿不到播放器的读取回调（IJK 的拉流在 native，
 * `IAndroidIO` 与 `IMediaDataSource` 都没被启用，media3 也没打包 HTTP 数据源）。
 * 与其去拦读取，不如让播放器来读我们，这是唯一能拿到完整字节流的位置。
 *
 * 候选节点全部来自模块已有配置：`DownloadSourcePlan` 展开用户配的 CDN 优先列表，
 * 按 `CdnSpeedModel` 的实测速率排序、`DownloadHealth` 把反复失败的排到最后，最后以
 * playurl 原始地址兜底。代理自身没有另一套候选表。
 *
 * 每次请求只服务一块（[MediaProxyProtocol.CHUNK_BYTES]），并按序边到边交给播放器，
 * 不等整块取齐；同时把下一块提前取回来（[MediaPrefetchCache]）。
 *
 * 任何环节失败都回 `302` 指向原始地址，让播放器直接回源。宁可退化为直连，也不能
 * 因为代理出问题而没有画面。
 */
object MediaProxyServer {

    /**
     * 回环地址。必须显式指定 IPv4 的 `127.0.0.1`，不能用 `getLoopbackAddress()`：
     * 它在 IPv6 栈上返回 `::1`，而改写出的地址是 `127.0.0.1`，播放器会连不上。
     */
    private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

    private const val ACCEPT_BACKLOG = 16
    private const val MAX_WORKERS = 8
    private const val SOCKET_TIMEOUT_MS = 8000
    private const val MAX_HEADER_LENGTH = 8192
    private const val DEFAULT_CONTENT_TYPE = "video/mp4"
    private const val MAX_TRACKED_TOTALS = 64

    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(MAX_WORKERS) { runnable ->
        Thread(runnable, "bbzq-media-proxy").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = -1

    /** 用于判断蜂窝/WiFi，从而选用对应的那份 CDN 优先列表。 */
    @Volatile
    private var hostContext: Context? = null

    /**
     * 地址 → 文件总长度。学到一次就够：后续请求靠它把块夹到文件末尾，也靠它决定
     * 能不能切分（总长度未知时切分会产生越过文件末尾的片，那些片必然失败）。
     */
    private val knownTotals = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > MAX_TRACKED_TOTALS
    }

    /** 代理实际监听的端口；未启动时为 `-1`。 */
    val port: Int get() = boundPort

    val isRunning: Boolean get() = running.get() && boundPort > 0

    /**
     * 启动代理。端口由系统分配：写死会和别的进程抢，而且换端口就要重新改写一次地址，
     * 没有好处。已启动时直接返回现有端口，`-1` 表示启动失败。
     */
    @Synchronized
    fun start(context: Context?): Int {
        context?.let { hostContext = it.applicationContext ?: it }
        if (isRunning) return boundPort
        return try {
            val socket = ServerSocket(0, ACCEPT_BACKLOG, LOOPBACK)
            serverSocket = socket
            boundPort = socket.localPort
            running.set(true)
            Thread({ acceptLoop(socket) }, "bbzq-media-accept").apply { isDaemon = true }.start()
            boundPort
        } catch (t: Throwable) {
            serverSocket = null
            boundPort = -1
            running.set(false)
            -1
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = -1
        MediaPrefetchCache.clear()
        synchronized(knownTotals) { knownTotals.clear() }
    }

    /** 该地址改写后走代理的地址；代理不可用或地址不适用时返回 `null`。 */
    fun rewrite(url: String): String? {
        if (!isRunning) return null
        if (url.isEmpty()) return null
        // 已经是本代理的地址就不再套一层
        if (url.startsWith("http://127.0.0.1:")) return null
        return MediaProxyProtocol.proxyUrl(boundPort, url)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                break
            }
            runCatching { client.soTimeout = SOCKET_TIMEOUT_MS }
            runCatching { workers.execute { handleConnection(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    /** 一条连接上可能有多条请求（keep-alive），逐条处理到对端关闭或要求断开为止。 */
    private fun handleConnection(client: Socket) {
        try {
            BufferedInputStream(client.getInputStream()).use { input ->
                BufferedOutputStream(client.getOutputStream()).use { output ->
                    while (running.get()) {
                        val request = readRequest(input) ?: break
                        if (!serve(request, output)) break
                        output.flush()
                    }
                }
            }
        } catch (t: Throwable) {
            // 播放器主动断开是常态，不值得记日志
        } finally {
            runCatching { client.close() }
        }
    }

    private class Request(val method: String, val path: String, val query: String?, val range: String?)

    /**
     * 读请求行与请求头。返回 `null` 表示对端已关闭或请求行无法解析。
     *
     * GET 没有请求体，所以读完空行即可。本代理也只接受 GET，避免把未读的请求体留在
     * 流里让下一次解析错位。
     */
    private fun readRequest(input: BufferedInputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        var range: String? = null
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals("Range", ignoreCase = true)) {
                range = line.substring(colon + 1).trim()
            }
        }
        val target = parts[1]
        val questionMark = target.indexOf('?')
        val path = if (questionMark < 0) target else target.substring(0, questionMark)
        val query = if (questionMark < 0) null else target.substring(questionMark + 1)
        return Request(parts[0].uppercase(), path, query, range)
    }

    /** 读一行（到 LF 或 CRLF 为止），不含行尾。 */
    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) return builder.toString().trimEnd('\r')
            if (builder.length > MAX_HEADER_LENGTH) throw IOException("header line too long")
            builder.append(b.toChar())
        }
    }

    /** @return 是否继续处理这条连接上的下一条请求 */
    private fun serve(request: Request, output: BufferedOutputStream): Boolean {
        if (request.method != "GET") {
            writeStatus(output, 405, "Method Not Allowed")
            return false
        }
        val originalUrl = MediaProxyProtocol.originalUrlOf(request.path, request.query)
        if (originalUrl == null) {
            writeStatus(output, 404, "Not Found")
            return false
        }
        return try {
            serveRange(originalUrl, request.range, output)
            true
        } catch (t: Throwable) {
            // 降级：让播放器直接去原始地址取，不要因为代理出问题而没有画面
            writeRedirect(output, originalUrl)
            false
        }
    }

    private fun serveRange(originalUrl: String, rangeHeader: String?, output: BufferedOutputStream) {
        val sources = candidatesFor(originalUrl)
        if (sources.isEmpty()) throw IOException("no candidate for $originalUrl")

        val knownTotal = rememberTotal(originalUrl, null)
        val requested = MediaProxyProtocol.resolve(MediaProxyProtocol.parseRange(rangeHeader), knownTotal)
        val requestedEnd = requested.end ?: return

        // 预读命中就直接给，省掉整个往返
        val cached = MediaPrefetchCache.get(originalUrl, requested.start, requestedEnd)
        if (cached != null) {
            val (bytes, cachedTotal) = cached
            val total = cachedTotal ?: knownTotal
            if (cachedTotal != null) rememberTotal(originalUrl, cachedTotal)
            writePartial(output, bytes, requested.start, total)
            prefetchNextChunk(sources, originalUrl, requested.start + bytes.size, bytes.size.toLong(), total)
            return
        }

        if (knownTotal == null) {
            // 还不知道文件多长，先整块取回顺便学到总长度。每个视频只有第一次会走这里：
            // 此时 Content-Length 无从预先确定，边到边发就没法如实回报响应头。
            val (bytes, meta) = MediaRangeFetcher.fetch(sources, requested, null)
            meta.total?.let { rememberTotal(originalUrl, it) }
            writePartial(output, bytes, requested.start, meta.total)
            prefetchNextChunk(sources, originalUrl, requested.start + bytes.size, bytes.size.toLong(), meta.total)
            return
        }

        // 总长度已知：流式下发，第一片到手就写响应头，之后边到边写
        var served = 0L
        MediaRangeFetcher.stream(
            sources,
            requested,
            knownTotal,
            onReady = { meta ->
                meta.total?.let { rememberTotal(originalUrl, it) }
                writePartialHead(
                    output = output,
                    start = requested.start,
                    length = requestedEnd - requested.start + 1,
                    total = meta.total ?: knownTotal,
                    contentType = meta.contentType,
                )
            },
        ) { bytes ->
            output.write(bytes)
            served += bytes.size
        }
        prefetchNextChunk(sources, originalUrl, requested.start + served, served, knownTotal)
    }

    /**
     * 把下一块提前取回来。
     *
     * 播放器按顺序读，当前块读完必然来要下一块；提前取好，块边界那次请求就零等待。
     */
    private fun prefetchNextChunk(
        sources: List<String>,
        originalUrl: String,
        nextStart: Long,
        chunkLength: Long,
        total: Long?,
    ) {
        if (chunkLength <= 0L) return
        if (total != null) {
            if (nextStart > total - 1) return
            MediaPrefetchCache.prefetch(sources, originalUrl, nextStart, minOf(chunkLength, total - nextStart), total)
        } else {
            MediaPrefetchCache.prefetch(sources, originalUrl, nextStart, chunkLength, null)
        }
    }

    /** 候选源：模块里配好的 CDN 节点，按实测速率排序、冷却的排最后，原始地址兜底。 */
    private fun candidatesFor(originalUrl: String): List<String> {
        val prefs = ModuleSettingsBridge.instance
        val isCellular = hostContext?.let { context ->
            runCatching { NetworkTypeDetector.isCellular(context) }.getOrDefault(false)
        } ?: false
        val hosts = runCatching { ModuleSettings.getActiveCdnHosts(prefs, isCellular) }.getOrDefault(emptyList())
        return DownloadSourcePlan.plan(originalUrl, hosts).map { it.url }
    }

    private fun rememberTotal(originalUrl: String, total: Long?): Long? = synchronized(knownTotals) {
        if (total != null && total > 0L) {
            knownTotals[originalUrl] = total
            total
        } else {
            knownTotals[originalUrl]
        }
    }

    private fun writePartial(output: BufferedOutputStream, body: ByteArray, start: Long, total: Long?) {
        writePartialHead(output, start, body.size.toLong(), total, null)
        output.write(body)
    }

    private fun writePartialHead(
        output: BufferedOutputStream,
        start: Long,
        length: Long,
        total: Long?,
        contentType: String?,
    ) {
        val end = start + length - 1
        writeHead(
            output,
            status = 206,
            reason = "Partial Content",
            headers = listOf(
                "Content-Type" to (contentType?.takeIf { it.isNotBlank() } ?: DEFAULT_CONTENT_TYPE),
                "Content-Length" to length.toString(),
                "Content-Range" to MediaProxyProtocol.contentRange(MediaProxyProtocol.Range(start, end), total),
                "Accept-Ranges" to "bytes",
                "Connection" to "keep-alive",
            ),
        )
    }

    private fun writeRedirect(output: BufferedOutputStream, location: String) {
        writeHead(
            output,
            status = 302,
            reason = "Found",
            headers = listOf(
                "Location" to location,
                "Content-Length" to "0",
                "Connection" to "close",
            ),
        )
    }

    private fun writeStatus(output: BufferedOutputStream, status: Int, reason: String) {
        writeHead(
            output,
            status = status,
            reason = reason,
            headers = listOf(
                "Content-Length" to "0",
                "Connection" to "close",
            ),
        )
    }

    private fun writeHead(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        headers: List<Pair<String, String>>,
    ) {
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }
}
