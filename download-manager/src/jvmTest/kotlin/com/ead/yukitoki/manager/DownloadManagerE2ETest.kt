package com.ead.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end suite: a real CIO [HttpClient] drives [DefaultDownloadManager] over real
 * sockets against [E2EDownloadServer], writing through the real [FileSystem.SYSTEM]. Each
 * test exercises a user-facing feature exactly as an app would, with no mocks anywhere in
 * the stack.
 */
class DownloadManagerE2ETest {
    private val server = E2EDownloadServer()
    private val fs = FileSystem.SYSTEM
    private lateinit var client: HttpClient
    private lateinit var tmpDir: Path

    @BeforeTest
    fun setUp() {
        server.start()
        client = HttpClient(CIO)
        tmpDir = "${System.getProperty("java.io.tmpdir")}/yukitoki-e2e-${System.nanoTime()}".toPath()
        fs.createDirectories(tmpDir)
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.stop()
        runCatching { fs.deleteRecursively(tmpDir, mustExist = false) }
    }

    // --- helpers -----------------------------------------------------------------

    private fun dest(name: String): Path = "$tmpDir/$name".toPath()

    private fun readBytes(path: Path): ByteArray = fs.read(path) { readByteArray() }

    private fun hashHex(algorithm: String, data: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(data).joinToString("") { "%02x".format(it) }

    /** Runs [block] with a freshly built manager on a real I/O scope, then tears it down. */
    private fun runE2E(
        config: DownloadManagerConfig = DownloadManagerConfig(),
        timeoutMs: Long = 60_000,
        block: suspend CoroutineScope.(DownloadManager) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val manager = DefaultDownloadManager(
            httpClient = client,
            scope = scope,
            fileSystem = fs,
            config = config,
        )
        try {
            withTimeout(timeoutMs) { block(manager) }
        } finally {
            manager.shutdown(cancelActive = true)
            scope.cancel()
        }
    }

    private suspend fun DownloadManager.awaitSnapshot(
        id: DownloadId,
        predicate: (DownloadSnapshot) -> Boolean,
    ): DownloadSnapshot = observeItem(id).filterNotNull().first(predicate)

    private fun DownloadSnapshot.runningBytes(): Long =
        (state as? DownloadState.Running)?.progress?.bytesDownloaded ?: 0L

    // --- basic download ----------------------------------------------------------

    @Test
    fun `plain download writes exact bytes`() = runE2E { manager ->
        val id = DownloadId.new()
        val ack = manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/file"), dest("a.bin")), id))
        assertIs<CommandAck.Accepted>(ack)
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertContentEquals(server.payload, readBytes(dest("a.bin")))
    }

    @Test
    fun `downloadNow one-shot succeeds`() = runE2E { manager ->
        val result = manager.downloadNow(DownloadRequest(server.url("/file"), dest("one.bin")))
        assertIs<DownloadResult.Success>(result)
        assertEquals(server.payload.size.toLong(), result.success.bytesDownloaded)
        assertContentEquals(server.payload, readBytes(dest("one.bin")))
    }

    @Test
    fun `progress is reported and monotonic`() = runE2E { manager ->
        val id = DownloadId.new()
        val samples = mutableListOf<Long>()
        val collector = launch {
            manager.observeItem(id).filterNotNull().collect { snap ->
                (snap.state as? DownloadState.Running)?.let { samples.add(it.progress.bytesDownloaded) }
            }
        }
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("p.bin")), id))
        val result = manager.awaitTerminal(id)
        collector.cancel()
        assertIs<DownloadResult.Success>(result)
        assertTrue(samples.isNotEmpty(), "expected at least one progress sample")
        assertEquals(samples.sorted(), samples, "progress bytes must be monotonic non-decreasing")
        assertTrue(samples.last() <= server.payload.size.toLong())
    }

    // --- segmented ---------------------------------------------------------------

    @Test
    fun `segmented parallel download reconstructs file`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/file"), dest("seg.bin"), maxSegments = 4), id)
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertContentEquals(server.payload, readBytes(dest("seg.bin")))
    }

    @Test
    fun `segmented falls back to single stream when server lacks range`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/norange"), dest("fb.bin"), maxSegments = 4), id)
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertContentEquals(server.payload, readBytes(dest("fb.bin")))
    }

    // --- pause / resume ----------------------------------------------------------

    @Test
    fun `pause then resume completes via range request`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("pr.bin")), id))
        manager.awaitSnapshot(id) { it.runningBytes() >= 32 * 1024 || it.state is DownloadState.Succeeded }
        manager.command(DownloadCommand.Pause(id))
        manager.awaitSnapshot(id) { it.state is DownloadState.Paused }
        manager.command(DownloadCommand.Resume(id))
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertContentEquals(server.payload, readBytes(dest("pr.bin")))
    }

    // --- checksums ---------------------------------------------------------------

    @Test
    fun `sha-256 checksum validates`() = runE2E { manager ->
        val hex = hashHex("SHA-256", server.payload)
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                DownloadRequest(
                    server.url("/file"), dest("sha.bin"),
                    expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, hex),
                ),
                id,
            )
        )
        assertIs<DownloadResult.Success>(manager.awaitTerminal(id))
    }

    @Test
    fun `md5 checksum mismatch fails terminally`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                DownloadRequest(
                    server.url("/file"), dest("md5.bin"),
                    expectedChecksum = ChecksumSpec(ChecksumAlgorithm.MD5, "00000000000000000000000000000000"),
                    retryPolicy = noRetry(),
                ),
                id,
            )
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.ChecksumMismatch>(result.failure)
    }

    // --- overwrite policies ------------------------------------------------------

    @Test
    fun `fail-if-exists yields AlreadyExists`() = runE2E { manager ->
        val path = dest("exists.bin")
        fs.write(path) { writeUtf8("old") }
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                DownloadRequest(server.url("/file"), path, overwritePolicy = OverwritePolicy.FAIL_IF_EXISTS, retryPolicy = noRetry()),
                id,
            )
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.AlreadyExists>(result.failure)
    }

    @Test
    fun `rename-unique writes a new file and keeps the original`() = runE2E { manager ->
        val path = dest("doc.bin")
        fs.write(path) { writeUtf8("original") }
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                DownloadRequest(server.url("/file"), path, overwritePolicy = OverwritePolicy.RENAME_UNIQUE),
                id,
            )
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertTrue(result.success.finalPath.name.contains("(1)"), "expected a uniquified name, got ${result.success.finalPath}")
        assertContentEquals(server.payload, readBytes(result.success.finalPath))
        assertEquals("original", fs.read(path) { readUtf8() })
    }

    @Test
    fun `replace overwrites existing file`() = runE2E { manager ->
        val path = dest("replace.bin")
        fs.write(path) { writeUtf8("junk-junk-junk") }
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/file"), path, overwritePolicy = OverwritePolicy.REPLACE), id)
        )
        assertIs<DownloadResult.Success>(manager.awaitTerminal(id))
        assertContentEquals(server.payload, readBytes(path))
    }

    // --- retries -----------------------------------------------------------------

    @Test
    fun `retries on 503 then succeeds and counts attempts`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                DownloadRequest(
                    server.url("/flaky/r1?fail=2"), dest("flaky.bin"),
                    retryPolicy = RetryPolicy(maxAttempts = 5, initialDelayMs = 20, maxDelayMs = 40, multiplier = 1.0, jitterRatio = 0.0),
                ),
                id,
            )
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        val attempts = manager.observeState().value.snapshots[id]?.attempts ?: 0
        assertEquals(3, attempts, "expected 2 failed attempts + 1 success")
        assertContentEquals(server.payload, readBytes(dest("flaky.bin")))
    }

    @Test
    fun `http 404 fails without retry`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/notfound"), dest("nf.bin"), retryPolicy = RetryPolicy.default()), id)
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        val failure = result.failure
        assertIs<DownloadFailure.HttpStatus>(failure)
        assertEquals(404, failure.code)
        assertEquals(1, manager.observeState().value.snapshots[id]?.attempts)
    }

    @Test
    fun `retry command revives a failed download`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/flaky/r2?fail=1"), dest("retrycmd.bin"), retryPolicy = noRetry()), id)
        )
        assertIs<DownloadResult.Failure>(manager.awaitTerminal(id))
        manager.command(DownloadCommand.Retry(id))
        assertIs<DownloadResult.Success>(manager.awaitTerminal(id))
    }

    // --- redirects ---------------------------------------------------------------

    @Test
    fun `redirect is followed`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/redirect"), dest("rd.bin")), id))
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Success>(result)
        assertContentEquals(server.payload, readBytes(dest("rd.bin")))
    }

    // --- timeouts & integrity ----------------------------------------------------

    @Test
    fun `idle timeout fails a stalled stream`() = runE2E(config = DownloadManagerConfig(idleTimeoutMs = 300)) { manager ->
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/stall"), dest("stall.bin"), retryPolicy = noRetry()), id)
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.Timeout>(result.failure)
    }

    @Test
    fun `expected size mismatch fails with IntegrityMismatch`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(
            DownloadCommand.Enqueue(
                DownloadRequest(
                    server.url("/file"), dest("size.bin"),
                    expectedSizeBytes = server.payload.size + 1L,
                    retryPolicy = noRetry(),
                ),
                id,
            )
        )
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.IntegrityMismatch>(result.failure)
    }

    @Test
    fun `server changed mid-resume yields ServerChanged`() = runE2E { manager ->
        val id = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/changedetag"), dest("ce.bin"), retryPolicy = noRetry()), id))
        manager.awaitSnapshot(id) { it.runningBytes() >= 32 * 1024 || it.state is DownloadState.Succeeded }
        manager.command(DownloadCommand.Pause(id))
        manager.awaitSnapshot(id) { it.state is DownloadState.Paused }
        manager.command(DownloadCommand.Resume(id))
        val result = manager.awaitTerminal(id)
        assertIs<DownloadResult.Failure>(result)
        assertIs<DownloadFailure.ServerChanged>(result.failure)
    }

    // --- concurrency / priority / ordering ---------------------------------------

    @Test
    fun `concurrency bound is respected`() = runE2E(config = DownloadManagerConfig(maxConcurrentDownloads = 2)) { manager ->
        var maxActive = 0
        val watcher = launch {
            manager.observeState().collect { maxActive = maxOf(maxActive, it.active.size) }
        }
        val ids = (1..6).map { i ->
            DownloadId.new().also { manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("c$i.bin")), it)) }
        }
        ids.forEach { assertIs<DownloadResult.Success>(manager.awaitTerminal(it)) }
        watcher.cancel()
        assertTrue(maxActive <= 2, "active set exceeded the concurrency bound: $maxActive")
        assertTrue(maxActive >= 2, "expected downloads to overlap, max active was $maxActive")
    }

    @Test
    fun `high priority runs before normal`() = runE2E(config = DownloadManagerConfig(maxConcurrentDownloads = 1)) { manager ->
        val blocker = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("block.bin")), blocker))
        manager.awaitSnapshot(blocker) { it.state is DownloadState.Running }

        val normal = DownloadId.new()
        val high = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("n.bin"), priority = DownloadPriority.NORMAL), normal))
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("h.bin"), priority = DownloadPriority.HIGH), high))

        val order = trackTerminalOrder(manager, mapOf(high to "high", normal to "normal"))
        assertEquals(listOf("high", "normal"), order)
    }

    @Test
    fun `move reorders within the queue`() = runE2E(config = DownloadManagerConfig(maxConcurrentDownloads = 1)) { manager ->
        val blocker = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("mblock.bin")), blocker))
        manager.awaitSnapshot(blocker) { it.state is DownloadState.Running }

        val a = DownloadId.new(); val b = DownloadId.new(); val c = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("ma.bin")), a))
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("mb.bin")), b))
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("mc.bin")), c))
        // Pull c to the front of the pending tier.
        assertIs<CommandAck.Accepted>(manager.command(DownloadCommand.Move(c, 0)))

        val order = trackTerminalOrder(manager, mapOf(a to "a", b to "b", c to "c"))
        assertEquals("c", order.first(), "moved item should finish first; order was $order")
    }

    // --- bulk commands -----------------------------------------------------------

    @Test
    fun `cancel all stops every download`() = runE2E(config = DownloadManagerConfig(maxConcurrentDownloads = 2)) { manager ->
        val ids = (1..4).map { i ->
            DownloadId.new().also { manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("ca$i.bin")), it)) }
        }
        manager.awaitSnapshot(ids.first()) { it.state is DownloadState.Running }
        manager.command(DownloadCommand.CancelAll)
        ids.forEach { id ->
            val result = manager.awaitTerminal(id)
            assertIs<DownloadResult.Failure>(result)
            assertIs<DownloadFailure.CancelledByUser>(result.failure)
        }
    }

    @Test
    fun `pause all then resume all completes every download`() = runE2E(config = DownloadManagerConfig(maxConcurrentDownloads = 2)) { manager ->
        val ids = (1..3).map { i ->
            DownloadId.new().also { manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/throttled"), dest("pa$i.bin")), it)) }
        }
        manager.awaitSnapshot(ids.first()) { it.state is DownloadState.Running }
        manager.command(DownloadCommand.PauseAll)
        ids.forEach { id -> manager.awaitSnapshot(id) { it.state is DownloadState.Paused } }
        manager.command(DownloadCommand.ResumeAll)
        ids.forEach { id -> assertIs<DownloadResult.Success>(manager.awaitTerminal(id)) }
    }

    @Test
    fun `retry all failed revives failed downloads`() = runE2E { manager ->
        val a = DownloadId.new(); val b = DownloadId.new()
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/flaky/ra1?fail=1"), dest("raa.bin"), retryPolicy = noRetry()), a))
        manager.command(DownloadCommand.Enqueue(DownloadRequest(server.url("/flaky/ra2?fail=1"), dest("rab.bin"), retryPolicy = noRetry()), b))
        assertIs<DownloadResult.Failure>(manager.awaitTerminal(a))
        assertIs<DownloadResult.Failure>(manager.awaitTerminal(b))
        manager.command(DownloadCommand.RetryAllFailed)
        assertIs<DownloadResult.Success>(manager.awaitTerminal(a))
        assertIs<DownloadResult.Success>(manager.awaitTerminal(b))
    }

    // --- rejection contract ------------------------------------------------------

    @Test
    fun `wifi constraint is rejected by the default manager`() = runE2E { manager ->
        val ack = manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/file"), dest("wifi.bin"), constraints = DownloadConstraints(requireWifi = true)))
        )
        assertIs<CommandAck.Rejected>(ack)
    }

    @Test
    fun `caller supplied range header is rejected`() = runE2E { manager ->
        val ack = manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/file"), dest("range.bin"), headers = headersOf("Range", "bytes=0-10")))
        )
        assertIs<CommandAck.Rejected>(ack)
    }

    @Test
    fun `segmented post is rejected`() = runE2E { manager ->
        val ack = manager.command(
            DownloadCommand.Enqueue(DownloadRequest(server.url("/file"), dest("post.bin"), method = HttpMethod.Post, maxSegments = 4))
        )
        assertIs<CommandAck.Rejected>(ack)
    }

    // --- ordering helper ---------------------------------------------------------

    private suspend fun trackTerminalOrder(
        manager: DownloadManager,
        labels: Map<DownloadId, String>,
    ): List<String> {
        val order = mutableListOf<String>()
        val mutex = Mutex()
        val jobs = labels.map { (id, label) ->
            CoroutineScope(Dispatchers.IO).launch {
                manager.awaitTerminal(id)
                mutex.withLock { order.add(label) }
            }
        }
        jobs.forEach { it.join() }
        return order
    }

    private fun noRetry(): RetryPolicy =
        RetryPolicy(maxAttempts = 1, initialDelayMs = 1, maxDelayMs = 1, multiplier = 1.0, jitterRatio = 0.0)
}
