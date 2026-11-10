package io.github.bbzq.feats.media

import io.github.bbzq.feats.RangeRequestBudget
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaRangeRequestTest {
    private val body = ByteArray(128 * 1024) { (it % 251).toByte() }
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).build()

    @Test fun promotedPrefetchCanRescueWithoutRacingWhileSpeculative() {
        Fixture().use { server ->
            val primaryStarted = CountDownLatch(1)
            val backupStarted = CountDownLatch(1)
            val releasePrimary = CountDownLatch(1)
            server.handle = { socket, path ->
                if (path == "/primary") {
                    primaryStarted.countDown()
                    releasePrimary.await(5, TimeUnit.SECONDS)
                } else backupStarted.countDown()
                respond(socket)
            }
            val budget = RangeRequestBudget(4000, speculative = true)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<ByteArray> { read(server, budget) }
                assertTrue(primaryStarted.await(2, TimeUnit.SECONDS))
                assertFalse("pure prefetch must not hedge", backupStarted.await(650, TimeUnit.MILLISECONDS))
                budget.promoteToForeground()
                assertTrue("foreground takeover must enable rescue", backupStarted.await(1500, TimeUnit.MILLISECONDS))
                assertArrayEquals(body, result.get(2, TimeUnit.SECONDS))
            } finally {
                budget.cancel()
                releasePrimary.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test fun cancellationDoesNotStartBackup() {
        Fixture().use { server ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val backups = AtomicInteger()
            server.handle = { socket, path ->
                if (path == "/primary") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                else backups.incrementAndGet()
                respond(socket)
            }
            val budget = RangeRequestBudget(4000, speculative = true)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Boolean> {
                    try { read(server, budget); false } catch (_: IOException) { true }
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                budget.cancel()
                assertTrue(result.get(2, TimeUnit.SECONDS))
                assertEquals(0, backups.get())
            } finally { budget.cancel(); release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun invalidBackupCannotWinOrPolluteBody() {
        Fixture().use { server ->
            val backupRejected = CountDownLatch(1)
            val releasePrimary = CountDownLatch(1)
            server.handle = { socket, path ->
                if (path == "/primary") {
                    releasePrimary.await(5, TimeUnit.SECONDS)
                    respond(socket)
                } else {
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx".toByteArray())
                }
            }
            val executor = Executors.newSingleThreadExecutor()
            val budget = RangeRequestBudget(4000)
            try {
                val result = executor.submit<ByteArray> { read(server, budget) { backupRejected.countDown() } }
                assertTrue(backupRejected.await(2, TimeUnit.SECONDS))
                releasePrimary.countDown()
                assertArrayEquals(body, result.get(2, TimeUnit.SECONDS))
            } finally { budget.cancel(); releasePrimary.countDown(); executor.shutdownNow() }
        }
    }

    private fun read(server: Fixture, budget: RangeRequestBudget, failure: () -> Unit = {}): ByteArray {
        val primary = server.url("primary")
        val backup = server.url("backup")
        val output = ByteArrayOutputStream()
        MediaRangeRequest.read(client, primary, backup, listOf(primary, backup), 0, body.lastIndex.toLong(),
            null, budget, MediaSourcePerformance.epoch(), emptySet(), validate = {}, onHeaders = {},
            onFailure = { _, _ -> failure() }) { bytes, offset, count -> output.write(bytes, offset, count) }
        return output.toByteArray()
    }

    private fun respond(socket: Socket) {
        val head = "HTTP/1.1 206 Partial Content\r\nContent-Length: ${body.size}\r\nContent-Range: bytes 0-${body.lastIndex}/${body.size}\r\nETag: \"same-media\"\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
    }

    private class Fixture : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "media-test-origin").apply { isDaemon = true } }
        @Volatile var handle: (Socket, String) -> Unit = { _, _ -> }
        init {
            workers.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    sockets += socket
                    workers.execute {
                        try {
                            socket.soTimeout = 2000
                            val input = socket.getInputStream().bufferedReader()
                            val path = input.readLine().split(' ')[1]
                            while (!input.readLine().isNullOrEmpty()) { }
                            handle(socket, path)
                        } catch (_: Exception) { }
                        finally { sockets -= socket; runCatching { socket.close() } }
                    }
                }
            }
        }
        fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow() }
    }
}
