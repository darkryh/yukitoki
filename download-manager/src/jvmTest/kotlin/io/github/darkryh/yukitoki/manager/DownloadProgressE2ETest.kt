package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What callers see while bytes flow from a paced server: about 85 KiB/s for 3 seconds. */
class DownloadProgressE2ETest {
    private val server = E2EDownloadServer(payloadSize = 256 * 1024)
    private val fs = FileSystem.SYSTEM
    private lateinit var tmpDir: Path

    @BeforeTest
    fun setUp() {
        server.start()
        tmpDir = "${System.getProperty("java.io.tmpdir")}/yukitoki-progress-${System.nanoTime()}".toPath()
        fs.createDirectories(tmpDir)
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        runCatching { fs.deleteRecursively(tmpDir, mustExist = false) }
    }

    /** Downloads the paced route, returning every published progress value. */
    private fun download(
        name: String,
        segmentation: DownloadSegmentation,
        granularity: ProgressGranularity = ProgressGranularity(),
    ): List<DownloadProgress> = runBlocking {
        val client = HttpClient(CIO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = DownloadManager.create(client, scope)
        try {
            withTimeout(30.seconds) {
                val task = manager.enqueue(
                    DownloadRequest(
                        url = server.url("/slow"),
                        destination = DownloadDestination.File("$tmpDir/$name".toPath()),
                        retryPolicy = RetryPolicy.None,
                        segmentation = segmentation,
                        progressGranularity = granularity,
                    )
                )
                val seen = CopyOnWriteArrayList<DownloadProgress>()
                val collector = launch(Dispatchers.Unconfined) {
                    task.state.collect { snapshot ->
                        (snapshot?.state as? DownloadState.Running)?.let { seen += it.progress }
                    }
                }
                assertIs<DownloadResult.Success>(task.await())
                collector.cancel()
                assertContentEquals(server.payload, fs.read("$tmpDir/$name".toPath()) { readByteArray() })
                seen.distinct()
            }
        } finally {
            manager.shutdown()
            scope.cancel()
            client.close()
        }
    }

    private fun assertLiveProgress(seen: List<DownloadProgress>) {
        assertTrue(seen.size >= 5, "default granularity must keep a slow download updating: $seen")
        assertEquals(server.payload.size.toLong(), seen.last().totalBytes)
        assertTrue(seen.map { it.bytesDownloaded }.zipWithNext().all { (a, b) -> a <= b }, "went backwards: $seen")

        val speeds = seen.mapNotNull { it.speedBytesPerSecond }
        assertTrue(speeds.isNotEmpty(), "no speed reported: $seen")
        assertTrue(speeds.all { it in 10_000L..2_000_000L }, "implausible speed for a ~85 KiB/s server: $speeds")
        assertTrue(seen.any { it.eta != null }, "no ETA reported: $seen")
    }

    @Test
    fun `a slow single stream keeps reporting progress speed and eta`() {
        assertLiveProgress(download("single.bin", DownloadSegmentation.Single))
    }

    @Test
    fun `a slow segmented download keeps reporting progress speed and eta`() {
        assertLiveProgress(download("segmented.bin", DownloadSegmentation.MaxSegments(4)))
    }

    @Test
    fun `minInterval limits updates to the first and final bytes when it is very long`() {
        val seen = download("sparse.bin", DownloadSegmentation.Single, ProgressGranularity(minInterval = 1.hours))
        assertTrue(seen.size <= 2, "minInterval was not honored: $seen")
    }

    @Test
    fun `minBytesDelta limits updates even when the interval has passed`() {
        val seen = download(
            "bytes.bin",
            DownloadSegmentation.Single,
            ProgressGranularity(minBytesDelta = Long.MAX_VALUE, minInterval = 0.milliseconds),
        )
        assertTrue(seen.size <= 2, "minBytesDelta was not honored: $seen")
    }
}
