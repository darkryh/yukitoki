package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression suite for issue #15 over real sockets.
 *
 * Ktor's CIO engine cancels a whole call — body streaming included — after its
 * `requestTimeout` (15 s by default) unless the request carries its own timeout capability.
 * The bundled JVM engine used to be CIO and `create()` used a bare `HttpClient {}`, so any
 * healthy download longer than 15 s failed with `DownloadFailure.Timeout`.
 *
 * Most cases shrink the engine cap to 1 s and pace the body over ~3 s, so they prove the
 * mechanism quickly. One case keeps the real 15 s default and a ~17 s transfer.
 *
 * Removing the cap must not let a silent server hang a download forever, so the suite also
 * checks that a missing response head and a hanging HEAD probe are still bounded by
 * [DownloadManagerConfig.idleTimeout].
 */
class DownloadManagerTimeoutRegressionTest {
    private val server = E2EDownloadServer(payloadSize = 256 * 1024)
    private val fs = FileSystem.SYSTEM
    private lateinit var tmpDir: Path

    @BeforeTest
    fun setUp() {
        server.start()
        tmpDir = "${System.getProperty("java.io.tmpdir")}/yukitoki-timeout-${System.nanoTime()}".toPath()
        fs.createDirectories(tmpDir)
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        runCatching { fs.deleteRecursively(tmpDir, mustExist = false) }
    }

    private fun dest(name: String): Path = "$tmpDir/$name".toPath()

    private fun readBytes(path: Path): ByteArray = fs.read(path) { readByteArray() }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** CIO with a 1 s engine-level total request timeout: the issue's trap, made fast. */
    private fun cappedCioClient(): HttpClient = HttpClient(CIO) { engine { requestTimeout = 1_000 } }

    private fun slowRequest(name: String, segmentation: DownloadSegmentation = DownloadSegmentation.Single) =
        DownloadRequest(
            url = server.url("/slow"),
            destination = DownloadDestination.File(dest(name)),
            retryPolicy = RetryPolicy.None,
            segmentation = segmentation,
            expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, sha256(server.payload)),
        )

    private fun withBorrowed(client: HttpClient, block: suspend (DownloadManager) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = DownloadManager.create(client, scope)
        try {
            withTimeout(60.seconds) { block(manager) }
        } finally {
            manager.shutdown()
            scope.cancel()
            client.close()
        }
    }

    private fun withOwned(manager: DownloadManager, block: suspend (DownloadManager) -> Unit) = runBlocking {
        try {
            withTimeout(60.seconds) { block(manager) }
        } finally {
            manager.shutdown()
        }
    }

    private fun assertDownloaded(result: DownloadResult, name: String) {
        assertIs<DownloadResult.Success>(result, "result=$result")
        assertContentEquals(server.payload, readBytes(dest(name)))
    }

    @Test
    fun `control - the paced route really exceeds a 1s engine request timeout`() {
        runBlocking {
            cappedCioClient().use { client ->
                assertFailsWith<HttpRequestTimeoutException> { client.get(server.url("/slow")).bodyAsBytes() }
            }
        }
    }

    @Test
    fun `single stream survives the CIO engine request timeout on a borrowed client`() =
        withBorrowed(cappedCioClient()) { manager ->
            assertDownloaded(manager.download(slowRequest("single.bin")), "single.bin")
        }

    @Test
    fun `segmented download survives the CIO engine request timeout on a borrowed client`() =
        withBorrowed(cappedCioClient()) { manager ->
            val result = manager.download(slowRequest("segmented.bin", DownloadSegmentation.MaxSegments(4)))
            assertDownloaded(result, "segmented.bin")
        }

    @Test
    fun `borrowed client with a short HttpTimeout request timeout still completes`() =
        withBorrowed(HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 1_000 } }) { manager ->
            assertDownloaded(manager.download(slowRequest("plugin.bin")), "plugin.bin")
        }

    @Test
    fun `owned client survives a short request timeout set through configureClient`() =
        withOwned(DownloadManager.create(configureClient = { install(HttpTimeout) { requestTimeoutMillis = 1_000 } })) { manager ->
            assertDownloaded(manager.download(slowRequest("owned-plugin.bin")), "owned-plugin.bin")
        }

    @Test
    fun `configureClient customizations reach the owned client`() =
        withOwned(DownloadManager.create(configureClient = { install(DefaultRequest) { headers.append("X-Yukitoki-Test", "1") } })) { manager ->
            val result = manager.download(
                DownloadRequest(
                    url = server.url("/requireheader"),
                    destination = DownloadDestination.File(dest("header.bin")),
                    retryPolicy = RetryPolicy.None,
                )
            )
            assertDownloaded(result, "header.bin")
        }

    @Test
    fun `owned client uses the Java engine on JVM`() {
        createOwnedHttpClient().use { client ->
            assertEquals("io.ktor.client.engine.java.JavaHttpEngine", client.engine::class.qualifiedName)
        }
    }

    @Test
    fun `a server that never sends a response head fails with Timeout instead of hanging`() =
        withOwned(DownloadManager.create(DownloadManagerConfig(idleTimeout = 1.seconds))) { manager ->
            val started = System.nanoTime()
            val result = manager.download(
                DownloadRequest(
                    url = server.url("/hangheaders"),
                    destination = DownloadDestination.File(dest("hang.bin")),
                    retryPolicy = RetryPolicy.None,
                    segmentation = DownloadSegmentation.Single,
                )
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val failure = assertIs<DownloadResult.Failure>(result, "result=$result").failure
            assertIs<DownloadFailure.Timeout>(failure)
            assertTrue(elapsedMs < 10_000, "took $elapsedMs ms; the idle timeout must bound the response head")
        }

    @Test
    fun `a hanging HEAD probe falls back to the range probe within the idle timeout`() =
        withOwned(DownloadManager.create(DownloadManagerConfig(idleTimeout = 1.seconds))) { manager ->
            val started = System.nanoTime()
            val result = manager.download(
                DownloadRequest(
                    url = server.url("/hanghead"),
                    destination = DownloadDestination.File(dest("hanghead.bin")),
                    retryPolicy = RetryPolicy.None,
                    segmentation = DownloadSegmentation.MaxSegments(2),
                )
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertDownloaded(result, "hanghead.bin")
            assertTrue(elapsedMs < 10_000, "took $elapsedMs ms; the HEAD probe must be bounded")
        }

    /**
     * The exact failure from the issue, with Ktor's real 15 s CIO default in play: a healthy
     * ~17 s transfer through [DownloadManager.create]. Slow by design; it is the end-to-end proof.
     */
    @Test
    fun `create downloads a transfer longer than 15 seconds`() {
        val slowServer = E2EDownloadServer(payloadSize = 64 * 1024, slowChunks = 15, slowChunkDelayMs = 1_150)
        slowServer.start()
        try {
            runBlocking {
                val manager = DownloadManager.create()
                try {
                    val path = dest("long.bin")
                    val result = withTimeout(60.seconds) {
                        manager.download(
                            DownloadRequest(
                                url = slowServer.url("/slow"),
                                destination = DownloadDestination.File(path),
                                retryPolicy = RetryPolicy.None,
                                segmentation = DownloadSegmentation.Single,
                                expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, sha256(slowServer.payload)),
                            )
                        )
                    }
                    assertIs<DownloadResult.Success>(result, "result=$result")
                    assertContentEquals(slowServer.payload, readBytes(path))
                } finally {
                    manager.shutdown()
                }
            }
        } finally {
            slowServer.stop()
        }
    }
}
