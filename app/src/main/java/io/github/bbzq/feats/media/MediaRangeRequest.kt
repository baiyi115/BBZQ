package io.github.bbzq.feats.media

import io.github.bbzq.feats.HttpRange
import io.github.bbzq.feats.RangeRequestBudget
import io.github.bbzq.feats.download.DownloadHealth
import io.github.bbzq.feats.hook.CustomCdnProcessor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 借鉴 Make Bilibili Great Than Ever Before 的延迟救援；只抢有效正文，预读不额外竞速。 */
internal object MediaRangeRequest {
    private val timers by lazy {
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "bbzq-media-watch").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    // 救援请求不排队：没有空位就沿用主请求，不能让备用任务挤占当前分片。
    private val racers by lazy {
        ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
            Thread(task, "bbzq-media-rescue").apply { isDaemon = true }
        })
    }

    // 只有取得槽位的前台请求才异步竞速；池满时当前线程顺序回源，不排队。
    // 加载线程只等胜出者，避免备用已完成仍被主请求的取消延迟拖住。
    private val requests by lazy {
        ThreadPoolExecutor(0, 3, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
            Thread(task, "bbzq-media-request").apply { isDaemon = true }
        })
    }

    class SlowSource : SocketTimeoutException("media source too slow")

    private class Attempt(val url: String, val budget: RangeRequestBudget, val minimumRate: Double) {
        val started = System.nanoTime()
        val stallNanos = TimeUnit.MILLISECONDS.toNanos(MediaSourcePerformance.stallTimeoutMs(url))
        @Volatile var done = false
        @Volatile var lost = false
        @Volatile var success = false
        @Volatile var failure: IOException? = null
        // 进度与背压状态在同一把锁下发布，监视线程不能观察到半次写入。
        var firstByte = 0L
        var lastProgress = started
        var inSink = false
        var sinkNanos = 0L
        var bytes = 0L
        var firstChunkBytes = 0
        var expectedBytes = 0L
        var resumable = false
        @Volatile var guardFailure: IOException? = null
        // 慢传使用短窗口，不能让已经恢复的线路继续背负之前停顿的平均值。
        var windowStarted = 0L
        var windowSinkNanos = 0L
        var windowBytes = 0L
        var slowSince = 0L
    }

    fun read(
        client: OkHttpClient, primary: String, alternative: String?, alternatives: List<String>,
        start: Long, end: Long, entityTag: String?, budget: RangeRequestBudget, epoch: Long,
        slowSources: Set<String>,
        validate: (HttpRange.Response) -> Unit,
        onHeaders: (HttpRange.Response) -> Unit,
        onFailure: (String, IOException) -> Unit,
        write: (ByteArray, Int, Int) -> Unit,
    ) {
        val lock = Object()
        budget.onCancel { synchronized(lock) { lock.notifyAll() } }
        val winner = AtomicReference<Attempt?>()
        fun attempt(url: String): Attempt {
            val child = RangeRequestBudget(
                TimeUnit.NANOSECONDS.toMillis(budget.remainingNanos()).coerceAtLeast(1), budget.isSpeculative,
            )
            budget.onCancel(child::cancel)
            return Attempt(url, child,
                if (url in slowSources) 0.0 else MediaSourcePerformance.rescueRate(url, alternatives))
        }
        val main = attempt(primary)
        var backup: Attempt? = null
        var closed = false
        var backupTried = false
        var hedging = false

        fun forward(current: Attempt, count: Int = 0, action: () -> Unit) {
            synchronized(current) {
                current.guardFailure?.let { throw it }
                current.budget.remainingNanos()
                current.inSink = true
            }
            val entered = System.nanoTime()
            var delivered = false
            try { action(); delivered = true } finally {
                synchronized(current) {
                    val now = System.nanoTime()
                    current.sinkNanos += now - entered
                    current.lastProgress = now
                    if (delivered && count > 0) {
                        if (current.bytes == 0L) current.firstChunkBytes = count
                        current.bytes += count
                    }
                    current.inSink = false
                }
            }
        }

        fun run(current: Attempt) {
            try {
                HttpRange.read(client, current.url, start, end, budget = current.budget, ifRange = entityTag,
                    firstByteTimeoutMs = if (alternatives.size > 1) 5000 else 0,
                    deferHeadersUntilBody = true, onHeaders = { meta ->
                        synchronized(lock) {
                            if (closed || winner.get() != null) {
                                current.lost = true
                                throw IOException("media range race lost")
                            }
                            current.budget.remainingNanos()
                            // 只有通过表示校验且拿到正文的请求才有权提交下游。
                            validate(meta)
                            winner.set(current)
                            synchronized(current) {
                                current.firstByte = System.nanoTime()
                                current.lastProgress = current.firstByte
                                current.windowStarted = current.firstByte
                                current.expectedBytes = meta.end - meta.start + 1
                                current.resumable = meta.total != null && meta.strongEntityTag != null
                            }
                            val other = if (current === main) backup else main
                            other?.let { it.lost = true; it.budget.cancel() }
                        }
                        forward(current) { onHeaders(meta) }
                    }) { bytes, offset, length ->
                    forward(current, length) { write(bytes, offset, length) }
                }
                budget.remainingNanos()
                MediaSourcePerformance.record(epoch, current.url, current.firstByte - current.started,
                    System.nanoTime() - current.firstByte - current.sinkNanos, current.bytes - current.firstChunkBytes)
                DownloadHealth.recordHostSuccess(CustomCdnProcessor.hostOf(current.url))
                current.success = true
            } catch (e: Exception) {
                val failure = current.guardFailure ?: (e as? IOException ?: IOException("media range failed", e))
                current.failure = failure
                if (failure is SlowSource) {
                    MediaSourcePerformance.record(epoch, current.url, current.firstByte - current.started,
                        System.nanoTime() - current.firstByte - current.sinkNanos, current.bytes - current.firstChunkBytes)
                }
                // 竞速落败、用户取消、块预算耗尽都不是 CDN 故障。
                synchronized(lock) {
                    if (!closed && !current.lost && runCatching { budget.remainingNanos() }.isSuccess) {
                        onFailure(current.url, failure)
                    }
                }
            } finally {
                current.budget.cancel()
                synchronized(lock) {
                    current.done = true
                    lock.notifyAll()
                }
            }
        }

        fun startBackup() = synchronized(lock) {
            if (!hedging || closed || backupTried || winner.get() != null || alternative == null || budget.isSpeculative) return@synchronized
            backupTried = true
            val next = attempt(alternative)
            backup = next
            try { racers.execute { run(next) } } catch (_: RejectedExecutionException) {
                backup = null
                next.budget.cancel()
            }
        }

        fun watch(current: Attempt) {
            val cancel = synchronized(current) {
                if (current.done || current.lost || current.guardFailure != null || current.firstByte == 0L) return
                // 只有强校验允许从已交付位置续传。背压发生在 write 内，不能当成网络停滞。
                if (!current.resumable || current.inSink || alternatives.size < 2) {
                    current.slowSince = 0
                    return
                }
                val now = System.nanoTime()
                val idle = now - current.lastProgress > current.stallNanos
                val active = now - current.firstByte - current.sinkNanos
                if (budget.isSpeculative || current.expectedBytes - current.bytes <= 64 * 1024) current.slowSince = 0
                val windowActive = now - current.windowStarted - (current.sinkNanos - current.windowSinkNanos)
                if (windowActive >= TimeUnit.MILLISECONDS.toNanos(500)) {
                    val bytes = (current.bytes - current.firstChunkBytes).coerceAtLeast(0)
                    val slow = !budget.isSpeculative && active >= TimeUnit.MILLISECONDS.toNanos(1500) &&
                        current.minimumRate > 0 && current.expectedBytes - current.bytes > 64 * 1024 &&
                        (bytes - current.windowBytes) / windowActive.toDouble() < current.minimumRate
                    if (!slow) current.slowSince = 0
                    else if (current.slowSince == 0L) current.slowSince = now
                    current.windowStarted = now
                    current.windowSinkNanos = current.sinkNanos
                    current.windowBytes = bytes
                }
                if (idle || (current.slowSince != 0L && now - current.slowSince >= TimeUnit.MILLISECONDS.toNanos(1500))) {
                    current.guardFailure = if (idle) SocketTimeoutException("media source stalled") else SlowSource()
                    true
                } else false
            }
            if (cancel) current.budget.cancel()
        }

        val delay = TimeUnit.MILLISECONDS.toNanos(MediaSourcePerformance.hedgeDelayMs(primary))
        val monitor = if (alternatives.size < 2) null else timers.scheduleWithFixedDelay({
            // 定时任务不能把一次取消异常变成永久停止调度。
            runCatching {
                if (System.nanoTime() - main.started >= delay) startBackup()
                val second = synchronized(lock) { backup }
                watch(main)
                second?.let { watch(it) }
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
        try {
            synchronized(lock) {
                if (alternative != null && !budget.isSpeculative) {
                    hedging = true
                    try { requests.execute { run(main) } }
                    catch (_: RejectedExecutionException) { hedging = false }
                }
            }
            if (!hedging) run(main)
            synchronized(lock) {
                while (true) {
                    val selected = winner.get()
                    if (selected != null && selected.done) break
                    if (selected == null && main.done) {
                        startBackup()
                        if (backup == null || backup?.done == true) break
                    }
                    try { TimeUnit.NANOSECONDS.timedWait(lock, budget.remainingNanos()) }
                    catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("media race interrupted", e)
                    }
                }
            }
            if (winner.get()?.success == true) return
            budget.remainingNanos()
            throw winner.get()?.failure ?: backup?.failure ?: main.failure ?: IOException("media range failed")
        } finally {
            monitor?.cancel(false)
            synchronized(lock) {
                closed = true
                listOfNotNull(main, backup).forEach {
                    if (it !== winner.get()) it.lost = true
                    it.budget.cancel()
                }
            }
        }
    }
}
