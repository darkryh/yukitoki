package io.github.darkryh.yukitoki.integration

import io.github.darkryh.yukitoki.manager.ChecksumAlgorithm
import io.github.darkryh.yukitoki.manager.ChecksumSpec
import io.github.darkryh.yukitoki.manager.CommandResult
import io.github.darkryh.yukitoki.manager.DownloadDestination
import io.github.darkryh.yukitoki.manager.DownloadFailure
import io.github.darkryh.yukitoki.manager.DownloadId
import io.github.darkryh.yukitoki.manager.DownloadLocations
import io.github.darkryh.yukitoki.manager.DownloadManager
import io.github.darkryh.yukitoki.manager.DownloadManagerConfig
import io.github.darkryh.yukitoki.manager.DownloadRequest
import io.github.darkryh.yukitoki.manager.DownloadResult
import io.github.darkryh.yukitoki.manager.DownloadSegmentation
import io.github.darkryh.yukitoki.manager.DownloadState
import io.github.darkryh.yukitoki.manager.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** This platform's file system, manager and engine details. */
internal expect val checkFileSystem: FileSystem
internal expect val platformName: String

/** Trusts the server's test certificate, using the engine the platform bundles. */
internal expect fun newManager(config: DownloadManagerConfig = DownloadManagerConfig()): DownloadManager

/** Whether the engine tells Ktor which HTTP version a response used. */
internal expect val engineReportsHttpVersion: Boolean

/** Whether this platform can write to a volume on the machine running the server. */
internal expect val canReachHostVolume: Boolean

/** Heap in use, where the platform can tell, for checking large files stay out of memory. */
internal expect fun usedHeapBytes(): Long?

internal const val HTTP1 = "http://127.0.0.1:8766"
internal const val HTTP2 = "https://localhost:8767"
internal const val MIB = 1024L * 1024L

/** Past Int.MAX_VALUE, where an offset kept in an Int would overflow. */
private const val LARGE_SIZE = 2_684_354_560L // 2.5 GiB
private const val LARGE_RATE_FREE_PAUSE_AT = 2_300_000_000L
private const val MEMORY_LIMIT_BYTES = 200 * MIB

// Hosts built for repeated downloads, serving files that never change. Don't point these at a free
// speed-test mirror: they rate limit, then silently drop your IP, which looks like a Yukitoki bug.
internal const val REAL_CDN_URL = "https://nodejs.org/dist/v22.11.0/node-v22.11.0-linux-x64.tar.xz"
internal const val REAL_CDN_SIZE = 29_235_364L

/** From Node's published SHASUMS256.txt, so it doesn't rely on any download made here. */
internal const val REAL_CDN_SHA = "83bf07dd343002a26211cf1fcd46a9d9534219aad42ee02847816940bf610a72"

/** Big enough to still be running when a test cuts the network. */
internal const val BIG_CDN_URL = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
internal const val BIG_CDN_SHA = "2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"

internal val quickRetries = RetryPolicy(
    maxAttempts = 10,
    initialDelay = 50.milliseconds,
    maxDelay = 200.milliseconds,
    multiplier = 1.0,
    jitterRatio = 0.0,
)

internal data class LoggedRequest(val method: String, val range: String, val httpVersion: String)

private val serverClient = HttpClient()

private suspend fun serverText(path: String) = serverClient.get("$HTTP1$path").bodyAsText()

internal suspend fun serverSha(size: Long) = serverText("/sha?size=$size")

internal suspend fun serverGets(name: String): List<LoggedRequest> =
    serverText("/log?name=$name").lines().filter { it.isNotEmpty() }.map {
        val (method, range, version) = it.split('|')
        LoggedRequest(method, range, version)
    }.filter { it.method == "GET" }

/** Bytes the client asked for across ranged requests. Starting over asks for the whole file again. */
internal suspend fun requestedBytes(name: String): Long = serverGets(name).sumOf { request ->
    val (start, end) = request.range.removePrefix("bytes=").split('-')
    end.toLong() - start.toLong() + 1
}

internal suspend fun enabledChecks(): Map<String, String> =
    serverText("/config").lines().associate { it.substringBefore('=') to it.substringAfter('=') }

internal fun skip(check: String, reason: String) {
    println("[$platformName] SKIPPED $check: $reason")
}

