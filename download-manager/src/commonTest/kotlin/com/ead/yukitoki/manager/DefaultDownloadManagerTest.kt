package com.ead.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDownloadManagerTest {

    /**
     * Builds a manager whose blocking-I/O dispatcher shares the test scheduler, so the
     * worker offloading introduced for the main-thread-safety fix stays fully
     * deterministic under virtual time.
     */
    private fun TestScope.testManager(
        client: HttpClient,
        fs: FileSystem = FakeFileSystem(),
        config: DownloadManagerConfig = DownloadManagerConfig(),
    ): DefaultDownloadManager = DefaultDownloadManager(
        httpClient = client,
        scope = backgroundScope,
        fileSystem = fs,
        // The idle-timeout watchdog is a wall-clock reliability feature; under a virtual
        // test clock a jump (advanceTimeBy / advanceUntilIdle) would trip it spuriously,
        // so disable it here and cover it with a real-dispatcher test instead.
        config = config.copy(idleTimeoutMs = 0),
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `enqueue and download succeed`() = runTest {
        val payload = "hello".encodeToByteArray()
        val engine = MockEngine {
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf(payload.size.toString()),
                    HttpHeaders.ContentType to listOf(ContentType.Application.OctetStream.toString()),
                ),
            )
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)

        val id = DownloadId.new()
        val ack = manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/file.bin",
                    destinationPath = "/tmp/file.bin".toPath(),
                )
            )
        )
        assertIs<CommandAck.Accepted>(ack)

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertTrue(fs.exists("/tmp/file.bin".toPath()))
        assertEquals("hello", fs.read("/tmp/file.bin".toPath()) { readUtf8() })
        manager.shutdown()
        client.close()
    }

    @Test
    fun `parallel downloads bounded by concurrency and complete`() = runTest {
        val payload = ByteArray(2048) { 7 }
        val engine = MockEngine {
            respond(content = payload, status = HttpStatusCode.OK)
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs, DownloadManagerConfig(maxConcurrentDownloads = 3))

        try {
            val ids = (1..20).map { idx ->
                val id = DownloadId.new()
                manager.command(
                    DownloadCommand.Enqueue(
                        id = id,
                        request = DownloadRequest(
                            url = "https://example.com/$idx.bin",
                            destinationPath = "/bulk/$idx.bin".toPath(),
                        )
                    )
                )
                id
            }

            withTimeout(20_000) {
                while (true) {
                    val state = manager.observeState().value
                    val terminalCount = ids.count { id ->
                        when (state.snapshots[id]?.state) {
                            is DownloadState.Succeeded, is DownloadState.Failed, is DownloadState.Cancelled -> true
                            else -> false
                        }
                    }
                    if (terminalCount == ids.size) break
                    delay(20)
                }
            }
            val final = manager.observeState().value
            ids.forEach { id ->
                assertTrue(final.snapshots[id]?.state is DownloadState.Succeeded)
            }
            assertTrue(final.active.size <= 3)
            assertTrue(final.pending.isEmpty())
        } finally {
            manager.shutdown()
            client.close()
        }
    }

    @Test
    fun `retry failed item succeeds after transient 5xx`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond("err", HttpStatusCode.InternalServerError)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/retry",
                    destinationPath = "/tmp/retry.bin".toPath(),
                    retryPolicy = RetryPolicy(
                        maxAttempts = 2,
                        initialDelayMs = 1,
                        maxDelayMs = 1,
                        multiplier = 1.0,
                        jitterRatio = 0.0,
                    ),
                )
            )
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals(2, calls)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `pause queued then resume`() = runTest {
        val payload = "ok".encodeToByteArray()
        val engine = MockEngine { respond(payload, HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs, DownloadManagerConfig(maxConcurrentDownloads = 1))
        val id1 = DownloadId.new()
        val id2 = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(id = id1, request = DownloadRequest("https://x/1", "/tmp/1.bin".toPath()))
        )
        manager.command(
            DownloadCommand.Enqueue(id = id2, request = DownloadRequest("https://x/2", "/tmp/2.bin".toPath()))
        )
        val pauseAck = manager.command(DownloadCommand.Pause(id2))
        assertIs<CommandAck.Accepted>(pauseAck)
        val pauseState = manager.observeState().value.snapshots[id2]?.state
        assertTrue(pauseState is DownloadState.Paused || pauseState is DownloadState.Queued)
        manager.command(DownloadCommand.Resume(id2))

        assertIs<DownloadResult.Success>(manager.awaitTerminal(id1))
        assertIs<DownloadResult.Success>(manager.awaitTerminal(id2))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `cancel queued item terminates as cancelled`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs, DownloadManagerConfig(maxConcurrentDownloads = 1))
        val id1 = DownloadId.new()
        val id2 = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(id = id1, request = DownloadRequest("https://x/1", "/tmp/a.bin".toPath())))
        manager.command(DownloadCommand.Enqueue(id = id2, request = DownloadRequest("https://x/2", "/tmp/b.bin".toPath())))
        val ack = manager.command(DownloadCommand.Cancel(id2))
        assertIs<CommandAck.Accepted>(ack)
        assertIs<DownloadResult.Success>(manager.awaitTerminal(id1))
        val cancelled = manager.awaitTerminal(id2)
        assertIs<DownloadResult.Failure>(cancelled)
        assertIs<DownloadFailure.CancelledByUser>(cancelled.failure)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `cancel active item cleans partial file when configured`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/active-cancel.bin".toPath()
        val partPath = "/tmp/active-cancel.bin.part".toPath()
        fs.createDirectories(partPath.parent!!)
        fs.write(partPath) { writeUtf8("partial") }

        val requestStarted = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            assertEquals("bytes=7-", request.headers[HttpHeaders.Range])
            requestStarted.complete(Unit)
            never.await()
            respond("tail", HttpStatusCode.PartialContent)
        }
        val client = HttpClient(engine)
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/active-cancel.bin",
                    destinationPath = finalPath,
                )
            )
        )

        requestStarted.await()
        assertIs<CommandAck.Accepted>(manager.command(DownloadCommand.Cancel(id)))
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.CancelledByUser>(result.failure)
        assertTrue(!fs.exists(partPath), "Cancelled active downloads should clean .part files by default")
        manager.shutdown()
        client.close()
    }

    @Test
    fun `default path resolution works when destination missing`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(url = "https://cdn.host/report.docx?x=1")
            )
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals("report.docx", result.success.finalPath.name)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `retry waits for configured backoff before next attempt`() = runTest {
        var calls = 0
        val firstCall = CompletableDeferred<Unit>()
        val engine = MockEngine {
            calls++
            if (calls == 1) firstCall.complete(Unit)
            if (calls == 1) respond("err", HttpStatusCode.ServiceUnavailable) else respond("ok", HttpStatusCode.OK)
        }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        val id = DownloadId.new()

        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/backoff",
                    destinationPath = "/tmp/backoff.bin".toPath(),
                    retryPolicy = RetryPolicy(
                        maxAttempts = 2,
                        initialDelayMs = 1_000,
                        maxDelayMs = 1_000,
                        multiplier = 1.0,
                        jitterRatio = 0.0,
                    ),
                )
            )
        )

        val result = async { manager.awaitTerminal(id) }
        withTimeout(5_000) {
            while (!firstCall.isCompleted) {
                yield()
            }
        }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()

        assertIs<DownloadResult.Success>(result.await())
        assertEquals(2, calls)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `set concurrency and observe state`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        assertIs<CommandAck.Accepted>(manager.command(DownloadCommand.SetConcurrency(4)))
        val state = manager.observeState().first()
        assertEquals(4, state.maxConcurrentDownloads)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `overwrite policies fail or rename deterministically`() = runTest {
        val engine = MockEngine { respond("new", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf("3"))) }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        fs.createDirectories("/tmp".toPath())
        fs.write("/tmp/existing.txt".toPath()) { writeUtf8("old") }
        fs.write("/tmp/existing(1).txt".toPath()) { writeUtf8("older") }
        val manager = testManager(client, fs)

        val failId = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = failId,
                request = DownloadRequest(
                    url = "https://example.com/existing.txt",
                    destinationPath = "/tmp/existing.txt".toPath(),
                    overwritePolicy = OverwritePolicy.FAIL_IF_EXISTS,
                )
            )
        )
        val failed = manager.awaitTerminal(failId)
        assertIs<DownloadResult.Failure>(failed)
        assertIs<DownloadFailure.AlreadyExists>(failed.failure)
        assertEquals("old", fs.read("/tmp/existing.txt".toPath()) { readUtf8() })

        val renameId = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = renameId,
                request = DownloadRequest(
                    url = "https://example.com/existing.txt",
                    destinationPath = "/tmp/existing.txt".toPath(),
                    overwritePolicy = OverwritePolicy.RENAME_UNIQUE,
                )
            )
        )
        val renamed = manager.awaitTerminal(renameId)
        assertIs<DownloadResult.Success>(renamed)
        assertEquals("/tmp/existing(2).txt", renamed.success.finalPath.toString())
        assertEquals("new", fs.read("/tmp/existing(2).txt".toPath()) { readUtf8() })
        manager.shutdown()
        client.close()
    }

    @Test
    fun `enqueue rejects request options that the default manager cannot enforce`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())

        assertIs<CommandAck.Rejected>(
            manager.command(
                DownloadCommand.Enqueue(
                    request = DownloadRequest(
                        url = "https://example.com/segmented-post.bin",
                        method = HttpMethod.Post,
                        maxSegments = 2,
                    )
                )
            )
        )
        assertIs<CommandAck.Rejected>(
            manager.command(
                DownloadCommand.Enqueue(
                    request = DownloadRequest(
                        url = "https://example.com/constrained.bin",
                        constraints = DownloadConstraints(requireWifi = true),
                    )
                )
            )
        )
        assertIs<CommandAck.Rejected>(
            manager.command(
                DownloadCommand.Enqueue(
                    request = DownloadRequest(
                        url = "https://example.com/range.bin",
                        headers = headersOf(HttpHeaders.Range to listOf("bytes=0-10")),
                    )
                )
            )
        )

        manager.shutdown()
        client.close()
    }

    @Test
    fun `fuzz commands do not crash coordinator and all downloads terminate`() = runTest {
        val payload = ByteArray(1024) { 1 }
        val engine = MockEngine { respond(payload, HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs, DownloadManagerConfig(maxConcurrentDownloads = 4))
        try {
            val ids = (1..30).map { idx ->
                val id = DownloadId.new()
                manager.command(DownloadCommand.Enqueue(id = id, request = DownloadRequest("https://f/$idx", "/tmp/f/$idx.bin".toPath())))
                id
            }

            ids.take(10).forEach { manager.command(DownloadCommand.Pause(it)) }
            ids.take(10).forEach { manager.command(DownloadCommand.Resume(it)) }
            ids.takeLast(5).forEach { manager.command(DownloadCommand.Cancel(it)) }
            manager.command(DownloadCommand.RetryAllFailed)
            manager.command(DownloadCommand.SetConcurrency(2))
            manager.command(DownloadCommand.SetConcurrency(5))
            ids.drop(10).take(5).forEachIndexed { i, id -> manager.command(DownloadCommand.Move(id, i)) }

            withTimeout(20_000) {
                while (true) {
                    val state = manager.observeState().value
                    val done = ids.count { id ->
                        when (state.snapshots[id]?.state) {
                            is DownloadState.Succeeded, is DownloadState.Failed, is DownloadState.Cancelled -> true
                            else -> false
                        }
                    }
                    if (done == ids.size) break
                    delay(20)
                }
            }
            val finalState = manager.observeState().value
            assertTrue(finalState.pending.isEmpty())
            assertTrue(finalState.active.isEmpty())
            ids.forEach { id ->
                assertNotNull(finalState.snapshots[id])
            }
        } finally {
            manager.shutdown()
            client.close()
        }
    }

    @Test
    fun `download fails fast on inadequate disk space`() = runTest {
        val payload = "hello".encodeToByteArray()
        val engine = MockEngine {
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf(payload.size.toString()),
                ),
            )
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)

        PlatformSpaceChecker.mockUsableSpace = 100L

        try {
            val id = DownloadId.new()
            manager.command(
                DownloadCommand.Enqueue(
                    id = id,
                    request = DownloadRequest(
                        url = "https://example.com/file.bin",
                        destinationPath = "/tmp/space_test.bin".toPath(),
                    )
                )
            )

            val result = manager.awaitTerminal(id)
            assertIs<DownloadResult.Failure>(result)
            assertIs<DownloadFailure.NoSpace>(result.failure)
        } finally {
            PlatformSpaceChecker.mockUsableSpace = null
            manager.shutdown()
            client.close()
        }
    }

    @Test
    fun `on the fly hashing verifies matching sha256`() = runTest {
        val payload = "hello".encodeToByteArray()
        val expectedSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

        val engine = MockEngine {
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf(payload.size.toString()),
                ),
            )
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)

        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/hash.bin",
                    destinationPath = "/tmp/hash.bin".toPath(),
                    expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, expectedSha)
                )
            )
        )

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertTrue(fs.exists("/tmp/hash.bin".toPath()))
        assertEquals("hello", fs.read("/tmp/hash.bin".toPath()) { readUtf8() })
        manager.shutdown()
        client.close()
    }

    @Test
    fun `on the fly hashing fails on checksum mismatch and cleans partial file`() = runTest {
        val payload = "hello".encodeToByteArray()
        val badSha = "1111111111111111111111111111111111111111111111111111111111111111"

        val engine = MockEngine {
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf(payload.size.toString()),
                ),
            )
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)

        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/bad_hash.bin",
                    destinationPath = "/tmp/bad_hash.bin".toPath(),
                    expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, badSha)
                )
            )
        )

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.ChecksumMismatch>(result.failure)

        val finalExists = fs.exists("/tmp/bad_hash.bin".toPath())
        val partExists = fs.exists("/tmp/bad_hash.bin.part".toPath())
        assertTrue(!finalExists && !partExists, "Corrupted files should be cleaned up")

        manager.shutdown()
        client.close()
    }

    @Test
    fun `md5 checksum validates downloaded content`() = runTest {
        val payload = "hello".encodeToByteArray()
        val expectedMd5 = "5d41402abc4b2a76b9719d911017c592"
        val engine = MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf(payload.size.toString())))
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/md5.bin",
                    destinationPath = "/tmp/md5.bin".toPath(),
                    expectedChecksum = ChecksumSpec(ChecksumAlgorithm.MD5, expectedMd5),
                )
            )
        )
        assertIs<DownloadResult.Success>(manager.awaitTerminal(id))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `expected size mismatch fails integrity and cleans up before finalize`() = runTest {
        val payload = "hello".encodeToByteArray()
        val engine = MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf(payload.size.toString())))
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/size.bin",
                    destinationPath = "/tmp/size.bin".toPath(),
                    expectedSizeBytes = 999L,
                )
            )
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.IntegrityMismatch>(result.failure)
        assertTrue(!fs.exists("/tmp/size.bin".toPath()), "Wrong-size file must not be finalized")
        assertTrue(!fs.exists("/tmp/size.bin.part".toPath()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `range resumption appends bytes when server returns 206`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/resume.bin".toPath()
        val partPath = "/tmp/resume.bin.part".toPath()

        fs.createDirectories(partPath.parent!!)
        fs.write(partPath) { writeUtf8("part1_") }

        val engine = MockEngine { request ->
            assertEquals("bytes=6-", request.headers[HttpHeaders.Range])
            respond(
                content = "part2",
                status = HttpStatusCode.PartialContent,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("5"),
                ),
            )
        }
        val client = HttpClient(engine)
        val manager = testManager(client, fs)

        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/resume.bin",
                    destinationPath = finalPath,
                )
            )
        )

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertTrue(fs.exists(finalPath))
        assertEquals("part1_part2", fs.read(finalPath) { readUtf8() })
        manager.shutdown()
        client.close()
    }

    @Test
    fun `segmented download requests byte ranges and merges in order`() = runTest {
        val payload = "abcdefghij".encodeToByteArray()
        val requestedRanges = mutableListOf<String>()
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
                    val range = request.headers[HttpHeaders.Range].orEmpty()
                    requestedRanges += range
                    assertEquals("\"v1\"", request.headers[HttpHeaders.IfRange])
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
                else -> error("Unexpected method ${request.method}")
            }
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/segmented.bin",
                    destinationPath = "/tmp/segmented.bin".toPath(),
                    maxSegments = 3,
                )
            )
        )

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals("abcdefghij", fs.read("/tmp/segmented.bin".toPath()) { readUtf8() })
        assertEquals(listOf("bytes=0-3", "bytes=4-6", "bytes=7-9"), requestedRanges.sortedBy { it.substringAfter('=').substringBefore('-').toInt() })
        assertTrue(!fs.exists("/tmp/segmented.bin.part.seg0".toPath()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `segmented download falls back to single stream when ranges are unsupported`() = runTest {
        var headCalls = 0
        var fullGetCalls = 0
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    headCalls++
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength to listOf("5")),
                    )
                }
                HttpMethod.Get -> {
                    fullGetCalls++
                    assertEquals(null, request.headers[HttpHeaders.Range])
                    respond(
                        content = "whole",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength to listOf("5")),
                    )
                }
                else -> error("Unexpected method ${request.method}")
            }
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/fallback.bin",
                    destinationPath = "/tmp/fallback.bin".toPath(),
                    maxSegments = 4,
                )
            )
        )

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals("whole", fs.read("/tmp/fallback.bin".toPath()) { readUtf8() })
        assertEquals(1, headCalls)
        assertEquals(1, fullGetCalls)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `segmented download fails when server returns wrong content range`() = runTest {
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentLength to listOf("6"),
                    ),
                )
                HttpMethod.Get -> respond(
                    content = "abc",
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf("3"),
                        HttpHeaders.ContentRange to listOf("bytes 0-2/7"),
                    ),
                )
                else -> error("Unexpected method ${request.method}")
            }
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/bad-segment.bin",
                    destinationPath = "/tmp/bad-segment.bin".toPath(),
                    maxSegments = 2,
                )
            )
        )

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.IntegrityMismatch>(result.failure)
        assertTrue(!fs.exists("/tmp/bad-segment.bin".toPath()))
        assertTrue(!fs.exists("/tmp/bad-segment.bin.part.seg0".toPath()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `pause active download preserves resumable partial file`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/pause.bin".toPath()
        val partPath = "/tmp/pause.bin.part".toPath()
        fs.createDirectories(partPath.parent!!)
        fs.write(partPath) { writeUtf8("partial") }

        val requestStarted = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            assertEquals("bytes=7-", request.headers[HttpHeaders.Range])
            requestStarted.complete(Unit)
            never.await()
            respond("tail", HttpStatusCode.PartialContent)
        }
        val client = HttpClient(engine)
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/pause.bin",
                    destinationPath = finalPath,
                )
            )
        )

        requestStarted.await()
        assertIs<CommandAck.Accepted>(manager.command(DownloadCommand.Pause(id)))
        withTimeout(5_000) {
            while (manager.observeState().value.snapshots[id]?.state !is DownloadState.Paused) {
                delay(10)
            }
        }
        assertTrue(fs.exists(partPath), "Paused downloads should keep .part files for resume")
        manager.shutdown()
        client.close()
    }

    @Test
    fun `pause active download then resume completes from partial`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/pr.bin".toPath()
        val partPath = "/tmp/pr.bin.part".toPath()
        fs.createDirectories(partPath.parent!!)
        fs.write(partPath) { writeUtf8("part1_") }

        var call = 0
        val firstStarted = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            assertEquals("bytes=6-", request.headers[HttpHeaders.Range])
            call++
            if (call == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            } else {
                respond(
                    content = "part2",
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(HttpHeaders.ContentLength to listOf("5")),
                )
            }
        }
        val client = HttpClient(engine)
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(url = "https://example.com/pr.bin", destinationPath = finalPath)
            )
        )

        firstStarted.await()
        assertIs<CommandAck.Accepted>(manager.command(DownloadCommand.Pause(id)))
        withTimeout(5_000) {
            while (manager.observeState().value.snapshots[id]?.state !is DownloadState.Paused) delay(10)
        }
        assertIs<CommandAck.Accepted>(manager.command(DownloadCommand.Resume(id)))

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals("part1_part2", fs.read(finalPath) { readUtf8() })
        manager.shutdown()
        client.close()
    }

    @Test
    fun `content length mismatch fails integrity and removes partial`() = runTest {
        val engine = MockEngine {
            respond(
                content = "short",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength to listOf("10")),
            )
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/short.bin",
                    destinationPath = "/tmp/short.bin".toPath(),
                )
            )
        )

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.IntegrityMismatch>(result.failure)
        assertTrue(!fs.exists("/tmp/short.bin".toPath()))
        assertTrue(!fs.exists("/tmp/short.bin.part".toPath()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `range resumption resets and redownloads when server returns 200`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/change.bin".toPath()
        val partPath = "/tmp/change.bin.part".toPath()

        fs.createDirectories(partPath.parent!!)
        fs.write(partPath) { writeUtf8("part1_") }

        val engine = MockEngine { request ->
            assertEquals("bytes=6-", request.headers[HttpHeaders.Range])
            respond(
                content = "new_file_from_start",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("19"),
                ),
            )
        }
        val client = HttpClient(engine)
        val manager = testManager(client, fs)

        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/change.bin",
                    destinationPath = finalPath,
                )
            )
        )

        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertTrue(fs.exists(finalPath))
        assertEquals("new_file_from_start", fs.read(finalPath) { readUtf8() })
        manager.shutdown()
        client.close()
    }

    @Test
    fun `move reorders within priority tier and preserves priority`() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            never.await()
            respond("x", HttpStatusCode.OK)
        }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem(), DownloadManagerConfig(maxConcurrentDownloads = 1))

        // Blocker occupies the single active slot so the rest stay pending.
        val blocker = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(id = blocker, request = DownloadRequest("https://b/blocker", "/tmp/blocker.bin".toPath())))
        started.await()

        val n1 = DownloadId.new()
        val n2 = DownloadId.new()
        val high = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(id = n1, request = DownloadRequest("https://b/n1", "/tmp/n1.bin".toPath())))
        manager.command(DownloadCommand.Enqueue(id = n2, request = DownloadRequest("https://b/n2", "/tmp/n2.bin".toPath())))
        manager.command(DownloadCommand.Enqueue(id = high, request = DownloadRequest("https://b/high", "/tmp/high.bin".toPath(), priority = DownloadPriority.HIGH)))

        // Pending starts as [high, n1, n2]. Move n1 to index 1 within the NORMAL tier.
        assertEquals(listOf(high, n1, n2), manager.observeState().value.pending)
        assertIs<CommandAck.Accepted>(manager.command(DownloadCommand.Move(n1, 1)))

        // HIGH still leads (priority preserved); only the NORMAL tier reordered.
        assertEquals(listOf(high, n2, n1), manager.observeState().value.pending)
        manager.shutdown(cancelActive = true)
        client.close()
    }

    @Test
    fun `pending queue orders by priority tier`() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            never.await()
            respond("x", HttpStatusCode.OK)
        }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem(), DownloadManagerConfig(maxConcurrentDownloads = 1))

        val blocker = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(id = blocker, request = DownloadRequest("https://b/blocker", "/tmp/b.bin".toPath())))
        started.await()

        val low = DownloadId.new()
        val high = DownloadId.new()
        val normal = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(id = low, request = DownloadRequest("https://b/low", "/tmp/low.bin".toPath(), priority = DownloadPriority.LOW)))
        manager.command(DownloadCommand.Enqueue(id = high, request = DownloadRequest("https://b/high", "/tmp/high.bin".toPath(), priority = DownloadPriority.HIGH)))
        manager.command(DownloadCommand.Enqueue(id = normal, request = DownloadRequest("https://b/normal", "/tmp/normal.bin".toPath(), priority = DownloadPriority.NORMAL)))

        assertEquals(listOf(high, normal, low), manager.observeState().value.pending)
        manager.shutdown(cancelActive = true)
        client.close()
    }

    @Test
    fun `downloadNow returns success and maps rejection to failure`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())

        val ok = manager.downloadNow(DownloadRequest(url = "https://example.com/dn.bin", destinationPath = "/tmp/dn.bin".toPath()))
        assertIs<DownloadResult.Success>(ok)

        val rejected = manager.downloadNow(
            DownloadRequest(url = "https://example.com/dn2.bin", constraints = DownloadConstraints(requireCharging = true))
        )
        assertIs<DownloadResult.Failure>(rejected)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `observeItem emits snapshots through to terminal`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        val id = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(id = id, request = DownloadRequest("https://example.com/oi.bin", "/tmp/oi.bin".toPath())))
        manager.awaitTerminal(id)
        val snapshot = manager.observeItem(id).first { it?.state is DownloadState.Succeeded }
        assertNotNull(snapshot)
        assertEquals(id, snapshot.id)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `awaitTerminal returns null for unknown id`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        assertNull(manager.awaitTerminal(DownloadId.new()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `commands and awaits are rejected after shutdown`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        manager.shutdown()
        assertIs<CommandAck.Rejected>(manager.command(DownloadCommand.SetConcurrency(2)))
        assertNull(manager.awaitTerminal(DownloadId.new()))
        client.close()
    }

    @Test
    fun `graceful shutdown lets active download finish`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            release.await()
            respond("done", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf("4")))
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val path = "/tmp/graceful.bin".toPath()
        manager.command(DownloadCommand.Enqueue(request = DownloadRequest("https://example.com/graceful.bin", path)))
        started.await()

        val shutdownJob = async { manager.shutdown(cancelActive = false) }
        release.complete(Unit)
        shutdownJob.await()

        assertTrue(fs.exists(path), "Graceful shutdown should let the in-flight download finalize")
        client.close()
    }

    @Test
    fun `forced shutdown cancels active download`() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            never.await()
            respond("done", HttpStatusCode.OK)
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val path = "/tmp/forced.bin".toPath()
        manager.command(DownloadCommand.Enqueue(request = DownloadRequest("https://example.com/forced.bin", path)))
        started.await()

        manager.shutdown(cancelActive = true)

        assertTrue(!fs.exists(path), "Forced shutdown should not finalize the cancelled download")
        client.close()
    }

    @Test
    fun `request validation rejects impossible segment and progress values`() {
        assertFailsWith<IllegalArgumentException> {
            DownloadRequest(url = "https://example.com/file", maxSegments = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ProgressGranularity(minBytesDelta = -1)
        }
    }
}
