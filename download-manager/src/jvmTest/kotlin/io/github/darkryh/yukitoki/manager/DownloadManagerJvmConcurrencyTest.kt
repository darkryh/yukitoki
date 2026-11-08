package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Real-thread tests. Unlike the virtual-time common suite, these run workers on a true
 * multi-threaded [Dispatchers.IO] over the real [FileSystem.SYSTEM], which is the only
 * way to exercise the worker/coordinator concurrency contract and the wall-clock idle
 * timeout.
 */
class DownloadManagerJvmConcurrencyTest {
    private val fs = FileSystem.SYSTEM
    private val tmpDir = "${System.getProperty("java.io.tmpdir")}/yukitoki-test-${System.nanoTime()}".toPath()

    private fun path(name: String) = "${tmpDir}/$name".toPath()

    @AfterTest
    fun cleanup() {
        runCatching { fs.deleteRecursively(tmpDir, mustExist = false) }
    }

    @Test
    fun `concurrent segmented and plain downloads on real threads stay race-free`() = runBlocking {
        val payload = ByteArray(16 * 1024) { (it % 251).toByte() }
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentLength to listOf(payload.size.toString()),
                        HttpHeaders.ETag to listOf("\"v1\""),
                    ),
                )
                HttpMethod.Get -> {
                    val range = request.headers[HttpHeaders.Range]
                    if (range == null) {
                        respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf(payload.size.toString())))
                    } else {
                        val (start, end) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                        respond(
                            content = payload.copyOfRange(start, end + 1),
                            status = HttpStatusCode.PartialContent,
                            headers = headersOf(
                                HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
                                HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}"),
                            ),
                        )
                    }
                }
                else -> error("Unexpected method ${request.method}")
            }
        }
        val client = HttpClient(engine)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            repeat(3) { round ->
                val manager = DefaultDownloadManager(
                    httpClient = client,
                    scope = scope,
                    fileSystem = fs,
                    config = DownloadManagerConfig(maxConcurrentDownloads = 4),
                )
                val ids = (1..24).map { i ->
                    val id = DownloadId.new()
                    manager.submitCommand(
                        DownloadCommand.Enqueue(
                            id = id,
                            request = DownloadRequest(
                                url = "https://example.com/r$round-$i.bin",
                                destinationPath = path("r$round-$i.bin"),
                                segmentation = if (i % 2 == 0) {
                                    DownloadSegmentation.MaxSegments(4)
                                } else {
                                    DownloadSegmentation.Single
                                },
                            ),
                        )
                    )
                    id
                }
                // Hammer SetConcurrency from another thread while workers run, to stress
                // the coordinator/worker boundary.
                scope.launch {
                    repeat(20) {
                        manager.submitCommand(DownloadCommand.SetMaxConcurrentDownloads(((it % 4) + 1)))
                    }
                }

                withTimeout(60_000) {
                    ids.forEach { id -> assertIs<DownloadResult.Success>(manager.await(id)) }
                }
                ids.forEachIndexed { index, _ ->
                    val bytes = fs.read(path("r$round-${index + 1}.bin")) { readByteArray() }
                    assertTrue(bytes.contentEquals(payload), "round $round file ${index + 1} content mismatch")
                }
                manager.shutdown()
            }
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun `idle timeout fails a stalled stream on real time`() = runBlocking {
        val engine = MockEngine {
            // Emit a few bytes, then leave the channel open forever (never closed) so the
            // worker's next read blocks and the idle watchdog must fire.
            val channel = ByteChannel(autoFlush = true)
            channel.writeStringUtf8("partial")
            respond(
                content = channel,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength to listOf("100")),
            )
        }
        val client = HttpClient(engine)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val manager = DefaultDownloadManager(
                httpClient = client,
                scope = scope,
                fileSystem = fs,
                config = DownloadManagerConfig(idleTimeout = 300.milliseconds),
            )
            val id = DownloadId.new()
            manager.submitCommand(
                DownloadCommand.Enqueue(
                    id = id,
                    request = DownloadRequest(
                        url = "https://example.com/stall.bin",
                        destinationPath = path("stall.bin"),
                        retryPolicy = RetryPolicy(
                            maxAttempts = 1,
                            initialDelayMs = 1,
                            maxDelayMs = 1,
                            multiplier = 1.0,
                            jitterRatio = 0.0,
                        ),
                    ),
                )
            )
            val result = withTimeout(10_000) { manager.await(id) }
            assertIs<DownloadResult.Failure>(result)
            assertIs<DownloadFailure.Timeout>(result.failure)
            manager.shutdown()
        } finally {
            client.close()
            scope.cancel()
        }
    }
}
