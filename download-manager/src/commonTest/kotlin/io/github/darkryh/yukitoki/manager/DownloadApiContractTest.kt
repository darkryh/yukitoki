package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration

/** Pins what each public call promises its caller: results, rejections and invariants. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadApiContractTest {
    @Test
    fun `ids stay unique when created from many threads at once`() = runTest {
        val ids = withContext(Dispatchers.Default) {
            List(8) { async { List(50_000) { DownloadId.new() } } }.awaitAll().flatten()
        }

        assertEquals(ids.size, ids.toSet().size)
    }

    private val payload = "payload".encodeToByteArray()

    private fun TestScope.manager(
        fs: FileSystem = FakeFileSystem(),
        config: DownloadManagerConfig = DownloadManagerConfig(),
        hang: Boolean = false,
    ) = DefaultDownloadManager(
        httpClient = HttpClient(
            MockEngine {
                if (hang) CompletableDeferred<Unit>().await()
                respond(payload, HttpStatusCode.OK)
            }
        ),
        scope = backgroundScope,
        fileSystem = SynchronizedFileSystem(fs),
        config = config.copy(idleTimeout = Duration.ZERO),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        random = Random(seed = 0),
    )

    private fun request(name: String, expectedBytes: Long? = null, priority: DownloadPriority = DownloadPriority.NORMAL) =
        DownloadRequest(
            url = "https://example.test/$name",
            destination = DownloadDestination.File("/$name".toPath()),
            retryPolicy = RetryPolicy.None,
            segmentation = DownloadSegmentation.Single,
            expectedBytes = expectedBytes,
            priority = priority,
        )

    @Test
    fun `download returns the final path and size`() = runTest {
        val fs = FakeFileSystem()
        val result = assertIs<DownloadResult.Success>(manager(fs).download(request("a.bin")))

        assertEquals(DownloadedFile("/a.bin".toPath(), payload.size.toLong()), result.file)
        assertEquals("payload", fs.read(result.file.path) { readUtf8() })
    }

    @Test
    fun `succeeded state carries the same file as the result`() = runTest {
        val manager = manager()
        val task = manager.enqueue(request("b.bin"))
        val result = assertIs<DownloadResult.Success>(task.await())
        val state = assertIs<DownloadState.Succeeded>(manager.state.value.snapshots[task.id]?.state)

        assertEquals(result.file, state.file)
    }

    @Test
    fun `enqueue by url queues with the given priority`() = runTest {
        val manager = manager(config = DownloadManagerConfig(maxConcurrentDownloads = 1), hang = true)
        manager.enqueue(request("running.bin"))
        val task = manager.enqueue(
            url = "https://example.test/c.bin",
            destination = DownloadDestination.File("/c.bin".toPath()),
            priority = DownloadPriority.HIGH,
        )

        assertEquals(DownloadPriority.HIGH, manager.state.value.snapshots[task.id]?.request?.priority)
        manager.close()
    }

    @Test
    fun `size mismatch reports expected and actual bytes`() = runTest {
        val result = assertIs<DownloadResult.Failure>(manager().download(request("d.bin", expectedBytes = 1)))

        assertEquals(DownloadFailure.SizeMismatch(expectedBytes = 1, actualBytes = payload.size.toLong()), result.failure)
    }

    @Test
    fun `enqueue on an occupied exact path throws AlreadyExists with the path`() = runTest {
        val fs = FakeFileSystem()
        fs.write("/e.bin".toPath()) { writeUtf8("existing") }

        val rejected = assertFailsWith<DownloadRejectedException> { manager(fs).enqueue(request("e.bin")) }

        assertEquals(DownloadFailure.AlreadyExists("/e.bin".toPath()), rejected.failure)
    }

    @Test
    fun `download returns a rejection as a failure instead of throwing`() = runTest {
        val fs = FakeFileSystem()
        fs.write("/f.bin".toPath()) { writeUtf8("existing") }

        val result = assertIs<DownloadResult.Failure>(manager(fs).download(request("f.bin")))

        assertEquals(DownloadFailure.AlreadyExists("/f.bin".toPath()), result.failure)
    }

    @Test
    fun `commands on an unknown id are rejected as NotFound`() = runTest {
        val manager = manager()
        val unknown = DownloadId("unknown")

        assertEquals(CommandResult.Rejected.NotFound, manager.pause(unknown))
        assertEquals(CommandResult.Rejected.NotFound, manager.resume(unknown))
        assertEquals(CommandResult.Rejected.NotFound, manager.cancel(unknown))
        assertEquals(CommandResult.Rejected.NotFound, manager.retry(unknown))
        assertEquals(CommandResult.Rejected.NotFound, manager.remove(unknown))
        assertEquals(CommandResult.Rejected.NotFound, manager.moveWithinPriority(unknown, 0))
    }

    @Test
    fun `commands that do not fit the current state report that state`() = runTest {
        val manager = manager()
        val task = manager.enqueue(request("g.bin"))
        val finished = assertIs<DownloadResult.Success>(task.await())
        val succeeded = DownloadState.Succeeded(finished.file)

        assertEquals(CommandResult.Rejected.InvalidState(succeeded), task.pause())
        assertEquals(CommandResult.Rejected.InvalidState(succeeded), task.resume())
        assertEquals(CommandResult.Rejected.InvalidState(succeeded), task.retry())
        assertEquals(CommandResult.Rejected.InvalidState(succeeded), manager.moveWithinPriority(task.id, 0))
    }

    @Test
    fun `removing an unfinished download reports its state`() = runTest {
        val manager = manager(hang = true)
        val task = manager.enqueue(request("h.bin"))
        runCurrent()

        assertIs<CommandResult.Rejected.InvalidState>(manager.remove(task.id))
        manager.close()
    }

    @Test
    fun `an out of range queue position is rejected with the position`() = runTest {
        val manager = manager(config = DownloadManagerConfig(maxConcurrentDownloads = 1), hang = true)
        manager.enqueue(request("running.bin"))
        runCurrent()
        val queued = manager.enqueue(request("queued.bin"))

        assertEquals(CommandResult.Rejected.InvalidPosition(5), manager.moveWithinPriority(queued.id, 5))
        assertEquals(CommandResult.Accepted, manager.moveWithinPriority(queued.id, 0))
        manager.close()
    }

    @Test
    fun `a closed manager rejects commands and new downloads`() = runTest {
        val manager = manager()
        manager.shutdown()

        assertEquals(CommandResult.Rejected.ManagerClosed, manager.pauseAll())
        assertEquals(
            DownloadFailure.ManagerClosed,
            assertFailsWith<DownloadRejectedException> { manager.enqueue(request("i.bin")) }.failure,
        )
        assertEquals(
            DownloadFailure.ManagerClosed,
            assertIs<DownloadResult.Failure>(manager.download(request("j.bin"))).failure,
        )
    }

    @Test
    fun `setMaxConcurrentDownloads updates state and starts queued work`() = runTest {
        val manager = manager(config = DownloadManagerConfig(maxConcurrentDownloads = 1), hang = true)
        manager.enqueue(request("k1.bin"))
        val second = manager.enqueue(request("k2.bin"))
        runCurrent()
        assertEquals(1, manager.state.value.active.size)

        assertEquals(CommandResult.Accepted, manager.setMaxConcurrentDownloads(2))

        assertEquals(2, manager.state.value.maxConcurrentDownloads)
        assertEquals(true, second.id in manager.state.value.active)
        manager.close()
    }

    @Test
    fun `invalid concurrency is refused up front`() = runTest {
        val manager = manager()
        assertFailsWith<IllegalArgumentException> { manager.setMaxConcurrentDownloads(0) }
        assertFailsWith<IllegalArgumentException> { DownloadManagerConfig(maxConcurrentDownloads = 0) }
    }

    @Test
    fun `a caller supplied Range header is refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            DownloadRequest(
                url = "https://example.test/l.bin",
                headers = headersOf(HttpHeaders.Range to listOf("bytes=0-10")),
            )
        }
    }

    @Test
    fun `request and segmentation values are validated at construction`() {
        assertFailsWith<IllegalArgumentException> { DownloadRequest(url = "https://example.test/m.bin", expectedBytes = -1) }
        assertFailsWith<IllegalArgumentException> { DownloadRequest(url = "ftp://example.test/m.bin") }
        assertFailsWith<IllegalArgumentException> { DownloadSegmentation.MaxSegments(count = 0) }
    }

    @Test
    fun `file names follow the same rules for managed downloads and intents`() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "a:b", "a?b").forEach { name ->
            assertFailsWith<IllegalArgumentException>("managed: '$name'") {
                DownloadDestination.Directory("/tmp".toPath(), name)
            }
            assertFailsWith<IllegalArgumentException>("intent: '$name'") {
                DownloadIntentRequest(url = "https://example.test/n.bin", fileName = name)
            }
        }
    }
}
