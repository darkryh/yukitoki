package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Pause, resume and cancel against a real server while bytes are really flowing. */
class DownloadStateRaceE2ETest {
    private val server = E2EDownloadServer(payloadSize = 256 * 1024)
    private val fs = FileSystem.SYSTEM
    private lateinit var tmpDir: Path

    @BeforeTest
    fun setUp() {
        server.start()
        tmpDir = "${System.getProperty("java.io.tmpdir")}/yukitoki-race-${System.nanoTime()}".toPath()
        fs.createDirectories(tmpDir)
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        runCatching { fs.deleteRecursively(tmpDir, mustExist = false) }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun slowRequest(name: String, segmentation: DownloadSegmentation) = DownloadRequest(
        url = server.url("/slow"),
        destination = DownloadDestination.File("$tmpDir/$name".toPath()),
        retryPolicy = RetryPolicy.None,
        segmentation = segmentation,
        progressGranularity = ProgressGranularity(minBytesDelta = 0, minInterval = 0.milliseconds),
        expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, sha256(server.payload)),
    )

    private fun withManager(block: suspend CoroutineScope.(DownloadManager) -> Unit) = runBlocking {
        val client = HttpClient(CIO)
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

    private suspend fun DownloadManager.awaitRunning(id: DownloadId) {
        state.first { it.snapshots[id]?.state is DownloadState.Running }
    }

    private suspend fun DownloadManager.awaitPaused(id: DownloadId) {
        state.first { it.snapshots[id]?.state is DownloadState.Paused }
    }

    private fun assertOnlyFinalFiles(vararg names: String) {
        assertEquals(names.toSet(), fs.list(tmpDir).map { it.name }.toSet(), "unexpected files left behind")
    }

    @Test
    fun `progress already in flight never overwrites a pause`() = withManager { manager ->
        val task = manager.enqueue(slowRequest("a.bin", DownloadSegmentation.Single))
        val states = CopyOnWriteArrayList<DownloadState>()
        val collector = launch(Dispatchers.Unconfined) {
            manager.state.collect { state -> state.snapshots[task.id]?.state?.let(states::add) }
        }
        manager.awaitRunning(task.id)
        task.pause()
        manager.awaitPaused(task.id)
        collector.cancel()

        val afterPause = states.dropWhile { it !is DownloadState.Pausing }
        assertTrue(afterPause.isNotEmpty(), "states=$states")
        assertTrue(afterPause.none { it is DownloadState.Running }, "state went backwards: $afterPause")
    }

    @Test
    fun `paused single stream download resumes to a verified file`() = withManager { manager ->
        val task = manager.enqueue(slowRequest("b.bin", DownloadSegmentation.Single))
        manager.awaitRunning(task.id)
        task.pause()
        manager.awaitPaused(task.id)
        task.resume()

        assertIs<DownloadResult.Success>(task.await())
        assertContentEquals(server.payload, fs.read("$tmpDir/b.bin".toPath()) { readByteArray() })
        assertOnlyFinalFiles("b.bin")
    }

    @Test
    fun `paused segmented download resumes to a verified file without leftovers`() = withManager { manager ->
        val task = manager.enqueue(slowRequest("c.bin", DownloadSegmentation.MaxSegments(4)))
        manager.awaitRunning(task.id)
        task.pause()
        manager.awaitPaused(task.id)
        task.resume()

        assertIs<DownloadResult.Success>(task.await())
        assertContentEquals(server.payload, fs.read("$tmpDir/c.bin".toPath()) { readByteArray() })
        assertOnlyFinalFiles("c.bin")
    }

    @Test
    fun `pause then cancel while bytes flow ends cancelled and removes the partial`() = withManager { manager ->
        val task = manager.enqueue(slowRequest("d.bin", DownloadSegmentation.Single))
        manager.awaitRunning(task.id)
        task.pause()
        task.cancel()

        assertIs<DownloadResult.Cancelled>(task.await())
        assertOnlyFinalFiles()
    }

    @Test
    fun `cancelAll ends running, paused and queued downloads alike`() = withManager { manager ->
        manager.setConcurrency(DownloadConcurrency.Fixed(1))
        val running = manager.enqueue(slowRequest("e1.bin", DownloadSegmentation.Single))
        val paused = manager.enqueue(slowRequest("e2.bin", DownloadSegmentation.Single))
        val queued = manager.enqueue(slowRequest("e3.bin", DownloadSegmentation.Single))
        manager.awaitRunning(running.id)
        paused.pause()

        manager.cancelAll()

        assertIs<DownloadResult.Cancelled>(running.await())
        assertIs<DownloadResult.Cancelled>(paused.await())
        assertIs<DownloadResult.Cancelled>(queued.await())
        assertOnlyFinalFiles()
    }

    @Test
    fun `repeated pauseAll and resumeAll still finish every file intact`() = withManager { manager ->
        val tasks = (1..3).map { manager.enqueue(slowRequest("f$it.bin", DownloadSegmentation.Single)) }
        repeat(4) {
            delay(150)
            manager.pauseAll()
            delay(50)
            manager.resumeAll()
        }

        tasks.forEach { assertIs<DownloadResult.Success>(it.await(), "task ${it.id}") }
        (1..3).forEach { index ->
            assertContentEquals(server.payload, fs.read("$tmpDir/f$index.bin".toPath()) { readByteArray() })
        }
        assertOnlyFinalFiles("f1.bin", "f2.bin", "f3.bin")
    }
}