/** Real time on real sockets, with one directory per test that's removed afterwards. */
internal fun realTest(limit: Duration = 2.minutes, block: suspend CoroutineScope.(Path) -> Unit): TestResult =
    runTest(timeout = limit) {
        withContext(Dispatchers.Default) {
            val directory = DownloadLocations.downloads / "integration-tests-${Random.nextLong().toULong()}"
            checkFileSystem.createDirectories(directory)
            try {
                block(directory)
            } finally {
                checkFileSystem.deleteRecursively(directory)
            }
        }
    }

internal fun uniqueName() = "f${Random.nextLong().toULong()}.bin"

internal fun fileUrl(base: String, name: String, size: Long, query: String = "") = "$base/f/$name?size=$size&$query"

internal suspend fun fileSha(path: Path): String = withContext(Dispatchers.Default) {
    val hashing = HashingSink.sha256(blackholeSink())
    checkFileSystem.source(path).buffer().use { it.readAll(hashing) }
    hashing.hash.hex()
}

/** The saved file matches the server and nothing else is left in the directory. */
internal suspend fun assertSavedIntact(directory: Path, name: String, size: Long, result: DownloadResult?) {
    val success = assertIs<DownloadResult.Success>(result, "download result: $result")
    assertEquals(size, success.file.sizeBytes)
    assertEquals(serverSha(size), fileSha(directory / name), "file contents")
    assertEquals(listOf(name), checkFileSystem.list(directory).map { it.name }, "files left behind")
}

/** Returns once enough bytes arrived, or when the download ended first, so a failure can't hang a test. */
internal suspend fun DownloadManager.awaitBytes(id: DownloadId, bytes: Long) {
    state.first { current ->
        when (val snapshot = current.snapshots[id]?.state) {
            is DownloadState.Running -> snapshot.progress.bytesDownloaded >= bytes
            is DownloadState.Succeeded, is DownloadState.Failed, DownloadState.Cancelled -> true
            else -> false
        }
    }
}

internal suspend fun DownloadManager.pauseAndResume(id: DownloadId) {
    if (pause(id) != CommandResult.Accepted) return
    state.first { it.snapshots[id]?.state is DownloadState.Paused }
    resume(id)
}

private fun request(
    url: String,
    path: Path,
    segmentation: DownloadSegmentation = DownloadSegmentation.Single,
    retry: RetryPolicy = RetryPolicy.None,
    checksum: ChecksumSpec? = null,
) = DownloadRequest(
    url = url,
    destination = DownloadDestination.File(path),
    retryPolicy = retry,
    segmentation = segmentation,
    expectedChecksum = checksum,
)

/**
 * These use retries, as a real app does. OkHttp occasionally sees this server reset a stream while
 * several are open. It hasn't been explained yet, and the file still has to arrive intact.
 */
class Http2Test {
    private val size = 64 * MIB

    @Test
    fun `a whole file arrives over HTTP 2`() = realTest { dir ->
        newManager().use { manager ->
            val name = uniqueName()
            val result = manager.download(request(fileUrl(HTTP2, name, size), dir / name, retry = quickRetries))
            assertSavedIntact(dir, name, size, result)
            assertTrue(serverGets(name).all { it.httpVersion == "2.0" })
        }
    }

    @Test
    fun `explicit segments over HTTP 2 arrive intact`() = realTest { dir ->
        newManager().use { manager ->
            val name = uniqueName()
            val result = manager.download(
                request(fileUrl(HTTP2, name, size), dir / name, DownloadSegmentation.MaxSegments(8), quickRetries),
            )
            assertSavedIntact(dir, name, size, result)
            val gets = serverGets(name)
            assertTrue(gets.size > 1, "expected several segments, got $gets")
            assertTrue(gets.all { it.httpVersion == "2.0" }, "$gets")
        }
    }

    @Test
    fun `auto uses one stream over HTTP 2 and segments over HTTP 1`() = realTest { dir ->
        newManager().use { manager ->
            val overTwo = uniqueName()
            assertSavedIntact(
                dir, overTwo, size,
                manager.download(request(fileUrl(HTTP2, overTwo, size), dir / overTwo, DownloadSegmentation.Auto, quickRetries)),
            )
            val twoGets = serverGets(overTwo)
            println("[$platformName] MEASURE auto over HTTP/2 used ${twoGets.size} requests")
            if (engineReportsHttpVersion) {
                // One stream asks for the whole file. Segments all ask for ranges.
                assertTrue(twoGets.first().range.isEmpty(), "$twoGets")
            }

            val overOne = uniqueName()
            val oneDir = dir / "one"
            checkFileSystem.createDirectories(oneDir)
            assertSavedIntact(
                oneDir, overOne, size,
                manager.download(request(fileUrl(HTTP1, overOne, size), oneDir / overOne, DownloadSegmentation.Auto, quickRetries)),
            )
            val oneGets = serverGets(overOne)
            assertTrue(oneGets.size > 1, "expected segments over HTTP/1.1, got $oneGets")
        }
    }

