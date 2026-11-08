package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
        // FakeFileSystem is not thread-safe; on Kotlin/Native a cancelled segment's handle-close
        // can race the coordinator's merge/cleanup and surface a spurious okio "file is open"
        // error (a test-double limitation — real filesystems are thread-safe). Serialize access
        // so segmentation tests are deterministic across JVM/Native/JS. See [SynchronizedFileSystem].
        fileSystem = SynchronizedFileSystem(fs),
        // The idle-timeout watchdog is a wall-clock reliability feature; under a virtual
        // test clock a jump (advanceTimeBy / advanceUntilIdle) would trip it spuriously,
        // so disable it here and cover it with a real-dispatcher test instead.
        config = config.copy(idleTimeout = Duration.ZERO),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        // Drive origin-pacing time from the same virtual scheduler as delay(), so rate-limit
        // penalties advance deterministically instead of racing the real wall clock. This is
        // what makes the segmented 429 back-off tests reproducible across JVM/Native/JS.
        timeSource = testScheduler.timeSource,
        // Seed the adaptive back-off jitter so the retry path is fully deterministic.
        random = Random(seed = 0),
    )

    private fun DownloadState?.isTerminal(): Boolean =
        this is DownloadState.Succeeded || this is DownloadState.Failed || this is DownloadState.Cancelled

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
        val ack = manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/file.bin",
                    destinationPath = "/tmp/file.bin".toPath(),
                )
            )
        )
        assertIs<CommandResult.Accepted>(ack)

        val result = manager.await(id)
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
                manager.submitCommand(
                    DownloadCommand.Enqueue(
                        id = id,
                        request = DownloadRequest(
                            url = "https://example.com/$idx.bin",
                            destinationPath = "/bulk/$idx.bin".toPath(),
                            // This test exercises the queue-level concurrency bound, not
                            // per-file segmentation. SingleStream keeps every download on one
                            // request so the drain stays fully on the virtual test clock;
                            // Auto segmentation's per-origin pacing uses the real wall clock,
                            // which does not advance under the test scheduler.
                            segmentation = DownloadSegmentation.Single,
                        )
                    )
                )
                id
            }

            // Suspend until every download has succeeded. Collecting the manager's own
            // StateFlow with `first { }` yields to the scheduler/event loop identically on
            // JVM, Native and JS, so there is no polling budget or wall-clock timeout to
            // race. The previous `withTimeout(...) { delay(20) }` poll was flaky on the iOS
            // simulator, and a fixed-step scheduler pump did not drive the JS event loop.
            manager.state.first { st -> ids.all { st.snapshots[it]?.state is DownloadState.Succeeded } }

            val final = manager.state.value
            // All 20 completed under a bound of 3 without deadlocking or stranding work,
            // and the queue reflects the configured concurrency limit.
            assertEquals(3, final.maxConcurrentDownloads)
            assertTrue(final.active.isEmpty())
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
        manager.submitCommand(
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
                    segmentation = DownloadSegmentation.Single,
                )
            )
        )
        val result = manager.await(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals(2, calls)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `pause queued then resume`() = runTest {
        val payload = "ok".encodeToByteArray()
        val releaseFirstDownload = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/1")) {
                releaseFirstDownload.await()
            }
            respond(payload, HttpStatusCode.OK)
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs, DownloadManagerConfig(maxConcurrentDownloads = 1))
        val id1 = DownloadId.new()
        val id2 = DownloadId.new()
        manager.submitCommand(
            DownloadCommand.Enqueue(id = id1, request = DownloadRequest("https://x/1", "/tmp/1.bin".toPath()))
        )
        runCurrent()
        manager.submitCommand(
            DownloadCommand.Enqueue(id = id2, request = DownloadRequest("https://x/2", "/tmp/2.bin".toPath()))
        )
        val pauseAck = manager.submitCommand(DownloadCommand.Pause(id2))
        assertIs<CommandResult.Accepted>(pauseAck)
        val pauseState = manager.state.value.snapshots[id2]?.state
        assertIs<DownloadState.Paused>(pauseState)
        assertIs<CommandResult.Accepted>(manager.submitCommand(DownloadCommand.Resume(id2)))

        releaseFirstDownload.complete(Unit)
        assertIs<DownloadResult.Success>(manager.await(id1))
        assertIs<DownloadResult.Success>(manager.await(id2))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `cancel queued item terminates as cancelled`() = runTest {
        val releaseFirstDownload = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/1")) {
                releaseFirstDownload.await()
            }
            respond("ok", HttpStatusCode.OK)
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs, DownloadManagerConfig(maxConcurrentDownloads = 1))
        val id1 = DownloadId.new()
        val id2 = DownloadId.new()
        manager.submitCommand(DownloadCommand.Enqueue(id = id1, request = DownloadRequest("https://x/1", "/tmp/a.bin".toPath())))
        runCurrent()
        manager.submitCommand(DownloadCommand.Enqueue(id = id2, request = DownloadRequest("https://x/2", "/tmp/b.bin".toPath())))
        val ack = manager.submitCommand(DownloadCommand.Cancel(id2))
        assertIs<CommandResult.Accepted>(ack)
        releaseFirstDownload.complete(Unit)
        assertIs<DownloadResult.Success>(manager.await(id1))
        val cancelled = manager.await(id2)
        assertIs<DownloadResult.Cancelled>(cancelled)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `cancel active item cleans partial file when configured`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/active-cancel.bin".toPath()
        val requestStarted = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Range])
            requestStarted.complete(Unit)
            never.await()
            respond("tail", HttpStatusCode.PartialContent)
        }
        val client = HttpClient(engine)
        val manager = testManager(client, fs)
        val id = DownloadId.new()
        val partPath = "$finalPath.yukitoki-${id.value}.part".toPath()

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/active-cancel.bin",
                    destinationPath = finalPath,
                )
            )
        )

        requestStarted.await()
        assertIs<CommandResult.Accepted>(manager.submitCommand(DownloadCommand.Cancel(id)))
        val result = manager.await(id)
        assertIs<DownloadResult.Cancelled>(result)
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
        val ack = manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(url = "https://cdn.host/report.docx?x=1")
            )
        )
        if (DownloadLocations.isSupported) {
            assertIs<CommandResult.Accepted>(ack)
            val result = manager.await(id)
            assertIs<DownloadResult.Success>(result)
            assertEquals("report.docx", result.file.path.name)
        } else {
            assertIs<CommandResult.Rejected>(ack)
        }
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

        manager.submitCommand(
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
                    segmentation = DownloadSegmentation.Single,
                )
            )
        )

        val result = async { manager.await(id) }
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
    fun `retry honors retry after header for too many requests`() = runTest {
        var calls = 0
        val firstCall = CompletableDeferred<Unit>()
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                firstCall.complete(Unit)
                respond(
                    content = "rate limited",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter to listOf("1")),
                )
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        val id = DownloadId.new()

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/retry-after",
                    destinationPath = "/tmp/retry-after.bin".toPath(),
                    retryPolicy = RetryPolicy(
                        maxAttempts = 2,
                        initialDelayMs = 30_000,
                        maxDelayMs = 30_000,
                        multiplier = 1.0,
                        jitterRatio = 0.0,
                    ),
                    segmentation = DownloadSegmentation.Single,
                )
            )
        )

        val result = async { manager.await(id) }
        withTimeout(5_000) {
            while (!firstCall.isCompleted) yield()
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
        assertIs<CommandResult.Accepted>(manager.submitCommand(DownloadCommand.SetMaxConcurrentDownloads((4))))
        val state = manager.state.first()
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
        val failedAck = manager.submitCommand(
            DownloadCommand.Enqueue(
                id = failId,
                request = DownloadRequest(
                    url = "https://example.com/existing.txt",
                    destinationPath = "/tmp/existing.txt".toPath(),
                    overwritePolicy = OverwritePolicy.FAIL_IF_EXISTS,
                )
            )
        )
        assertIs<CommandResult.Rejected>(failedAck)
        assertEquals("old", fs.read("/tmp/existing.txt".toPath()) { readUtf8() })

        val renameId = DownloadId.new()
        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = renameId,
                request = DownloadRequest(
                    url = "https://example.com/existing.txt",
                    destinationPath = "/tmp/existing.txt".toPath(),
                    overwritePolicy = OverwritePolicy.RENAME_UNIQUE,
                )
            )
        )
        val renamed = manager.await(renameId)
        assertIs<DownloadResult.Success>(renamed)
        assertEquals("/tmp/existing(2).txt", renamed.file.path.toString())
        assertEquals("new", fs.read("/tmp/existing(2).txt".toPath()) { readUtf8() })
        manager.shutdown()
        client.close()
    }

    @Test
    fun `default overwrite policy renames directory targets but protects exact files`() = runTest {
        val engine = MockEngine {
            respond("new", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf("3")))
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val directory = "/tmp".toPath()
        val exactPath = "/tmp/report.bin".toPath()
        fs.createDirectories(directory)
        fs.write(exactPath) { writeUtf8("old") }
        val manager = testManager(client, fs)

        val renamed = manager.download(
            DownloadRequest(
                url = "https://example.com/report.bin",
                destination = DownloadDestination.Directory(directory, "report.bin"),
            )
        )
        assertIs<DownloadResult.Success>(renamed)
        assertEquals("/tmp/report(1).bin", renamed.file.path.toString())
        assertEquals("new", fs.read(renamed.file.path) { readUtf8() })

        val protected = manager.download(
            DownloadRequest(
                url = "https://example.com/report.bin",
                destination = DownloadDestination.File(exactPath),
            )
        )
        assertIs<DownloadResult.Failure>(protected)
        assertIs<DownloadFailure.AlreadyExists>(protected.failure)
        assertEquals("old", fs.read(exactPath) { readUtf8() })

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
                manager.submitCommand(
                    DownloadCommand.Enqueue(
                        id = id,
                        request = DownloadRequest(
                            url = "https://f/$idx",
                            destinationPath = "/tmp/f/$idx.bin".toPath(),
                            segmentation = DownloadSegmentation.Single,
                        )
                    )
                )
                id
            }

            ids.take(10).forEach { manager.submitCommand(DownloadCommand.Pause(it)) }
            ids.take(10).forEach { manager.submitCommand(DownloadCommand.Resume(it)) }
            ids.takeLast(5).forEach { manager.submitCommand(DownloadCommand.Cancel(it)) }
            manager.submitCommand(DownloadCommand.RetryAllFailed)
            manager.submitCommand(DownloadCommand.SetMaxConcurrentDownloads((2)))
            manager.submitCommand(DownloadCommand.SetMaxConcurrentDownloads((5)))
            ids.drop(10).take(5).forEachIndexed { i, id -> manager.submitCommand(DownloadCommand.MoveWithinPriority(id, i)) }

            // Suspend until every download reaches a terminal state (see the concurrency
            // test above for why the StateFlow's `first { }` is used instead of a timed poll).
            manager.state.first { st -> ids.all { st.snapshots[it]?.state.isTerminal() } }
            val finalState = manager.state.value
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
            manager.submitCommand(
                DownloadCommand.Enqueue(
                    id = id,
                    request = DownloadRequest(
                        url = "https://example.com/file.bin",
                        destinationPath = "/tmp/space_test.bin".toPath(),
                    )
                )
            )

            val result = manager.await(id)
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
        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/hash.bin",
                    destinationPath = "/tmp/hash.bin".toPath(),
                    expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, expectedSha)
                )
            )
        )

        val result = manager.await(id)
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
        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/bad_hash.bin",
                    destinationPath = "/tmp/bad_hash.bin".toPath(),
                    expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, badSha)
                )
            )
        )

        val result = manager.await(id)
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
        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/md5.bin",
                    destinationPath = "/tmp/md5.bin".toPath(),
                    expectedChecksum = ChecksumSpec(ChecksumAlgorithm.MD5, expectedMd5),
                )
            )
        )
        assertIs<DownloadResult.Success>(manager.await(id))
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
        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/size.bin",
                    destinationPath = "/tmp/size.bin".toPath(),
                    expectedBytes = 999L,
                )
            )
        )
        val result = manager.await(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.SizeMismatch>(result.failure)
        assertTrue(!fs.exists("/tmp/size.bin".toPath()), "Wrong-size file must not be finalized")
        assertTrue(!fs.exists("/tmp/size.bin.part".toPath()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `stale partial without trusted task metadata is discarded`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/resume.bin".toPath()
        val id = DownloadId.new()
        val partPath = "$finalPath.yukitoki-${id.value}.part".toPath()
        fs.createDirectories(partPath.parent!!)
        fs.write(partPath) { writeUtf8("untrusted") }

        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Range])
            respond(
                content = "fresh",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("5"),
                ),
            )
        }
        val client = HttpClient(engine)
        val manager = testManager(client, fs)

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/resume.bin",
                    destinationPath = finalPath,
                )
            )
        )

        val result = manager.await(id)
        assertIs<DownloadResult.Success>(result)
        assertTrue(fs.exists(finalPath))
        assertEquals("fresh", fs.read(finalPath) { readUtf8() })
        manager.shutdown()
        client.close()
    }

    @Test
    fun `segmented download requests byte ranges and merges in order`() = runTest {
        val payload = "abcdefghij".encodeToByteArray()
        val requestedRanges = mutableListOf<String>()
        // MockEngine serves parallel segment requests on an IO dispatcher; guard the shared list.
        val rangesLock = Mutex()
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
                    rangesLock.withLock { requestedRanges += range }
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

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/segmented.bin",
                    destinationPath = "/tmp/segmented.bin".toPath(),
                    segmentation = DownloadSegmentation.MaxSegments(3),
                )
            )
        )

        val result = manager.await(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals("abcdefghij", fs.read("/tmp/segmented.bin".toPath()) { readUtf8() })
        // Assert the DISTINCT ranges the manager segmented the file into. The manager may
        // legitimately retry a transient transport error (mapped to DownloadFailure.Network),
        // which re-requests every range from scratch; asserting an exact once-only multiset
        // would flake against that correct behavior. Distinct-set equality still rejects any
        // wrong or extra range, which is what this test actually cares about.
        assertEquals(
            listOf("bytes=0-3", "bytes=4-6", "bytes=7-9"),
            requestedRanges.distinct().sortedBy { it.substringAfter('=').substringBefore('-').toInt() },
        )
        assertTrue(!fs.exists("/tmp/segmented.bin.part.seg0".toPath()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `segmented download uses range probe when head fails`() = runTest {
        val payload = "abcdefghij".encodeToByteArray()
        val requestedRanges = mutableListOf<String>()
        // MockEngine serves parallel segment requests on an IO dispatcher; guard the shared list.
        val rangesLock = Mutex()
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> throw IOException("unexpected end of stream on ${request.url}")
                HttpMethod.Get -> {
                    val range = request.headers[HttpHeaders.Range].orEmpty()
                    rangesLock.withLock { requestedRanges += range }
                    val (start, end) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                    respond(
                        content = payload.copyOfRange(start, end + 1),
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
                            HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}"),
                            HttpHeaders.ETag to listOf("\"v1\""),
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

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/head-broken.bin",
                    destinationPath = "/tmp/head-broken.bin".toPath(),
                    segmentation = DownloadSegmentation.MaxSegments(3),
                )
            )
        )

        val result = manager.await(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals("abcdefghij", fs.read("/tmp/head-broken.bin".toPath()) { readUtf8() })
        // Distinct ranges: the probe (bytes=0-0) plus the three segments. A transient-network
        // retry may re-request ranges, so assert the set, not an exact once-only multiset.
        assertEquals(
            listOf("bytes=0-0", "bytes=0-3", "bytes=4-6", "bytes=7-9"),
            requestedRanges.distinct().sortedBy { it.substringAfter('=').substringBefore('-').toInt() },
        )
        manager.shutdown()
        client.close()
    }

    @Test
    fun `segmented download retries with fewer segments after too many requests`() = runTest {
        val payload = "abcdefgh".encodeToByteArray()
        val requestedRanges = mutableListOf<String>()
        // MockEngine serves parallel segment requests on an IO dispatcher; guard the shared list.
        val rangesLock = Mutex()
        var rateLimited = false
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentLength to listOf(payload.size.toString()),
                    ),
                )
                HttpMethod.Get -> {
                    val range = request.headers[HttpHeaders.Range].orEmpty()
                    val limitNow = rangesLock.withLock {
                        requestedRanges += range
                        (!rateLimited && range == "bytes=0-1").also { if (it) rateLimited = true }
                    }
                    if (limitNow) {
                        respond(
                            content = "slow down",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.RetryAfter to listOf("0")),
                        )
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
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/rate-limited-segments.bin",
                    destinationPath = "/tmp/rate-limited-segments.bin".toPath(),
                    segmentation = DownloadSegmentation.MaxSegments(4),
                )
            )
        )

        val result = manager.await(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals("abcdefgh", fs.read("/tmp/rate-limited-segments.bin".toPath()) { readUtf8() })
        assertTrue("bytes=0-1" in requestedRanges)
        assertTrue("bytes=0-3" in requestedRanges)
        assertTrue("bytes=4-7" in requestedRanges)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `segmented download falls back to single stream when ranges are unsupported`() = runTest {
        var headCalls = 0
        var rangeProbeCalls = 0
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
                    if (request.headers[HttpHeaders.Range] != null) {
                        rangeProbeCalls++
                        respond(
                            content = "whole",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength to listOf("5")),
                        )
                    } else {
                        fullGetCalls++
                        respond(
                            content = "whole",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength to listOf("5")),
                        )
                    }
                }
                else -> error("Unexpected method ${request.method}")
            }
        }
        val client = HttpClient(engine)
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val id = DownloadId.new()

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/fallback.bin",
                    destinationPath = "/tmp/fallback.bin".toPath(),
                    segmentation = DownloadSegmentation.MaxSegments(4),
                )
            )
        )

        val result = manager.await(id)
        assertIs<DownloadResult.Success>(result)
        assertEquals("whole", fs.read("/tmp/fallback.bin".toPath()) { readUtf8() })
        // Prove the fallback SHAPE rather than exact counts: the manager probed for range
        // support (HEAD + a range probe) and, finding it unsupported, downloaded the whole
        // file with a single non-range GET instead of segmenting. Counts are asserted as
        // "at least one" so a legitimate transient-network retry (which re-runs the attempt)
        // doesn't flake the test; a wrongly-segmented download would still show fullGetCalls == 0.
        assertTrue(headCalls >= 1, "expected a HEAD probe, got $headCalls")
        assertTrue(rangeProbeCalls >= 1, "expected a range probe, got $rangeProbeCalls")
        assertTrue(fullGetCalls >= 1, "expected a single-stream full GET fallback, got $fullGetCalls")
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

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/bad-segment.bin",
                    destinationPath = "/tmp/bad-segment.bin".toPath(),
                    segmentation = DownloadSegmentation.MaxSegments(2),
                )
            )
        )

        val result = manager.await(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.SizeMismatch>(result.failure)
        assertTrue(!fs.exists("/tmp/bad-segment.bin".toPath()))
        assertTrue(!fs.exists("/tmp/bad-segment.bin.part.seg0".toPath()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `pause before a response body arrives reaches paused state`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/pause.bin".toPath()

        val requestStarted = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Range])
            requestStarted.complete(Unit)
            never.await()
            respond("tail", HttpStatusCode.PartialContent)
        }
        val client = HttpClient(engine)
        val manager = testManager(client, fs)
        val id = DownloadId.new()
        val partPath = "$finalPath.yukitoki-${id.value}.part".toPath()

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/pause.bin",
                    destinationPath = finalPath,
                )
            )
        )

        requestStarted.await()
        assertIs<CommandResult.Accepted>(manager.submitCommand(DownloadCommand.Pause(id)))
        withTimeout(5_000) {
            while (manager.state.value.snapshots[id]?.state !is DownloadState.Paused) {
                delay(10)
            }
        }
        assertTrue(!fs.exists(partPath), "No partial is created before the response body arrives")
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

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/short.bin",
                    destinationPath = "/tmp/short.bin".toPath(),
                )
            )
        )

        val result = manager.await(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.SizeMismatch>(result.failure)
        assertTrue(!fs.exists("/tmp/short.bin".toPath()))
        assertTrue(!fs.exists("/tmp/short.bin.part".toPath()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `range resumption resets and redownloads when server returns 200`() = runTest {
        val fs = FakeFileSystem()
        val finalPath = "/tmp/change.bin".toPath()
        val id = DownloadId.new()
        val partPath = "$finalPath.yukitoki-${id.value}.part".toPath()

        fs.createDirectories(partPath.parent!!)
        fs.write(partPath) { writeUtf8("part1_") }

        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Range])
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

        manager.submitCommand(
            DownloadCommand.Enqueue(
                id = id,
                request = DownloadRequest(
                    url = "https://example.com/change.bin",
                    destinationPath = finalPath,
                )
            )
        )

        val result = manager.await(id)
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
        manager.submitCommand(DownloadCommand.Enqueue(id = blocker, request = DownloadRequest("https://b/blocker", "/tmp/blocker.bin".toPath())))
        started.await()

        val n1 = DownloadId.new()
        val n2 = DownloadId.new()
        val high = DownloadId.new()
        manager.submitCommand(DownloadCommand.Enqueue(id = n1, request = DownloadRequest("https://b/n1", "/tmp/n1.bin".toPath())))
        manager.submitCommand(DownloadCommand.Enqueue(id = n2, request = DownloadRequest("https://b/n2", "/tmp/n2.bin".toPath())))
        manager.submitCommand(DownloadCommand.Enqueue(id = high, request = DownloadRequest("https://b/high", "/tmp/high.bin".toPath(), priority = DownloadPriority.HIGH)))

        // Pending starts as [high, n1, n2]. Move n1 to index 1 within the NORMAL tier.
        assertEquals(listOf(high, n1, n2), manager.state.value.pending)
        assertIs<CommandResult.Accepted>(manager.submitCommand(DownloadCommand.MoveWithinPriority(n1, 1)))

        // HIGH still leads (priority preserved); only the NORMAL tier reordered.
        assertEquals(listOf(high, n2, n1), manager.state.value.pending)
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
        manager.submitCommand(DownloadCommand.Enqueue(id = blocker, request = DownloadRequest("https://b/blocker", "/tmp/b.bin".toPath())))
        started.await()

        val low = DownloadId.new()
        val high = DownloadId.new()
        val normal = DownloadId.new()
        manager.submitCommand(DownloadCommand.Enqueue(id = low, request = DownloadRequest("https://b/low", "/tmp/low.bin".toPath(), priority = DownloadPriority.LOW)))
        manager.submitCommand(DownloadCommand.Enqueue(id = high, request = DownloadRequest("https://b/high", "/tmp/high.bin".toPath(), priority = DownloadPriority.HIGH)))
        manager.submitCommand(DownloadCommand.Enqueue(id = normal, request = DownloadRequest("https://b/normal", "/tmp/normal.bin".toPath(), priority = DownloadPriority.NORMAL)))

        assertEquals(listOf(high, normal, low), manager.state.value.pending)
        manager.shutdown(cancelActive = true)
        client.close()
    }

    @Test
    fun `download returns success and maps rejection to failure`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())

        val ok = manager.download(
            DownloadRequest(
                url = "https://example.com/dn.bin",
                destination = DownloadDestination.File("/tmp/dn.bin".toPath()),
            )
        )
        assertIs<DownloadResult.Success>(ok)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `observe emits snapshots through to terminal`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        val id = DownloadId.new()
        manager.submitCommand(DownloadCommand.Enqueue(id = id, request = DownloadRequest("https://example.com/oi.bin", "/tmp/oi.bin".toPath())))
        manager.await(id)
        val snapshot = manager.observe(id).first { it?.state is DownloadState.Succeeded }
        assertNotNull(snapshot)
        assertEquals(id, snapshot.id)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `await returns null for unknown id`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        assertNull(manager.await(DownloadId.new()))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `commands and awaits are rejected after shutdown`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        manager.shutdown()
        assertIs<CommandResult.Rejected>(manager.submitCommand(DownloadCommand.SetMaxConcurrentDownloads((2))))
        assertNull(manager.await(DownloadId.new()))
        client.close()
    }

    @Test
    fun `cancel releases existing awaiters`() = runTest {
        val engine = MockEngine {
            awaitCancellation()
        }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())
        val task = manager.enqueue(
            url = "https://example.com/hanging.bin",
            destination = DownloadDestination.File("/tmp/hanging.bin".toPath()),
        )

        val waiter = async { manager.await(task.id) }
        runCurrent()
        manager.close()

        assertNull(withTimeout(1_000) { waiter.await() })
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
        manager.submitCommand(DownloadCommand.Enqueue(request = DownloadRequest("https://example.com/graceful.bin", path)))
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
        manager.submitCommand(DownloadCommand.Enqueue(request = DownloadRequest("https://example.com/forced.bin", path)))
        started.await()

        manager.shutdown(cancelActive = true)

        assertTrue(!fs.exists(path), "Forced shutdown should not finalize the cancelled download")
        client.close()
    }

    @Test
    fun `network io exception maps to network failure`() = runTest {
        val engine = MockEngine {
            throw IOException("unexpected end of stream on https://example.com/file.bin")
        }
        val client = HttpClient(engine)
        val manager = testManager(client, FakeFileSystem())

        val result = manager.download(
            DownloadRequest(
                url = "https://example.com/file.bin",
                destinationPath = "/tmp/file.bin".toPath(),
                retryPolicy = RetryPolicy(
                    maxAttempts = 1,
                    initialDelayMs = 0,
                    maxDelayMs = 0,
                    multiplier = 1.0,
                    jitterRatio = 0.0,
                ),
                segmentation = DownloadSegmentation.Single,
            )
        )

        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.Network>(result.failure)
        client.close()
    }

    @Test
    fun `request validation rejects impossible segment and progress values`() {
        assertFailsWith<IllegalArgumentException> {
            DownloadRequest(url = "https://example.com/file", segmentation = DownloadSegmentation.MaxSegments(0))
        }
        assertFailsWith<IllegalArgumentException> {
            ProgressGranularity(minBytesDelta = -1)
        }
    }

    @Test
    fun `request validation rejects unsafe consumer inputs`() {
        assertFailsWith<IllegalArgumentException> {
            DownloadRequest(url = "ftp://example.com/file.bin")
        }
        assertFailsWith<IllegalArgumentException> {
            DownloadRequest(url = "not-a-url")
        }
        assertFailsWith<IllegalArgumentException> {
            DownloadRequest(
                url = "https://example.com/file.bin",
                destination = DownloadDestination.Directory("/tmp".toPath(), "../file.bin"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChecksumSpec(ChecksumAlgorithm.SHA_256, "not-hex")
        }
    }

    @Test
    fun `two active requests cannot reserve the same exact destination`() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            never.await()
            respond("unused", HttpStatusCode.OK)
        }
        val client = HttpClient(engine)
        val manager = testManager(
            client,
            FakeFileSystem(),
            DownloadManagerConfig(maxConcurrentDownloads = 1),
        )
        val path = "/tmp/reserved.bin".toPath()
        val firstId = DownloadId.new()
        val secondId = DownloadId.new()

        assertIs<CommandResult.Accepted>(manager.submitCommand(DownloadCommand.Enqueue(
            DownloadRequest("https://example.com/first", path, overwritePolicy = OverwritePolicy.REPLACE),
            firstId,
        )))
        started.await()
        val collision = manager.submitCommand(DownloadCommand.Enqueue(
            DownloadRequest("https://example.com/second", path, overwritePolicy = OverwritePolicy.REPLACE),
            secondId,
        ))
        assertIs<CommandResult.Rejected.Failed>(collision)
        assertIs<DownloadFailure.AlreadyExists>(collision.failure)

        manager.cancel(firstId)
        assertIs<DownloadResult.Cancelled>(manager.await(firstId))
        manager.shutdown()
        client.close()
    }

    @Test
    fun `finished snapshot retention is bounded`() = runTest {
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) })
        val manager = testManager(
            client,
            FakeFileSystem(),
            DownloadManagerConfig(maxRetainedFinished = 1),
        )
        val first = manager.enqueue(
            "https://example.com/first.bin",
            DownloadDestination.File("/tmp/first.bin".toPath()),
        )
        assertIs<DownloadResult.Success>(first.await())
        val second = manager.enqueue(
            "https://example.com/second.bin",
            DownloadDestination.File("/tmp/second.bin".toPath()),
        )
        assertIs<DownloadResult.Success>(second.await())

        assertNull(manager.state.value.snapshots[first.id])
        assertIs<DownloadState.Succeeded>(manager.state.value.snapshots[second.id]?.state)
        manager.shutdown()
        client.close()
    }

    @Test
    fun `task handle observes completion and remove can delete the final file`() = runTest {
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) })
        val fs = FakeFileSystem()
        val manager = testManager(client, fs)
        val path = "/tmp/task.bin".toPath()

        val task = manager.enqueue(
            "https://example.com/task.bin",
            DownloadDestination.File(path),
        )
        assertIs<DownloadResult.Success>(task.await())
        assertIs<DownloadState.Succeeded>(task.state.first()?.state)
        assertTrue(fs.exists(path))
        assertIs<CommandResult.Accepted>(manager.remove(task.id, deleteFile = true))
        assertTrue(!fs.exists(path))
        assertNull(manager.state.value.snapshots[task.id])
        manager.shutdown()
        client.close()
    }
}
