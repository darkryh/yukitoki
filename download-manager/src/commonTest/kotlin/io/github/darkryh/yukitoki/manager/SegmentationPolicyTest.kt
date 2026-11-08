package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.callContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** How many requests a download makes, and how parallel it is allowed to be. */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, InternalAPI::class)
class SegmentationPolicyTest {

    private data class Seen(val method: HttpMethod, val path: String, val range: String?)

    private class Recorder {
        private val lock = Mutex()
        private val seen = mutableListOf<Seen>()

        suspend fun add(request: HttpRequestData) = lock.withLock {
            seen += Seen(request.method, request.url.encodedPath, request.headers[HttpHeaders.Range])
        }

        suspend fun forPath(path: String): List<Seen> = lock.withLock { seen.filter { it.path == path } }
    }

    private val payload = ByteArray(4096) { (it % 251).toByte() }

    private fun rangeHeaders(contentLength: Long) = headersOf(
        HttpHeaders.AcceptRanges to listOf("bytes"),
        HttpHeaders.ETag to listOf("\"v1\""),
        HttpHeaders.ContentLength to listOf(contentLength.toString()),
    )

    /** Serves [payload] with full range support. */
    private fun MockRequestHandleScope.serve(request: HttpRequestData) = run {
        val range = request.headers[HttpHeaders.Range]
        when {
            request.method == HttpMethod.Head -> respond("", HttpStatusCode.OK, rangeHeaders(payload.size.toLong()))
            range != null -> {
                val spec = range.removePrefix("bytes=").split('-')
                val start = spec[0].toInt()
                val end = spec[1].toInt()
                respond(
                    payload.copyOfRange(start, end + 1),
                    HttpStatusCode.PartialContent,
                    headersOf(
                        HttpHeaders.ETag to listOf("\"v1\""),
                        HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}"),
                        HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
                    ),
                )
            }
            else -> respond(payload, HttpStatusCode.OK, rangeHeaders(payload.size.toLong()))
        }
    }

    private fun TestScope.manager(client: HttpClient) = DefaultDownloadManager(
        httpClient = client,
        scope = backgroundScope,
        fileSystem = SynchronizedFileSystem(FakeFileSystem()),
        config = DownloadManagerConfig(idleTimeout = Duration.ZERO),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        random = Random(seed = 0),
    )

    private fun request(
        name: String,
        segmentation: DownloadSegmentation,
        expectedBytes: Long? = null,
    ) = DownloadRequest(
        url = "https://example.test/$name",
        destination = DownloadDestination.File("/$name".toPath()),
        retryPolicy = RetryPolicy.None,
        segmentation = segmentation,
        expectedBytes = expectedBytes,
    )

    private suspend fun Recorder.segmentRequests(name: String) =
        forPath("/$name").count { it.method == HttpMethod.Get && it.range != null && it.range != "bytes=0-0" }

    @Test
    fun `a small file does not lower the segment count for later large files`() = runTest {
        val recorder = Recorder()
        val manager = manager(
            HttpClient(MockEngine { request ->
                recorder.add(request)
                serve(request)
            })
        )

        assertIs<DownloadResult.Success>(manager.download(request("small.bin", DownloadSegmentation.MaxSegments(2))))
        assertIs<DownloadResult.Success>(manager.download(request("large.bin", DownloadSegmentation.MaxSegments(4))))

        assertEquals(2, recorder.segmentRequests("small.bin"))
        assertEquals(4, recorder.segmentRequests("large.bin"))
    }

    @Test
    fun `parallelism recovers after rate limiting as downloads succeed`() = runTest {
        val recorder = Recorder()
        val rangeRequests = AtomicInt(0)
        val manager = manager(
            HttpClient(MockEngine { request ->
                recorder.add(request)
                // Only the first: it may cancel its siblings before they ever send a request.
                val limited = request.url.encodedPath == "/limited.bin" &&
                    request.headers[HttpHeaders.Range] != null &&
                    rangeRequests.addAndFetch(1) == 1
                if (limited) {
                    respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0"))
                } else {
                    serve(request)
                }
            })
        )

        assertIs<DownloadResult.Success>(manager.download(request("limited.bin", DownloadSegmentation.MaxSegments(4))))
        assertIs<DownloadResult.Success>(manager.download(request("next.bin", DownloadSegmentation.MaxSegments(4))))
        assertIs<DownloadResult.Success>(manager.download(request("last.bin", DownloadSegmentation.MaxSegments(4))))

        // The 429 halved the limit from 4 to 2. Each success earns one back.
        assertEquals(3, recorder.segmentRequests("next.bin"))
        assertEquals(4, recorder.segmentRequests("last.bin"))
    }

    @Test
    fun `whole file requests wait out a rate limit penalty`() = runTest {
        val rateLimited = CompletableDeferred<Unit>()
        val wholeFileRequests = AtomicInt(0)
        // The penalty runs on this clock, so it stays active until the test advances it.
        val penaltyClock = TestTimeSource()
        val manager = DefaultDownloadManager(
            httpClient = HttpClient(MockEngine { request ->
                when {
                    request.headers[HttpHeaders.Range] != null -> {
                        rateLimited.complete(Unit)
                        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "100"))
                    }
                    request.method == HttpMethod.Get -> {
                        wholeFileRequests.addAndFetch(1)
                        serve(request)
                    }
                    else -> serve(request)
                }
            }),
            scope = backgroundScope,
            fileSystem = SynchronizedFileSystem(FakeFileSystem()),
            config = DownloadManagerConfig(idleTimeout = Duration.ZERO),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            timeSource = penaltyClock,
            random = Random(seed = 0),
        )

        // Halving 2 segments leaves one, so this download falls back to a whole-file request.
        val download = async { manager.download(request("limited.bin", DownloadSegmentation.MaxSegments(2))) }
        rateLimited.await()
        // Real time for the 429 to reach the worker. Virtual delays meanwhile run past the
        // Retry-After wait, which leaves the fallback request held only by the penalty.
        withContext(Dispatchers.Default) { delay(500) }
        assertEquals(0, wholeFileRequests.load(), "requested during the penalty")

        penaltyClock += 101.seconds

        assertIs<DownloadResult.Success>(download.await())
        assertEquals(1, wholeFileRequests.load())
    }

    @Test
    fun `Auto uses one stream over HTTP 2`() = runTest {
        val recorder = Recorder()
        val large = 64L * 1024 * 1024
        val manager = manager(
            HttpClient(MockEngine { request ->
                recorder.add(request)
                val headers = if (request.method == HttpMethod.Head) rangeHeaders(large) else Headers.Empty
                http2Response(payload, headers)
            })
        )

        assertIs<DownloadResult.Success>(manager.download(request("h2.bin", DownloadSegmentation.Auto)))

        assertEquals(0, recorder.forPath("/h2.bin").count { it.range != null }, "${recorder.forPath("/h2.bin")}")
    }

    @Test
    fun `an explicit segment count is honored over HTTP 2`() = runTest {
        val recorder = Recorder()
        val manager = manager(
            HttpClient(MockEngine { request ->
                recorder.add(request)
                if (request.method == HttpMethod.Head) {
                    http2Response(ByteArray(0), rangeHeaders(payload.size.toLong()))
                } else {
                    serve(request)
                }
            })
        )

        assertIs<DownloadResult.Success>(manager.download(request("h2-explicit.bin", DownloadSegmentation.MaxSegments(3))))

        assertEquals(3, recorder.segmentRequests("h2-explicit.bin"))
    }

    @Test
    fun `Auto segments a large file over HTTP 1_1`() = runTest {
        val recorder = Recorder()
        val large = 64L * 1024 * 1024
        val manager = manager(
            HttpClient(MockEngine { request ->
                recorder.add(request)
                if (request.method == HttpMethod.Head) {
                    respond("", HttpStatusCode.OK, rangeHeaders(large))
                } else {
                    // Failing the slices is enough: the test only needs the segmentation decision.
                    respond("", HttpStatusCode.Forbidden)
                }
            })
        )

        manager.download(request("h1.bin", DownloadSegmentation.Auto))

        assertTrue(recorder.forPath("/h1.bin").any { it.range != null }, "${recorder.forPath("/h1.bin")}")
    }

    @Test
    fun `Auto skips probing a file known to be small`() = runTest {
        val recorder = Recorder()
        val manager = manager(
            HttpClient(MockEngine { request ->
                recorder.add(request)
                serve(request)
            })
        )

        val result = manager.download(request("known.bin", DownloadSegmentation.Auto, expectedBytes = payload.size.toLong()))

        assertIs<DownloadResult.Success>(result)
        assertEquals(listOf(HttpMethod.Get), recorder.forPath("/known.bin").map { it.method })
    }

    @Test
    fun `a missing file is not probed for ranges`() = runTest {
        val recorder = Recorder()
        val manager = manager(
            HttpClient(MockEngine { request ->
                recorder.add(request)
                respond("", HttpStatusCode.NotFound)
            })
        )

        val result = assertIs<DownloadResult.Failure>(manager.download(request("missing.bin", DownloadSegmentation.Auto)))

        assertEquals(404, assertIs<DownloadFailure.HttpStatus>(result.failure).code)
        assertEquals(
            listOf(HttpMethod.Head to null, HttpMethod.Get to null),
            recorder.forPath("/missing.bin").map { it.method to it.range },
        )
    }

    /** `respond` always reports HTTP/1.1, so a real protocol version needs the raw response. */
    private suspend fun http2Response(body: ByteArray, headers: Headers) = HttpResponseData(
        statusCode = HttpStatusCode.OK,
        requestTime = GMTDate(),
        headers = headers,
        version = HttpProtocolVersion.HTTP_2_0,
        body = ByteReadChannel(body),
        callContext = callContext(),
    )
}