    @Test
    fun `pause and resume over HTTP 2 continues where it stopped`() = realTest { dir ->
        newManager().use { manager ->
            val name = uniqueName()
            val task = manager.enqueue(request(fileUrl(HTTP2, name, size, "rate=40"), dir / name, retry = quickRetries))
            manager.awaitBytes(task.id, 8 * MIB)
            manager.pauseAndResume(task.id)
            assertSavedIntact(dir, name, size, task.await())
            val ranges = serverGets(name).map { it.range }
            assertTrue(ranges.size >= 2 && ranges[1].startsWith("bytes="), "the second request should resume: $ranges")
        }
    }

    @Test
    fun `a reset stream is retried and resumes`() = realTest { dir ->
        newManager().use { manager ->
            val name = uniqueName()
            val result = manager.download(
                request(fileUrl(HTTP2, name, size, "cuts=2&cut=reset"), dir / name, retry = quickRetries),
            )
            assertSavedIntact(dir, name, size, result)
            val gets = serverGets(name)
            assertEquals(3, gets.size, "$gets")
            assertTrue(gets[1].range.isNotEmpty() && gets[2].range.isNotEmpty(), "retries should resume: $gets")
        }
    }

    @Test
    fun `a closed connection is retried and resumes`() = realTest { dir ->
        newManager().use { manager ->
            val name = uniqueName()
            val result = manager.download(
                request(fileUrl(HTTP2, name, size, "cuts=2&cut=goaway"), dir / name, retry = quickRetries),
            )
            assertSavedIntact(dir, name, size, result)
        }
    }

    /** Retrying on the same connection would never get an answer, however many attempts are left. */
    @Test
    fun `a connection that stops answering is replaced`() = realTest { dir ->
        newManager(DownloadManagerConfig(idleTimeout = 2.seconds)).use { manager ->
            val name = uniqueName()
            val result = manager.download(request(fileUrl(HTTP2, name, size, "freeze=1"), dir / name, retry = quickRetries))
            assertSavedIntact(dir, name, size, result)
        }
    }

    @Test
    fun `segments recover when their connection stops answering`() = realTest { dir ->
        newManager(DownloadManagerConfig(idleTimeout = 2.seconds)).use { manager ->
            val name = uniqueName()
            val result = manager.download(
                request(fileUrl(HTTP2, name, size, "freeze=1"), dir / name, DownloadSegmentation.MaxSegments(4), quickRetries),
            )
            assertSavedIntact(dir, name, size, result)
        }
    }

    @Test
    fun `downloads sharing one connection do not mix their bytes`() = realTest { dir ->
        newManager().use { manager ->
            val sizes = listOf(8 * MIB, 16 * MIB, 24 * MIB)
            val saved = sizes.map { fileSize ->
                val name = uniqueName()
                async {
                    val request = request(fileUrl(HTTP2, name, fileSize), dir / name, retry = quickRetries)
                    Triple(name, fileSize, manager.download(request))
                }
            }.awaitAll()
            for ((name, fileSize, result) in saved) {
                assertIs<DownloadResult.Success>(result, "$result")
                assertEquals(serverSha(fileSize), fileSha(dir / name))
            }
        }
    }
}

class FlakyNetworkTest {
    private val size = 32 * MIB

    private fun bases() = listOf(HTTP1, HTTP2)

    @Test
    fun `repeated drops on one stream still give the exact file`() = realTest { dir ->
        newManager().use { manager ->
            for (base in bases()) {
                val name = uniqueName()
                val result = manager.download(request(fileUrl(base, name, size, "cuts=5"), dir / name, retry = quickRetries))
                assertIs<DownloadResult.Success>(result, "$base: $result")
                assertEquals(serverSha(size), fileSha(dir / name), base)
                assertEquals(6, serverGets(name).size, base)
            }
        }
    }

