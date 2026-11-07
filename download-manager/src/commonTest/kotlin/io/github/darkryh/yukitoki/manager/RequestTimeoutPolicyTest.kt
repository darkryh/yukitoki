package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Regression guard for issue #15: Ktor's CIO engine aborts any request after 15 s unless the
 * request carries its own timeout capability, which silently killed healthy long downloads.
 * Every body-carrying GET must opt out of a total request timeout, while short metadata
 * probes keep whatever bound the client configured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestTimeoutPolicyTest {

    private data class Seen(val method: HttpMethod, val range: String?, val requestTimeoutMillis: Long?)

    private val payload = ByteArray(4096) { (it % 251).toByte() }

    private fun recordingClient(seen: MutableList<Seen>): HttpClient = HttpClient(MockEngine { request ->
        val range = request.headers[HttpHeaders.Range]
        seen += Seen(request.method, range, request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
        val common = arrayOf(
            HttpHeaders.AcceptRanges to listOf("bytes"),
            HttpHeaders.ETag to listOf("\"v1\""),
        )
        when {
            request.method == HttpMethod.Head -> respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(*common, HttpHeaders.ContentLength to listOf(payload.size.toString())),
            )
            range != null -> {
                val spec = range.removePrefix("bytes=").split('-')
                val start = spec[0].toInt()
                val end = spec.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (payload.size - 1)
                respond(
                    content = payload.copyOfRange(start, end + 1),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        *common,
                        HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}"),
                        HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
                    ),
                )
            }
            else -> respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(*common, HttpHeaders.ContentLength to listOf(payload.size.toString())),
            )
        }
    })

    private fun TestScope.manager(client: HttpClient) = DefaultDownloadManager(
        httpClient = client,
        scope = backgroundScope,
        fileSystem = SynchronizedFileSystem(FakeFileSystem()),
        config = DownloadManagerConfig(idleTimeout = Duration.ZERO),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        random = Random(seed = 0),
    )

    @Test
    fun `single stream body request has no total request timeout`() = runTest {
        val seen = mutableListOf<Seen>()
        val result = manager(recordingClient(seen)).download(
            DownloadRequest(
                url = "https://example.test/file.bin",
                destination = DownloadDestination.File("/single.bin".toPath()),
                segmentation = DownloadSegmentation.Single,
            )
        )
        assertIs<DownloadResult.Success>(result)
        val gets = seen.filter { it.method == HttpMethod.Get }
        assertEquals(1, gets.size, "seen=$seen")
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, gets.single().requestTimeoutMillis)
    }

    @Test
    fun `segment body requests have no total request timeout while probes keep client bounds`() = runTest {
        val seen = mutableListOf<Seen>()
        val result = manager(recordingClient(seen)).download(
            DownloadRequest(
                url = "https://example.test/segmented.bin",
                destination = DownloadDestination.File("/segmented.bin".toPath()),
                segmentation = DownloadSegmentation.MaxSegments(4),
            )
        )
        assertIs<DownloadResult.Success>(result)

        val head = seen.filter { it.method == HttpMethod.Head }
        assertEquals(1, head.size, "seen=$seen")
        assertNull(head.single().requestTimeoutMillis, "metadata probe must keep the client's own bounds")

        val segments = seen.filter { it.method == HttpMethod.Get && it.range != null }
        assertTrue(segments.size > 1, "expected a segmented download, seen=$seen")
        segments.forEach { assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, it.requestTimeoutMillis, "segment $it") }
    }

    @Test
    fun `owned client uses the bundled engine and installs HttpTimeout`() {
        val client = createOwnedHttpClient()
        try {
            val engineName = assertNotNull(client.engine::class.simpleName)
            assertTrue(
                engineName.startsWith(bundledEngineName),
                "expected the bundled $bundledEngineName engine, got $engineName",
            )
            assertNotNull(client.pluginOrNull(HttpTimeout), "owned client must install HttpTimeout")
        } finally {
            client.close()
        }
    }
}
