package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Commands racing each other and racing the worker must leave every download in the state the
 * last user intent asked for, and must never leave a caller suspended forever.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
class DownloadStateMachineTest {

    private val testTimeout = 20.seconds

    private fun TestScope.manager(
        client: HttpClient,
        config: DownloadManagerConfig = DownloadManagerConfig(),
    ) = DefaultDownloadManager(
        httpClient = client,
        scope = backgroundScope,
        fileSystem = SynchronizedFileSystem(FakeFileSystem()),
        config = config.copy(idleTimeout = Duration.ZERO),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        random = Random(seed = 0),
    )

    private fun request(name: String, retryPolicy: RetryPolicy = RetryPolicy.None) = DownloadRequest(
        url = "https://example.test/$name",
        destination = DownloadDestination.File("/$name".toPath()),
        retryPolicy = retryPolicy,
        segmentation = DownloadSegmentation.Single,
    )

    private val slowRetry = RetryPolicy(
        maxAttempts = 3,
        initialDelay = 1.hours,
        maxDelay = 1.hours,
        multiplier = 1.0,
        jitterRatio = 0.0,
    )

    /** An engine whose requests never answer, signalling once the first one has started. */
    private fun hangingClient(started: CompletableDeferred<Unit> = CompletableDeferred()) = HttpClient(
        MockEngine {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            respond("unreachable", HttpStatusCode.OK)
        }
    )

    private fun failingClient(requests: AtomicInt) = HttpClient(
        MockEngine {
            requests.addAndFetch(1)
            respond("boom", HttpStatusCode.InternalServerError)
        }
    )

    private suspend fun DefaultDownloadManager.submit(request: DownloadRequest): DownloadId {
        val id = DownloadId.new()
        assertIs<CommandResult.Accepted>(submitCommand(DownloadCommand.Enqueue(request, id)))
        return id
    }

    @Test
    fun `cancel after pause on a running download ends cancelled`() = runTest(timeout = testTimeout) {
        val started = CompletableDeferred<Unit>()
        val manager = manager(hangingClient(started))
        val id = manager.submit(request("a.bin"))
        started.await()

        // Both commands reach the coordinator before the worker can observe the pause.
        listOf(
            async { manager.pause(id) },
            async { manager.cancel(id) },
        ).awaitAll()

        assertIs<DownloadResult.Cancelled>(manager.await(id))
        assertIs<DownloadState.Cancelled>(manager.state.value.snapshots[id]?.state)
    }

    @Test
    fun `pause right after enqueue is not lost when the worker has not run yet`() = runTest(timeout = testTimeout) {
        val requests = AtomicInt(0)
        val manager = manager(
            HttpClient(
                MockEngine {
                    requests.addAndFetch(1)
                    respond("payload", HttpStatusCode.OK)
                }
            )
        )
        val id = DownloadId.new()
        listOf(
            async { manager.submitCommand(DownloadCommand.Enqueue(request("b.bin"), id)) },
            async { manager.pause(id) },
        ).awaitAll()
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertIs<DownloadState.Paused>(manager.state.value.snapshots[id]?.state)
        assertEquals(0, requests.load(), "a paused download must not hit the network")

        manager.resume(id)
        assertIs<DownloadResult.Success>(manager.await(id))
    }

    @Test
    fun `cancelAll terminates paused downloads`() = runTest(timeout = testTimeout) {
        val manager = manager(
            hangingClient(),
            DownloadManagerConfig(maxConcurrentDownloads = 1),
        )
        manager.submit(request("running.bin"))
        runCurrent()
        val queued = manager.submit(request("queued.bin"))
        manager.pause(queued)
        assertIs<DownloadState.Paused>(manager.state.value.snapshots[queued]?.state)

        manager.cancelAll()

        assertIs<DownloadResult.Cancelled>(manager.await(queued))
    }

    @Test
    fun `cancelAll stops a download waiting for its retry backoff`() = runTest(timeout = testTimeout) {
        val requests = AtomicInt(0)
        val manager = manager(failingClient(requests))
        val id = manager.submit(request("c.bin", slowRetry))
        whenInBackoff(manager, id, requests) { manager.cancelAll() }

        assertIs<DownloadResult.Cancelled>(manager.await(id))

        advanceTimeBy(2.hours)
        runCurrent()
        assertEquals(1, requests.load(), "the cancelled download must not retry")
    }

    @Test
    fun `pauseAll holds a download waiting for its retry backoff`() = runTest(timeout = testTimeout) {
        val requests = AtomicInt(0)
        val manager = manager(failingClient(requests))
        val id = manager.submit(request("d.bin", slowRetry))
        val paused = CompletableDeferred<Unit>()
        whenInBackoff(manager, id, requests) {
            manager.pauseAll()
            paused.complete(Unit)
        }
        paused.await()

        advanceTimeBy(2.hours)
        runCurrent()

        assertIs<DownloadState.Paused>(manager.state.value.snapshots[id]?.state)
        assertEquals(1, requests.load(), "the paused download must not retry")
        manager.close()
    }

