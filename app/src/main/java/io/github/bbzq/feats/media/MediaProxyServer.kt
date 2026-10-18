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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
 * 因此通过地址改写让播放器读取本地代理，在此处理完整的媒体字节流。
 *
 * 候选节点全部来自模块已有配置：`DownloadSourcePlan` 展开用户配的 CDN 优先列表，
 * 按 `CdnSpeedModel` 的实测速率排序、`DownloadHealth` 把反复失败的排到最后，最后以
 * playurl 原始地址兜底。代理自身没有另一套候选表。
 *
 * 每次请求只服务一块（[MediaProxyProtocol.CHUNK_BYTES]），并按序边到边交给播放器，
 * 不等整块取齐；同时把下一块提前取回来（[MediaPrefetchCache]）。
 *
 * 提交响应前失败可回 `302`；开始输出后失败关闭连接，由播放器按 Range 重试。
 */
object MediaProxyServer {

    /**
     * 回环地址。必须显式指定 IPv4 的 `127.0.0.1`，不能用 `getLoopbackAddress()`：
     * 它在 IPv6 栈上返回 `::1`，而改写出的地址是 `127.0.0.1`，播放器会连不上。
     */
    private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

    private const val ACCEPT_BACKLOG = 16
    private const val MAX_WORKERS = 8
    private const val SOCKET_TIMEOUT_MS = 1500
    private const val RESPONSE_TIMEOUT_SECONDS = 30L
    private const val MAX_HEADER_LENGTH = 8192
    private const val DEFAULT_CONTENT_TYPE = "video/mp4"
    private const val MAX_TRACKED_TOTALS = 64

    private val running = AtomicBoolean(false)
    private val workers = ThreadPoolExecutor(MAX_WORKERS, MAX_WORKERS, 0, TimeUnit.SECONDS, SynchronousQueue(), { runnable ->
        Thread(runnable, "bbzq-media-proxy").apply { isDaemon = true }
    })
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val deadlines = ScheduledThreadPoolExecutor(1, { runnable ->
        Thread(runnable, "bbzq-media-deadline").apply { isDaemon = true }
    }).apply { removeOnCancelPolicy = true }

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
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        MediaPrefetchCache.clear()
        MediaSourcePerformance.clear()
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
        while (serverSocket === socket && running.get()) {
            val client = try { socket.accept() } catch (_: IOException) { break }
            val accepted = synchronized(this) {
                if (serverSocket !== socket || !running.get()) false else {
                    activeSockets += client
                    true
                }
            }
            if (!accepted) { runCatching { client.close() }; continue }
            runCatching {
                client.soTimeout = SOCKET_TIMEOUT_MS
                workers.execute { handleConnection(client, socket) }
            }.onFailure {
                activeSockets -= client
                runCatching { client.close() }
            }
        }
    }

    /** 本地 HTTP 每次只服务一块并关闭连接；CDN 上游仍由 OkHttp 复用连接。 */
    private fun handleConnection(client: Socket, listener: ServerSocket) {
        val deadline = deadlines.schedule({ runCatching { client.close() } }, RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            BufferedInputStream(client.getInputStream()).use { input ->
                BufferedOutputStream(client.getOutputStream()).use { output ->
                    val request = readRequest(input) ?: return
                    serve(request, output, listener)
                    output.flush()
                }
            }
        } catch (_: Exception) {
            // 切视频/拖动进度时断开连接是正常事件。
        } finally {
            deadline.cancel(false)
            activeSockets -= client
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
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: return null
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_LENGTH) throw IOException("request headers too long")
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

    private fun serve(request: Request, output: BufferedOutputStream, listener: ServerSocket) {
        if (request.method != "GET") {
            writeStatus(output, 405, "Method Not Allowed")
            return
        }
        val originalUrl = MediaProxyProtocol.originalUrlOf(request.path, request.query)
        if (originalUrl == null) {
            writeStatus(output, 404, "Not Found")
            return
        }
        // 无 Range 的完整 GET、后缀/多段或不支持的格式交回原站，不能伪装为从零开始的 206。
        val range = MediaProxyProtocol.parseRange(request.range)
        if (range == null) {
            writeRedirect(output, originalUrl)
            return
        }
        var committed = false
        try {
            val total = rememberTotal(originalUrl, null)
            if (total != null && range.start >= total) {
                committed = true
                writeHead(output, 416, "Range Not Satisfiable", listOf(
                    "Content-Range" to "bytes */$total", "Content-Length" to "0", "Connection" to "close"))
                return
            }
            serveRange(originalUrl, MediaProxyProtocol.resolve(range, total), total, output, listener) { committed = true }
        } catch (_: Exception) {
            // 响应头一旦开始发出，只能关闭连接；在媒体正文里追加 302 会污染解码输入。
            if (!committed) writeRedirect(output, originalUrl)
        }
    }

    private fun serveRange(
        originalUrl: String, requested: MediaProxyProtocol.Range, knownTotal: Long?,
        output: BufferedOutputStream, listener: ServerSocket, commit: () -> Unit,
    ) {
        val sources = candidatesFor(originalUrl)
        if (sources.isEmpty()) throw IOException("no media candidate")
        val lease = synchronized(this) {
            if (serverSocket !== listener || !running.get()) throw IOException("media proxy stopped")
            MediaPrefetchCache.acquire(sources, originalUrl, requested, knownTotal)
        }
        val meta = lease.use { lease ->
            lease.stream(onReady = { meta ->
                synchronized(this) {
                    if (serverSocket !== listener || !running.get()) throw IOException("media proxy stopped")
                    rememberTotal(originalUrl, meta.total)
                }
                commit()
                writePartialHead(output, meta.start, meta.length, meta.total ?: knownTotal, meta.contentType)
            }) { bytes, offset, length ->
                output.write(bytes, offset, length)
                // 首批/尾批小于缓冲区时也立即交给播放器。
                output.flush()
            }
        }
        synchronized(this) {
            if (serverSocket === listener && running.get() && meta.end < Long.MAX_VALUE) {
                prefetchNextChunk(sources, originalUrl, meta.end + 1, meta.length, meta.total ?: knownTotal)
            }
        }
    }

    /**
     * 把下一块提前取回来。
     *
     * 顺序播放时尝试提前取下一块；前台请求可接管尚未完成的预读，减少边界等待。
     */
    private fun prefetchNextChunk(
        sources: List<String>,
        originalUrl: String,
        nextStart: Long,
        chunkLength: Long,
        total: Long?,
    ) {
        // 16 字节等索引探测不应触发同样细碎的后台请求。
        if (chunkLength < 256L * 1024) return
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
        MediaSourcePerformance.updateScope(isCellular)
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
                "Connection" to "close",
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
