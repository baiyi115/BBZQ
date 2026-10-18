package io.github.bbzq.feats.media

import io.github.bbzq.feats.RangeRequestBudget
import java.io.IOException
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** 尾片的有界流水线：网络和播放器可同时推进，不保留整片的第二份副本。 */
internal class MediaPieceBuffer(private val budget: RangeRequestBudget) {
    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var bufferedBytes = 0
    private var finished = false
    private var failure: Exception? = null

    init { budget.onCancel(::cancel) }

    fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        require(length in 1..MAX_BYTES)
        while (!finished && (bufferedBytes + length > MAX_BYTES || chunks.size >= MAX_CHUNKS)) await()
        budget.remainingNanos()
        if (finished) throw IOException("media piece closed", failure)
        chunks.addLast(bytes.copyOfRange(offset, offset + length))
        bufferedBytes += length
        lock.notifyAll()
    }

    fun finish(error: Exception? = null) = synchronized(lock) {
        if (!finished) {
            failure = error
            finished = true
            lock.notifyAll()
        }
    }

    fun streamTo(sink: (ByteArray, Int, Int) -> Unit) {
        while (true) {
            val bytes = synchronized(lock) {
                while (chunks.isEmpty() && !finished) await()
                budget.remainingNanos()
                if (chunks.isEmpty()) {
                    failure?.let { throw IOException("media piece failed", it) }
                    return
                }
                chunks.removeFirst().also {
                    bufferedBytes -= it.size
                    lock.notifyAll()
                }
            }
            sink(bytes, 0, bytes.size)
        }
    }

    fun cancel() = synchronized(lock) {
        failure = IOException("media piece cancelled")
        finished = true
        chunks.clear()
        bufferedBytes = 0
        lock.notifyAll()
    }

    internal fun bufferedByteCount(): Int = synchronized(lock) { bufferedBytes }

    private fun await() {
        try {
            TimeUnit.NANOSECONDS.timedWait(lock, budget.remainingNanos())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("media piece interrupted").apply { initCause(e) }
        }
    }

    companion object {
        internal const val MAX_BYTES = 256 * 1024
        private const val MAX_CHUNKS = 32
    }
}