    @Test
    fun `resumeAll resumes a download that is still pausing`() = runTest(timeout = testTimeout) {
        val requests = AtomicInt(0)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val manager = manager(
            HttpClient(
                MockEngine {
                    if (requests.addAndFetch(1) == 1) firstStarted.complete(Unit) else secondStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    respond("unreachable", HttpStatusCode.OK)
                }
            )
        )
        val id = manager.submit(request("e.bin"))
        firstStarted.await()

        listOf(
            async { manager.pause(id) },
            async { manager.resumeAll() },
        ).awaitAll()

        secondStarted.await()
        assertIs<DownloadState.Starting>(manager.state.value.snapshots[id]?.state)
        manager.close()
    }

    @Test
    fun `shutdown right after enqueue does not hang when the worker has not run yet`() = runTest(timeout = testTimeout) {
        val manager = manager(hangingClient())
        listOf(
            async { manager.submitCommand(DownloadCommand.Enqueue(request("f.bin"), DownloadId.new())) },
            async { manager.shutdown() },
        ).awaitAll()
    }

    @Test
    fun `concurrent shutdown calls all return`() = runTest(timeout = testTimeout) {
        val manager = manager(hangingClient())
        listOf(
            async { manager.shutdown() },
            async { manager.shutdown() },
        ).awaitAll()
    }

    @Test
    fun `close releases a command the coordinator has not answered`() = runTest(timeout = testTimeout) {
        val manager = manager(hangingClient())
        val pending = async(start = CoroutineStart.UNDISPATCHED) { manager.pause(DownloadId("unknown")) }
        manager.close()
        assertIs<CommandResult.Rejected>(pending.await())
    }

    @Test
    fun `download returns its result when finished entries are not retained`() = runTest(timeout = testTimeout) {
        val manager = manager(
            HttpClient(MockEngine { respond("payload", HttpStatusCode.OK) }),
            DownloadManagerConfig(maxRetainedFinished = 0),
        )
        assertIs<DownloadResult.Success>(manager.download(request("g.bin")))
    }

    /**
     * A filesystem whose first sink runs [beforeFailing] and then fails. The hook queues a command
     * ahead of the worker's failure event, which is the race a real disk error can produce.
     */
    private class FailingSinkFileSystem(
        delegate: FileSystem,
        private val beforeFailing: () -> Unit,
    ) : ForwardingFileSystem(delegate) {
        override fun sink(file: Path, mustCreate: Boolean): Sink {
            beforeFailing()
            throw IOException("disk failed")
        }
    }

    private fun TestScope.managerWithFailingSink(beforeFailing: (DefaultDownloadManager) -> Unit): DefaultDownloadManager {
        lateinit var manager: DefaultDownloadManager
        manager = DefaultDownloadManager(
            httpClient = HttpClient(MockEngine { respond("payload", HttpStatusCode.OK) }),
            scope = backgroundScope,
            fileSystem = FailingSinkFileSystem(SynchronizedFileSystem(FakeFileSystem())) { beforeFailing(manager) },
            config = DownloadManagerConfig(idleTimeout = Duration.ZERO),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
            random = Random(seed = 0),
        )
        return manager
    }

    @Test
    fun `a failure racing a cancel ends cancelled`() = runTest(timeout = testTimeout) {
        val id = DownloadId.new()
        val scope = this
        val manager = managerWithFailingSink { manager ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.cancel(id) }
        }
        manager.submitCommand(DownloadCommand.Enqueue(request("h.bin"), id))

        assertIs<DownloadResult.Cancelled>(manager.await(id))
    }

    @Test
    fun `a failure racing a pause ends paused`() = runTest(timeout = testTimeout) {
        val id = DownloadId.new()
        val scope = this
        val manager = managerWithFailingSink { manager ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.pause(id) }
        }
        manager.submitCommand(DownloadCommand.Enqueue(request("i.bin"), id))

        val settled = manager.state.first { it.snapshots[id]?.state.let { state -> state is DownloadState.Paused || state is DownloadState.Failed } }
        assertIs<DownloadState.Paused>(settled.snapshots[id]?.state)
        manager.close()
    }

    /**
     * Runs [action] the moment [id] enters its retry backoff. runTest skips virtual delays while
     * the test body is suspended, so only an eager collector can act before the backoff elapses.
     */
    private fun TestScope.whenInBackoff(
        manager: DefaultDownloadManager,
        id: DownloadId,
        requests: AtomicInt,
        action: suspend () -> Unit,
    ) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.state.first { state ->
                requests.load() == 1 && state.snapshots[id]?.state is DownloadState.Queued && id !in state.active
            }
            action()
        }
    }
}
