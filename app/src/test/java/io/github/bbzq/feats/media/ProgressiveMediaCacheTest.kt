package io.github.bbzq.feats.media

import io.github.bbzq.feats.RangeRequestBudget
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProgressiveMediaCacheTest {
    private val url = "https://media.example/video"
    private val range = MediaProxyProtocol.Range(0, 127)

    @Test fun foregroundTakeoverSharesLoadAndPromotesItsBudget() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val observedBudget = AtomicReference<RangeRequestBudget>()
        val body = ByteArray(128) { it.toByte() }
        ProgressiveMediaCache { _, requested, _, budget, headers, sink ->
            try {
                calls.incrementAndGet()
                observedBudget.set(budget)
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                budget.remainingNanos()
                headers(MediaRangeFetcher.Meta(requested.start, requested.end!!, 128, null))
                sink(body, 0, body.size)
            } finally { finished.countDown() }
        }.use { cache ->
            try {
                cache.prefetch(listOf(url), url, range, 128)
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertTrue(observedBudget.get().isSpeculative)
                cache.acquire(listOf(url), url, range, 128).use { lease ->
                    assertFalse(observedBudget.get().isSpeculative)
                    release.countDown()
                    val output = ByteArrayOutputStream()
                    lease.stream({}) { bytes, offset, count -> output.write(bytes, offset, count) }
                    assertArrayEquals(body, output.toByteArray())
                }
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                cache.acquire(listOf(url), url, range, 128).use { lease ->
                    val output = ByteArrayOutputStream()
                    lease.stream({}) { bytes, offset, count -> output.write(bytes, offset, count) }
                    assertArrayEquals(body, output.toByteArray())
                }
                assertEquals(1, calls.get())
            } finally { release.countDown() }
        }
    }

    @Test fun seekCancelsUnusedPrefetchBeforeLoadingNewPosition() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val body = ByteArray(128) { 7 }
        val next = MediaProxyProtocol.Range(128, 255)
        ProgressiveMediaCache { _, requested, _, budget, headers, sink ->
            if (requested.start == 0L) {
                budget.onCancel { cancelled.countDown() }
                started.countDown()
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                budget.remainingNanos()
            }
            headers(MediaRangeFetcher.Meta(requested.start, requested.end!!, 256, null))
            sink(body, 0, body.size)
        }.use { cache ->
            cache.prefetch(listOf(url), url, range, 256)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            cache.acquire(listOf(url), url, next, 256).use { lease ->
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                val output = ByteArrayOutputStream()
                lease.stream({}) { bytes, offset, count -> output.write(bytes, offset, count) }
                assertArrayEquals(body, output.toByteArray())
            }
        }
    }

    @Test fun lastReaderLeavingCancelsIncompleteForegroundLoad() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        ProgressiveMediaCache { _, _, _, budget, _, _ ->
            budget.onCancel { cancelled.countDown() }
            started.countDown()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            throw IOException("cancelled by reader")
        }.use { cache ->
            val first = cache.acquire(listOf(url), url, range, 128)
            val second = cache.acquire(listOf(url), url, range, 128)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            first.close()
            assertEquals(1L, cancelled.count)
            second.close()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            second.close() // 重复关闭不能再次扣减读者。
        }
    }
}