    @Test
    fun `drops while downloading segments still give the exact file`() = realTest { dir ->
        newManager().use { manager ->
            for (base in bases()) {
                val name = uniqueName()
                val result = manager.download(
                    request(
                        fileUrl(base, name, size, "cuts=3"),
                        dir / name,
                        DownloadSegmentation.MaxSegments(4),
                        quickRetries,
                    ),
                )
                assertIs<DownloadResult.Success>(result, "$base: $result")
                assertEquals(serverSha(size), fileSha(dir / name), base)
                assertTrue(requestedBytes(name) < 2 * size, "$base: retries must not restart segments")
            }
        }
    }

    @Test
    fun `pausing segments keeps their progress`() = realTest { dir ->
        newManager().use { manager ->
            for (base in bases()) {
                val name = uniqueName()
                val task = manager.enqueue(
                    request(fileUrl(base, name, size, "rate=10"), dir / name, DownloadSegmentation.MaxSegments(4)),
                )
                manager.awaitBytes(task.id, size / 2)
                manager.pauseAndResume(task.id)
                assertIs<DownloadResult.Success>(task.await(), base)
                assertEquals(serverSha(size), fileSha(dir / name), base)
                assertTrue(requestedBytes(name) < 2 * size, "$base: resuming must not restart segments")
            }
        }
    }

    @Test
    fun `a stalled connection times out and the retry finishes the file`() = realTest { dir ->
        newManager(DownloadManagerConfig(idleTimeout = 1.seconds)).use { manager ->
            for (base in bases()) {
                val name = uniqueName()
                val result = manager.download(
                    request(fileUrl(base, name, size, "stallfirst=1"), dir / name, retry = quickRetries),
                )
                assertIs<DownloadResult.Success>(result, "$base: $result")
                assertEquals(serverSha(size), fileSha(dir / name), base)
                assertTrue(serverGets(name)[1].range.isNotEmpty(), "$base should resume")
            }
        }
    }

    @Test
    fun `retry works after the attempts run out`() = realTest { dir ->
        newManager().use { manager ->
            for (base in bases()) {
                val name = uniqueName()
                val twoAttempts = quickRetries.copy(maxAttempts = 2)
                val task = manager.enqueue(request(fileUrl(base, name, size, "cuts=2"), dir / name, retry = twoAttempts))
                assertIs<DownloadResult.Failure>(task.await(), base)
                assertEquals(emptyList(), checkFileSystem.list(dir).filter { it.name.startsWith(name) }, base)
                task.retry()
                assertIs<DownloadResult.Success>(manager.state.first { state ->
                    state.snapshots[task.id]?.state is DownloadState.Succeeded
                }.let { task.await() }, base)
                assertEquals(serverSha(size), fileSha(dir / name), base)
            }
        }
    }
}

class LargeFileTest {
    private suspend fun whenEnabled(block: suspend () -> Unit) {
        if (enabledChecks()["large"] == "true") block() else skip("large files", "needs --large")
    }

    /** Fails the test if the heap grows with the file, which would mean it's held in memory. */
    private suspend fun <T> measuringMemory(label: String, block: suspend () -> T): T = withContext(Dispatchers.Default) {
        var peak = 0L
        val sampler = launch {
            while (isActive) {
                usedHeapBytes()?.let { if (it > peak) peak = it }
                delay(100)
            }
        }
        val started = TimeSource.Monotonic.markNow()
        val result = block()
        sampler.cancel()
        val seconds = started.elapsedNow().inWholeMilliseconds / 1000.0
        println("[$platformName] MEASURE $label: ${seconds}s, peak heap ${peak / MIB} MiB")
        assertTrue(peak < MEMORY_LIMIT_BYTES, "$label peaked at ${peak / MIB} MiB of heap")
        result
    }

