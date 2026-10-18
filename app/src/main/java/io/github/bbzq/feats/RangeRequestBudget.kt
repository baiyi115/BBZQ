package io.github.bbzq.feats

import okhttp3.Call
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** 整个媒体块共用的单调时钟预算；包含排队、回源重试与响应体读取。 */
internal class RangeRequestBudget(timeoutMs: Long = 30_000, speculative: Boolean = false) {
    @Volatile var isSpeculative = speculative
        private set
    fun promoteToForeground() { isSpeculative = false }
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val calls = HashSet<Call>()
    private val cancellationActions = ArrayList<() -> Unit>()
    @Volatile var isCancelled = false
        private set

    fun remainingNanos(): Long {
        if (isCancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("media request cancelled")
        return (deadline - System.nanoTime()).takeIf { it > 0 }
            ?: throw InterruptedIOException("media request deadline exceeded")
    }

    @Synchronized fun attach(call: Call) {
        call.timeout().timeout(remainingNanos(), TimeUnit.NANOSECONDS)
        calls += call
    }

    @Synchronized fun detach(call: Call) { calls -= call }

    fun onCancel(action: () -> Unit) {
        val cancelled = synchronized(this) {
            if (isCancelled) true else { cancellationActions += action; false }
        }
        if (cancelled) action()
    }

    fun cancel() {
        val pending = synchronized(this) {
            if (isCancelled) return
            isCancelled = true
            (calls.toList() to cancellationActions.toList()).also {
                calls.clear()
                cancellationActions.clear()
            }
        }
        // 先唤醒背压/读者等待，再终止网络；不能把已取消任务留到 deadline 才释放。
        pending.second.forEach { runCatching { it() } }
        pending.first.forEach { it.cancel() }
    }
}
