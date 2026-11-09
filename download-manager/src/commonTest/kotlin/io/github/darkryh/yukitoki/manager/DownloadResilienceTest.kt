package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileHandle
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import io.ktor.client.network.sockets.ConnectTimeoutException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a download does when things go wrong around it: teardown, interrupted transfers, and
 * servers that break their promises. These run on real dispatchers, because the behavior under
 * test is the interleaving of the coordinator, its workers, and teardown.
 */
class DownloadResilienceTest {

    private val payload = Random(11).nextBytes(64 * 1024)
    private val half = payload.size / 2
    private val fs = FakeFileSystem()
    private val quickRetry = RetryPolicy(
        maxAttempts = 3,
        initialDelay = 1.milliseconds,
        maxDelay = 1.milliseconds,
        multiplier = 1.0,
        jitterRatio = 0.0,
    )

    private class Recorder {
        private val lock = Mutex()
        private val seen = mutableListOf<HttpRequestData>()
        suspend fun add(request: HttpRequestData): Int = lock.withLock { seen += request; seen.size }
        suspend fun gets(): List<HttpRequestData> = lock.withLock { seen.filter { it.method == HttpMethod.Get } }
    }

    /** Runs [block] on real dispatchers with a bounded wait, so a regression fails instead of hanging. */
    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) { withTimeout(30.seconds) { block() } }
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun manager(
        config: DownloadManagerConfig = DownloadManagerConfig(),
        fileSystem: okio.FileSystem = fs,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = DefaultDownloadManager(
        httpClient = HttpClient(MockEngine { handler(it) }),
        scope = managerScope,
        fileSystem = SynchronizedFileSystem(fileSystem),
        config = config,
        ioDispatcher = Dispatchers.Default,
    )

    private fun request(
        name: String = "file.bin",
        retry: RetryPolicy = RetryPolicy.None,
        segmentation: DownloadSegmentation = DownloadSegmentation.Single,
    ) = DownloadRequest(
        url = "https://example.test/$name",
        destination = DownloadDestination.File("/$name".toPath()),
        retryPolicy = retry,
        segmentation = segmentation,
    )

    private fun fullHeaders(size: Long = payload.size.toLong(), etag: String? = "\"v1\"") = Headers.build {
        append(HttpHeaders.ContentLength, size.toString())
        etag?.let { append(HttpHeaders.ETag, it) }
    }

    /** Sends the first half of [payload] and holds the connection open until [release]. */
    private fun MockRequestHandleScope.heldBody(
        release: CompletableDeferred<Unit>,
        headers: Headers = fullHeaders(),
    ) = run {
        val channel = ByteChannel(autoFlush = true)
        managerScope.launch {
            channel.writeFully(payload, 0, half)
            channel.flush()
            release.await()
            channel.writeFully(payload, half, payload.size)
            channel.close()
        }
        respond(channel, HttpStatusCode.OK, headers)
    }

    /** Sends the first half of [payload], then drops the connection. */
    private fun MockRequestHandleScope.droppedBody(headers: Headers = fullHeaders()) = run {
        val channel = ByteChannel(autoFlush = true)
        managerScope.launch {
            channel.writeFully(payload, 0, half)
            channel.close()
        }
        respond(channel, HttpStatusCode.OK, headers)
    }

    /** Answers a `bytes=N-` or `bytes=N-M` request from [payload]. */
    private fun MockRequestHandleScope.rangeResponse(request: HttpRequestData, etag: String = "\"v1\"") = run {
        val spec = request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").split('-')
        val start = spec[0].toInt()
        val end = spec[1].ifBlank { (payload.size - 1).toString() }.toInt()
        respond(
            payload.copyOfRange(start, end + 1),
            HttpStatusCode.PartialContent,
            headersOf(
                HttpHeaders.ETag to listOf(etag),
                HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}"),
                HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
            ),
        )
    }

    private suspend fun DownloadManager.awaitRunningWithBytes(id: DownloadId) {
        state.first { (it.snapshots[id]?.state as? DownloadState.Running)?.progress?.bytesDownloaded?.let { b -> b >= half } == true }
    }

    private suspend fun DownloadManager.pauseAndSettle(id: DownloadId) {
        pause(id)
        state.first { it.snapshots[id]?.state is DownloadState.Paused }
    }

    private fun partial(name: String = "file.bin", id: DownloadId) = "/$name.yukitoki-${id.value}.part".toPath()

    // --- teardown ------------------------------------------------------------------------------

    @Test
    fun `close removes the partial of a paused download`() = realTime {
        val manager = manager { heldBody(CompletableDeferred()) }
        val task = manager.enqueue(request())
        manager.awaitRunningWithBytes(task.id)
        manager.pauseAndSettle(task.id)
        assertTrue(fs.exists(partial(id = task.id)), "a pause keeps its partial")

        manager.close()
        manager.state.first { it.snapshots[task.id]?.state == DownloadState.Cancelled }

        assertFalse(fs.exists(partial(id = task.id)))
    }

    @Test
    fun `close keeps a paused partial when configured to`() = realTime {
        val manager = manager(DownloadManagerConfig(deletePartialOnCancel = false)) { heldBody(CompletableDeferred()) }
        val task = manager.enqueue(request())
        manager.awaitRunningWithBytes(task.id)
        manager.pauseAndSettle(task.id)

        manager.close()
        manager.state.first { it.snapshots[task.id]?.state == DownloadState.Cancelled }

        assertTrue(fs.exists(partial(id = task.id)))
    }

    @Test
    fun `state reports every unfinished download as cancelled after close`() = realTime {
        val manager = manager(DownloadManagerConfig(maxConcurrentDownloads = 2)) { heldBody(CompletableDeferred()) }
        val running = manager.enqueue(request("running.bin"))
        val paused = manager.enqueue(request("paused.bin"))
        manager.awaitRunningWithBytes(running.id)
        manager.awaitRunningWithBytes(paused.id)
        manager.pauseAndSettle(paused.id)
        manager.setMaxConcurrentDownloads(1)
        val queued = manager.enqueue(request("queued.bin"))
        assertEquals(DownloadState.Queued, manager.state.value.snapshots[queued.id]?.state)

        manager.close()
        val final = manager.state.first { state ->
            listOf(running, paused, queued).all { state.snapshots[it.id]?.state == DownloadState.Cancelled }
        }

        assertTrue(final.active.isEmpty(), "active: ${final.active}")
        assertTrue(final.pending.isEmpty(), "pending: ${final.pending}")
        assertEquals(emptyList(), fs.list("/".toPath()).filter { it.name.endsWith(".part") })
    }

    @Test
    fun `an await waiting at close reports the download as cancelled`() = realTime {
        val manager = manager { heldBody(CompletableDeferred()) }
        val task = manager.enqueue(request())
        manager.awaitRunningWithBytes(task.id)
        val waiting = async { task.await() }
        val downloadCall = async { manager.download(request("other.bin")) }
        manager.state.first { it.snapshots.size == 2 }

        manager.close()

        assertEquals(DownloadResult.Cancelled, waiting.await())
        assertEquals(DownloadResult.Cancelled, downloadCall.await())
    }

    @Test
    fun `await after shutdown returns the result of a saved file`() = realTime {
        val manager = manager { respond(payload, HttpStatusCode.OK, fullHeaders()) }
        val task = manager.enqueue(request())
        val saved = assertIs<DownloadResult.Success>(task.await())

        manager.shutdown()

        assertEquals(saved, task.await())
        assertEquals(saved, manager.await(task.id))
        assertNull(manager.await(DownloadId("unknown")))
    }

    @Test
    fun `await after close returns the result of a saved file`() = realTime {
        val manager = manager { respond(payload, HttpStatusCode.OK, fullHeaders()) }
        val task = manager.enqueue(request())
        val saved = assertIs<DownloadResult.Success>(task.await())

        manager.close()

        assertEquals(saved, task.await())
    }

    @Test
    fun `await started during a draining shutdown gets the real result`() = realTime {
        val release = CompletableDeferred<Unit>()
        val manager = manager { heldBody(release) }
        val task = manager.enqueue(request())
        manager.awaitRunningWithBytes(task.id)

        val shutdown = async { manager.shutdown(cancelActive = false) }
        manager.state.first { true }
        val late = async { task.await() }
        release.complete(Unit)
        shutdown.await()

        assertIs<DownloadResult.Success>(late.await())
        assertIs<DownloadResult.Success>(task.await())
    }

    @Test
    fun `cancelling a borrowed scope settles state and removes partials`() = realTime {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DefaultDownloadManager(
            httpClient = HttpClient(MockEngine { heldBody(CompletableDeferred()) }),
            scope = scope,
            fileSystem = SynchronizedFileSystem(fs),
            ioDispatcher = Dispatchers.Default,
        )
        val task = manager.enqueue(request())
        manager.awaitRunningWithBytes(task.id)
        manager.pauseAndSettle(task.id)

        scope.cancel()
        manager.state.first { it.snapshots[task.id]?.state == DownloadState.Cancelled }

        assertFalse(fs.exists(partial(id = task.id)))
        assertEquals(DownloadResult.Cancelled, task.await())
    }

    // --- retries and interruption --------------------------------------------------------------

    @Test
    fun `pausing does not spend the retry budget`() = realTime {
        val recorder = Recorder()
        val manager = manager { request ->
            val count = recorder.add(request)
            when (count) {
                // Three attempts interrupted by a pause, then one real failure.
                1, 2, 3 -> heldBody(CompletableDeferred())
                4 -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> respond(payload, HttpStatusCode.OK, fullHeaders())
            }
        }
        val task = manager.enqueue(request(retry = quickRetry.copy(maxAttempts = 2)))
        repeat(3) {
            manager.state.first { it.snapshots[task.id]?.state is DownloadState.Running }
            manager.pauseAndSettle(task.id)
            manager.resume(task.id)
        }

        assertIs<DownloadResult.Success>(task.await())
    }

    @Test
    fun `a dropped connection is retried and resumes where it stopped`() = realTime {
        val recorder = Recorder()
        val manager = manager { request ->
            when (recorder.add(request)) {
                1 -> droppedBody()
                else -> rangeResponse(request)
            }
        }

        val result = manager.download(request(retry = quickRetry))

        assertIs<DownloadResult.Success>(result)
        assertContentEquals(payload, fs.read("/file.bin".toPath()) { readByteArray() })
        val second = recorder.gets()[1]
        assertEquals("bytes=$half-", second.headers[HttpHeaders.Range])
        assertEquals("\"v1\"", second.headers[HttpHeaders.IfRange])
    }

    @Test
    fun `a transient failure keeps the partial for the next attempt`() = realTime {
        val recorder = Recorder()
        val manager = manager { request ->
            when (recorder.add(request)) {
                1 -> droppedBody()
                2 -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> rangeResponse(request)
            }
        }

        assertIs<DownloadResult.Success>(manager.download(request(retry = quickRetry)))

        assertEquals(listOf("bytes=$half-", "bytes=$half-"), recorder.gets().drop(1).map { it.headers[HttpHeaders.Range] })
    }

    @Test
    fun `running out of retries removes the partial`() = realTime {
        val manager = manager { request ->
            if (request.headers[HttpHeaders.Range] == null) droppedBody() else respond("", HttpStatusCode.BadGateway)
        }
        val task = manager.enqueue(request(retry = quickRetry))

        val result = assertIs<DownloadResult.Failure>(task.await())

        assertIs<DownloadFailure.HttpStatus>(result.failure)
        assertFalse(fs.exists(partial(id = task.id)))
    }

    @Test
    fun `a dropped connection without retries is SizeMismatch and removes the partial`() = realTime {
        val manager = manager { droppedBody() }
        val task = manager.enqueue(request())

        val result = assertIs<DownloadResult.Failure>(task.await())

        assertIs<DownloadFailure.SizeMismatch>(result.failure)
        assertFalse(fs.exists(partial(id = task.id)))
    }

    @Test
    fun `cancelling a running download removes the partial`() = realTime {
        val manager = manager { heldBody(CompletableDeferred()) }
        val task = manager.enqueue(request())
        manager.awaitRunningWithBytes(task.id)

        task.cancel()

        assertEquals(DownloadResult.Cancelled, task.await())
        assertFalse(fs.exists(partial(id = task.id)))
    }

    // --- resuming safely -----------------------------------------------------------------------

    @Test
    fun `resume without a validator restarts instead of splicing`() = realTime {
        val recorder = Recorder()
        val noValidator = fullHeaders(etag = null)
        val manager = manager { request ->
            when (recorder.add(request)) {
                1 -> heldBody(CompletableDeferred(), noValidator)
                else -> respond(payload, HttpStatusCode.OK, noValidator)
            }
        }
        val task = manager.enqueue(request())
        manager.awaitRunningWithBytes(task.id)
        manager.pauseAndSettle(task.id)

        task.resume()

        assertIs<DownloadResult.Success>(task.await())
        assertNull(recorder.gets()[1].headers[HttpHeaders.Range], "nothing proves the file is unchanged")
        assertContentEquals(payload, fs.read("/file.bin".toPath()) { readByteArray() })
    }

    @Test
    fun `a 416 on resume restarts from the beginning`() = realTime {
        val recorder = Recorder()
        val manager = manager { request ->
            when {
                recorder.add(request) == 1 -> heldBody(CompletableDeferred())
                request.headers[HttpHeaders.Range] != null -> respond("", HttpStatusCode.RequestedRangeNotSatisfiable)
                else -> respond(payload, HttpStatusCode.OK, fullHeaders())
            }
        }
        val task = manager.enqueue(request())
        manager.awaitRunningWithBytes(task.id)
        manager.pauseAndSettle(task.id)

        task.resume()

        assertIs<DownloadResult.Success>(task.await())
        assertContentEquals(payload, fs.read("/file.bin".toPath()) { readByteArray() })
    }

    // --- servers that break their promises -----------------------------------------------------

    private fun headHeaders(etag: String = "\"v1\"") = headersOf(
        HttpHeaders.AcceptRanges to listOf("bytes"),
        HttpHeaders.ETag to listOf(etag),
        HttpHeaders.ContentLength to listOf(payload.size.toString()),
    )

    @Test
    fun `a server that ignores ranges on slices falls back to one stream`() = realTime {
        val manager = manager { request ->
            if (request.method == HttpMethod.Head) {
                respond("", HttpStatusCode.OK, headHeaders())
            } else {
                respond(payload, HttpStatusCode.OK, fullHeaders())
            }
        }
        val task = manager.enqueue(request(segmentation = DownloadSegmentation.MaxSegments(4)))

        assertIs<DownloadResult.Success>(task.await())

        assertContentEquals(payload, fs.read("/file.bin".toPath()) { readByteArray() })
        assertEquals(listOf("file.bin"), fs.list("/".toPath()).map { it.name })
    }

    private val requestsLock = Mutex()

    /** Sends the first half of the requested range, then drops the connection. */
    private fun MockRequestHandleScope.droppedSlice(request: HttpRequestData, etag: String = "\"v1\"") = run {
        val spec = request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").split('-')
        val start = spec[0].toInt()
        val end = spec[1].toInt()
        val length = end - start + 1
        val channel = ByteChannel(autoFlush = true)
        managerScope.launch {
            channel.writeFully(payload, start, start + length / 2)
            channel.close()
        }
        respond(
            channel,
            HttpStatusCode.PartialContent,
            headersOf(
                HttpHeaders.ETag to listOf(etag),
                HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}"),
                HttpHeaders.ContentLength to listOf(length.toString()),
            ),
        )
    }

    /** Serves ranges, and drops the first response that starts at [cutOn] half way through. */
    private fun segmentManager(requests: MutableList<String>, cutOn: Long) = manager { request ->
        if (request.method == HttpMethod.Head) return@manager respond(ByteArray(0), HttpStatusCode.OK, headHeaders())
        val range = request.headers[HttpHeaders.Range]!!
        val firstAtCut = requestsLock.withLock {
            requests += range
            requests.count { it.startsWith("bytes=$cutOn-") } == 1 && range.startsWith("bytes=$cutOn-")
        }
        if (firstAtCut) droppedSlice(request) else rangeResponse(request)
    }

    private fun requestedBytes(requests: List<String>) = requests.sumOf { range ->
        val (start, end) = range.removePrefix("bytes=").split('-')
        end.toLong() - start.toLong() + 1
    }

    @Test
    fun `a retry of segments only requests what is missing`() = realTime {
        val requests = mutableListOf<String>()
        val secondSegment = payload.size / 2L
        val manager = segmentManager(requests, cutOn = secondSegment)
        val retries = RetryPolicy(3, 1.milliseconds, 1.milliseconds, 1.0, 0.0)
        val result = manager.download(request(retry = retries, segmentation = DownloadSegmentation.MaxSegments(2)))

        val file = assertIs<DownloadResult.Success>(result).file
        assertContentEquals(payload, fs.read(file.path) { readByteArray() })
        val starts = requests.map { it.removePrefix("bytes=").substringBefore('-').toLong() }
        assertTrue(starts.any { it > secondSegment }, "the retry must continue the cut segment: $requests")
        assertTrue(requestedBytes(requests) < 2L * payload.size, "starting over would request it all twice: $requests")
        assertEquals(listOf("file.bin"), fs.list("/".toPath()).map { it.name })
        manager.close()
    }

    @Test
    fun `segments start over when the file changed between attempts`() = realTime {
        val requests = mutableListOf<String>()
        var version = 1
        val manager = manager { request ->
            val tag = "\"v$version\""
            if (request.method == HttpMethod.Head) {
                return@manager respond(ByteArray(0), HttpStatusCode.OK, headHeaders(tag))
            }
            val range = request.headers[HttpHeaders.Range]!!
            val isFirstCut = requestsLock.withLock {
                requests += range
                requests.size == 1
            }
            if (isFirstCut) {
                version = 2
                droppedSlice(request, tag)
            } else {
                rangeResponse(request, tag)
            }
        }
        val retries = RetryPolicy(3, 1.milliseconds, 1.milliseconds, 1.0, 0.0)
        val result = manager.download(request(retry = retries, segmentation = DownloadSegmentation.MaxSegments(2)))
        // The sibling of the cut segment can see either version first, so only the file matters.
        if (result is DownloadResult.Success) {
            assertContentEquals(payload, fs.read(result.file.path) { readByteArray() })
            // Segments race, so either can be the cut one. It restarts, and nothing continues mid-segment.
            assertTrue(requests.count { it == requests.first() } >= 2, "$requests")
            val segmentStarts = setOf(0L, payload.size / 2L)
            val starts = requests.map { it.removePrefix("bytes=").substringBefore('-').toLong() }
            assertTrue(starts.all { it in segmentStarts }, "kept bytes from the old file: $requests")
        } else {
            assertIs<DownloadFailure.ServerChanged>(assertIs<DownloadResult.Failure>(result).failure)
        }
        manager.close()
    }

    @Test
    fun `cancelling after a segment failure removes the kept segments`() = realTime {
        val manager = segmentManager(mutableListOf(), cutOn = payload.size / 2L)
        val slowRetry = RetryPolicy(3, 30.seconds, 30.seconds, 1.0, 0.0)
        val task = manager.enqueue(request(retry = slowRetry, segmentation = DownloadSegmentation.MaxSegments(2)))
        manager.state.first { it.snapshots[task.id]?.state is DownloadState.Queued && task.id !in it.active && it.snapshots[task.id]?.attempts == 1 }
        task.cancel()
        assertEquals(DownloadResult.Cancelled, task.await())
        assertEquals(emptyList(), fs.list("/".toPath()))
        manager.close()
    }

    @Test
    fun `running out of retries removes the kept segments`() = realTime {
        val manager = manager { request ->
            if (request.method == HttpMethod.Head) return@manager respond(ByteArray(0), HttpStatusCode.OK, headHeaders())
            droppedSlice(request)
        }
        val retries = RetryPolicy(2, 1.milliseconds, 1.milliseconds, 1.0, 0.0)
        assertIs<DownloadResult.Failure>(manager.download(request(retry = retries, segmentation = DownloadSegmentation.MaxSegments(2))))
        assertEquals(emptyList(), fs.list("/".toPath()))
        manager.close()
    }

    @Test
    fun `a slice with a different validator is ServerChanged`() = realTime {
        val manager = manager { request ->
            if (request.method == HttpMethod.Head) {
                respond("", HttpStatusCode.OK, headHeaders())
            } else {
                rangeResponse(request, etag = "\"v2\"")
            }
        }

        val result = assertIs<DownloadResult.Failure>(
            manager.download(request(segmentation = DownloadSegmentation.MaxSegments(2)))
        )

        assertIs<DownloadFailure.ServerChanged>(result.failure)
    }

    @Test
    fun `single stream requests ask for the bytes as stored`() = realTime {
        val recorder = Recorder()
        val manager = manager { request ->
            recorder.add(request)
            respond(payload, HttpStatusCode.OK, fullHeaders())
        }

        manager.download(request())

        assertEquals("identity", recorder.gets().single().headers[HttpHeaders.AcceptEncoding])
    }

    @Test
    fun `a body the engine decompressed is not measured against the compressed length`() = realTime {
        val compressedLength = (payload.size / 3).toString()
        val manager = manager {
            respond(
                payload,
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentEncoding to listOf("gzip"),
                    HttpHeaders.ContentLength to listOf(compressedLength),
                ),
            )
        }

        assertIs<DownloadResult.Success>(manager.download(request()))

        assertContentEquals(payload, fs.read("/file.bin".toPath()) { readByteArray() })
    }

    @Test
    fun `an empty file downloads`() = realTime {
        val manager = manager { respond(ByteArray(0), HttpStatusCode.OK, fullHeaders(size = 0)) }

        val result = assertIs<DownloadResult.Success>(manager.download(request()))

        assertEquals(0L, result.file.sizeBytes)
        assertTrue(fs.exists("/file.bin".toPath()))
    }

    // --- file names ----------------------------------------------------------------------------

    private suspend fun nameFor(url: String): String {
        fs.createDirectories("/dl".toPath())
        val manager = manager { respond(payload, HttpStatusCode.OK, fullHeaders()) }
        val result = manager.download(DownloadRequest(url, DownloadDestination.Directory("/dl".toPath())))
        return assertIs<DownloadResult.Success>(result).file.path.name
    }

    @Test
    fun `a percent encoded file name is decoded`() = realTime {
        assertEquals("annual report.pdf", nameFor("https://example.test/annual%20report.pdf"))
    }

    @Test
    fun `an encoded path separator cannot leave the directory`() = realTime {
        val manager = manager { respond(payload, HttpStatusCode.OK, fullHeaders()) }
        fs.createDirectories("/dl".toPath())
        val result = manager.download(
            DownloadRequest("https://example.test/..%2F..%2Fescape.bin", DownloadDestination.Directory("/dl".toPath()))
        )
        val path = assertIs<DownloadResult.Success>(result).file.path
        assertEquals("/dl".toPath(), path.parent)
    }

    @Test
    fun `a URL ending in dots falls back to a generated name`() = realTime {
        val name = nameFor("https://example.test/a/..")
        assertTrue(name.startsWith("download-"), name)
    }

    @Test
    fun `a URL with no path falls back to a generated name`() = realTime {
        val name = nameFor("https://example.test")
        assertTrue(name.startsWith("download-"), name)
    }

    @Test
    fun `intent names follow the same rules`() {
        assertEquals("annual report.pdf", intentFileNameFor(DownloadIntentRequest("https://example.test/annual%20report.pdf")))
        assertEquals("download.bin", intentFileNameFor(DownloadIntentRequest("https://example.test/a/..")))
        assertEquals("download.bin", intentFileNameFor(DownloadIntentRequest("https://example.test")))
    }

    // --- failure types -------------------------------------------------------------------------

    @Test
    fun `an IO error from the network is Network whatever its message says`() = realTime {
        for (message in listOf("", "boom", "nonexistent.invalid")) {
            val manager = manager { throw IOException(message) }
            val result = assertIs<DownloadResult.Failure>(manager.download(request("n${message.length}.bin")))
            assertIs<DownloadFailure.Network>(result.failure, "message '$message'")
            manager.close()
        }
    }

    @Test
    fun `an engine failure is Network on every platform and is retried`() = realTime {
        // Ktor's engines throw this class, which is not Okio's IOException on native.
        var calls = 0
        val manager = manager {
            calls++
            throw kotlinx.io.IOException("Exception in http request")
        }
        val retries = RetryPolicy(maxAttempts = 2, initialDelay = 1.milliseconds, maxDelay = 1.milliseconds, multiplier = 1.0, jitterRatio = 0.0)
        val result = assertIs<DownloadResult.Failure>(manager.download(request(retry = retries)))
        assertIs<DownloadFailure.Network>(result.failure)
        assertEquals(2, calls)
        manager.close()
    }

    @Test
    fun `an engine timeout is Timeout`() = realTime {
        val manager = manager { throw ConnectTimeoutException("connect timed out") }
        val result = assertIs<DownloadResult.Failure>(manager.download(request()))
        assertIs<DownloadFailure.Timeout>(result.failure)
        manager.close()
    }

    @Test
    fun `an illegal state from the engine is not blamed on the disk`() = realTime {
        val manager = manager { throw IllegalStateException("engine broke") }
        val result = assertIs<DownloadResult.Failure>(manager.download(request()))
        assertIs<DownloadFailure.Unexpected>(result.failure)
        manager.close()
    }

    @Test
    fun `an IO error from the network is retried`() = realTime {
        val recorder = Recorder()
        val manager = manager { request ->
            if (recorder.add(request) == 1) throw IOException() else respond(payload, HttpStatusCode.OK, fullHeaders())
        }

        assertIs<DownloadResult.Success>(manager.download(request(retry = quickRetry)))
    }

    private class FailingOpen(private val message: String, delegate: okio.FileSystem) : ForwardingFileSystem(delegate) {
        override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
            throw IOException(message)
    }

    @Test
    fun `an IO error from the disk is a file system failure and is not retried`() = realTime {
        val recorder = Recorder()
        val manager = manager(fileSystem = FailingOpen("broken volume", fs)) { request ->
            recorder.add(request)
            respond(payload, HttpStatusCode.OK, fullHeaders())
        }

        val result = assertIs<DownloadResult.Failure>(manager.download(request(retry = quickRetry)))

        assertIs<DownloadFailure.FileSystem>(result.failure)
        assertEquals(1, recorder.gets().size)
    }

    @Test
    fun `a denied write is PermissionDenied with its path`() = realTime {
        val manager = manager(fileSystem = FailingOpen("Permission denied", fs)) {
            respond(payload, HttpStatusCode.OK, fullHeaders())
        }

        val result = assertIs<DownloadResult.Failure>(manager.download(request()))

        assertEquals("/file.bin".toPath(), assertIs<DownloadFailure.PermissionDenied>(result.failure).path)
    }
}