    @Test
    fun `a file over 2 GiB pauses past the Int limit and passes its checksum`() = realTest(20.minutes) { dir ->
        whenEnabled {
            newManager().use { manager ->
                val name = uniqueName()
                val checksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, serverSha(LARGE_SIZE))
                val result = measuringMemory("single stream with a pause") {
                    val task = manager.enqueue(request(fileUrl(HTTP1, name, LARGE_SIZE), dir / name, checksum = checksum))
                    manager.awaitBytes(task.id, LARGE_RATE_FREE_PAUSE_AT)
                    manager.pauseAndResume(task.id)
                    task.await()
                }
                assertSavedIntact(dir, name, LARGE_SIZE, result)
                val resumedFrom = serverGets(name).last().range
                val offset = assertNotNull(resumedFrom.removePrefix("bytes=").substringBefore('-').toLongOrNull(), resumedFrom)
                assertTrue(offset > Int.MAX_VALUE, "resumed from $offset")
            }
        }
    }

    @Test
    fun `a file over 2 GiB arrives intact in segments`() = realTest(20.minutes) { dir ->
        whenEnabled {
            newManager().use { manager ->
                val name = uniqueName()
                val result = measuringMemory("four segments") {
                    manager.download(
                        request(fileUrl(HTTP1, name, LARGE_SIZE), dir / name, DownloadSegmentation.MaxSegments(4)),
                    )
                }
                assertSavedIntact(dir, name, LARGE_SIZE, result)
            }
        }
    }
}

class DiskFullTest {
    private val size = 64 * MIB

    private suspend fun fullVolume(block: suspend (Path) -> Unit) {
        val volume = enabledChecks()["diskFull"].orEmpty()
        when {
            volume.isEmpty() -> skip("disk full", "needs a small volume from run.sh")
            !canReachHostVolume -> skip("disk full", "$platformName can't reach the volume")
            else -> block(volume.toPath())
        }
    }

    private suspend fun assertFailsWithNoSpace(segmentation: DownloadSegmentation) = fullVolume { volume ->
        newManager().use { manager ->
            val name = uniqueName()
            val result = manager.download(request(fileUrl(HTTP1, name, size), volume / name, segmentation))
            val failure = assertIs<DownloadResult.Failure>(result, "$result")
            assertIs<DownloadFailure.NoSpace>(failure.failure, "$failure")
            val left = checkFileSystem.list(volume).filter { it.name.startsWith(name) }
            assertEquals(emptyList(), left, "nothing should stay on the full disk")

            // The manager keeps working, and the space came back.
            val small = uniqueName()
            val saved = manager.download(request(fileUrl(HTTP1, small, MIB), volume / small))
            assertIs<DownloadResult.Success>(saved, "$saved")
            checkFileSystem.delete(volume / small)
        }
    }

    @Test
    fun `a full disk on one stream is NoSpace and leaves nothing behind`() = realTest {
        assertFailsWithNoSpace(DownloadSegmentation.Single)
    }

    @Test
    fun `a full disk with segments is NoSpace and leaves nothing behind`() = realTest {
        assertFailsWithNoSpace(DownloadSegmentation.MaxSegments(4))
    }
}

class RealCdnTest {
    private val patient = RetryPolicy(
        maxAttempts = 6,
        initialDelay = 1.seconds,
        maxDelay = 30.seconds,
        multiplier = 2.0,
        jitterRatio = 0.0,
    )

    private suspend fun assertSaved(label: String, path: Path, result: DownloadResult) {
        assertIs<DownloadResult.Success>(result, "$label: $result")
        assertEquals(REAL_CDN_SIZE, checkFileSystem.metadata(path).size, label)
        assertEquals(REAL_CDN_SHA, fileSha(path), label)
    }

    @Test
    fun `one stream and segments and a resume give the published file from a real CDN`() = realTest(10.minutes) { dir ->
        if (enabledChecks()["realCdn"] != "true") return@realTest skip("real CDN", "needs --real-cdn")
        newManager().use { manager ->
            val single = dir / "single.bin"
            assertSaved("one stream", single, manager.download(request(REAL_CDN_URL, single, retry = patient)))

            val auto = dir / "auto.bin"
            assertSaved("auto", auto, manager.download(request(REAL_CDN_URL, auto, DownloadSegmentation.Auto, patient)))

            val segments = dir / "segments.bin"
            assertSaved(
                "segments", segments,
                manager.download(request(REAL_CDN_URL, segments, DownloadSegmentation.MaxSegments(4), patient)),
            )

            val resumed = dir / "resumed.bin"
            val task = manager.enqueue(request(REAL_CDN_URL, resumed, retry = patient))
            manager.awaitBytes(task.id, 5 * MIB)
            manager.pauseAndResume(task.id)
            assertSaved("resume", resumed, task.await())
        }
    }
}

private inline fun <T> DownloadManager.use(block: (DownloadManager) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
