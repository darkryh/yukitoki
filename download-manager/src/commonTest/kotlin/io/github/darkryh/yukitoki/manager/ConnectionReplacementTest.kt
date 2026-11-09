package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** HTTP/2 retries share a connection, so a silent one has to be left behind for retries to work. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionReplacementTest {
    private val payload = ByteArray(256) { it.toByte() }

    private fun silentClient() = HttpClient(MockEngine { awaitCancellation() })

    private fun workingClient() = HttpClient(
        MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        },
    )

    private fun failingClient(status: HttpStatusCode) = HttpClient(MockEngine { respond("", status) })

    private val retries = RetryPolicy(
        maxAttempts = 3,
        initialDelay = 1.milliseconds,
        maxDelay = 1.milliseconds,
        multiplier = 1.0,
        jitterRatio = 0.0,
    )

    private suspend fun download(first: HttpClient, newClient: (() -> HttpClient)?): DownloadResult =
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = DefaultDownloadManager(
                httpClient = first,
                scope = scope,
                fileSystem = FakeFileSystem(),
                config = DownloadManagerConfig(idleTimeout = 100.milliseconds),
                ioDispatcher = Dispatchers.Default,
                newClient = newClient,
            )
            try {
                manager.download(
                    DownloadRequest(
                        url = "https://example.test/file.bin",
                        destinationPath = "/downloads/file.bin".toPath(),
                        retryPolicy = retries,
                        segmentation = DownloadSegmentation.Single,
                    ),
                )
            } finally {
                manager.close()
                scope.cancel()
            }
        }

    @Test
    fun `a retry after no response uses a new client`() = runTest {
        var created = 0
        val result = download(silentClient()) {
            created++
            workingClient()
        }

        assertIs<DownloadResult.Success>(result)
        assertEquals(1, created)
    }

    @Test
    fun `a failing replacement is replaced again`() = runTest {
        var created = 0
        val result = download(silentClient()) {
            created++
            if (created == 1) silentClient() else workingClient()
        }

        assertIs<DownloadResult.Success>(result)
        assertEquals(2, created)
    }

    @Test
    fun `an error from a server that answered keeps the client`() = runTest {
        var created = 0
        val result = download(failingClient(HttpStatusCode.ServiceUnavailable)) {
            created++
            workingClient()
        }

        assertIs<DownloadResult.Failure>(result)
        assertEquals(0, created)
    }

    @Test
    fun `a borrowed client is reported as a timeout and never closed`() = runTest {
        val client = silentClient()
        val result = download(client, newClient = null)

        assertIs<DownloadFailure.Timeout>(assertIs<DownloadResult.Failure>(result).failure)
        assertTrue(client.isActive())
        client.close()
    }

    @Test
    fun `a replaced client stays open until its last request ends`() {
        val first = workingClient()
        val second = workingClient()
        val clients = ReplaceableHttpClient(first) { second }

        val running = clients.acquire()
        val failed = clients.acquire()
        clients.replace(failed)
        failed.release()

        assertTrue(first.isActive(), "another request is still running on it")
        assertEquals(second, clients.acquire().client)

        running.release()
        assertFalse(first.isActive())
        clients.close()
    }

    @Test
    fun `concurrent failures replace the client once`() {
        var created = 0
        val clients = ReplaceableHttpClient(workingClient()) {
            created++
            workingClient()
        }

        val one = clients.acquire()
        val two = clients.acquire()
        clients.replace(one)
        clients.replace(two)

        assertEquals(1, created)
        one.release()
        two.release()
        clients.close()
    }

    private fun HttpClient.isActive(): Boolean = coroutineContext[kotlinx.coroutines.Job]?.isCompleted == false
}
