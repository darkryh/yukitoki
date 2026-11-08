package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Guarantees that only show up when callers and the coordinator run on different threads. */
class DownloadCoordinatorThreadingTest {

    private val payload = "payload".encodeToByteArray()

    private fun client(hang: Boolean = false) = HttpClient(
        MockEngine {
            if (hang) CompletableDeferred<Unit>().await()
            respond(payload, HttpStatusCode.OK)
        }
    )

    private fun request(name: String) = DownloadRequest(
        url = "https://example.test/$name",
        destination = DownloadDestination.File("/$name".toPath()),
        retryPolicy = RetryPolicy.None,
        segmentation = DownloadSegmentation.Single,
    )

    private fun withThreads(
        threads: Int,
        block: suspend (dispatcher: CoroutineDispatcher, scope: CoroutineScope) -> Unit,
    ) {
        val executor = Executors.newFixedThreadPool(threads)
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            runBlocking(dispatcher) { block(dispatcher, scope) }
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private fun manager(
        client: HttpClient,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        config: DownloadManagerConfig = DownloadManagerConfig(),
    ) = DefaultDownloadManager(
        httpClient = client,
        scope = scope,
        fileSystem = SynchronizedFileSystem(FakeFileSystem()),
        config = config,
        ioDispatcher = dispatcher,
    )

    @Test
    fun `an accepted enqueue is already visible in state`() = withThreads(4) { dispatcher, scope ->
        val manager = manager(client(hang = true), scope, dispatcher, DownloadManagerConfig(maxConcurrentDownloads = 1))
        repeat(300) { index ->
            val task = manager.enqueue(request("enqueue$index.bin"))
            assertTrue(task.id in manager.state.value.snapshots, "enqueue $index ack came before its state")
        }
        manager.close()
    }

    @Test
    fun `an accepted pause is already visible in state`() = withThreads(4) { dispatcher, scope ->
        val manager = manager(client(hang = true), scope, dispatcher, DownloadManagerConfig(maxConcurrentDownloads = 1))
        repeat(300) { index ->
            val task = manager.enqueue(request("pause$index.bin"))
            assertEquals(CommandResult.Accepted, task.pause())
            val state = manager.state.value.snapshots[task.id]?.state
            assertTrue(state is DownloadState.Paused || state is DownloadState.Pausing, "pause $index saw $state")
        }
        manager.close()
    }

    @Test
    fun `a finished download is already terminal in state`() = withThreads(4) { dispatcher, scope ->
        val manager = manager(client(), scope, dispatcher, DownloadManagerConfig(maxRetainedFinished = 1))
        repeat(200) { index ->
            val task = manager.enqueue(request("done$index.bin"))
            assertIs<DownloadResult.Success>(task.await())
            assertIs<DownloadState.Succeeded>(manager.state.value.snapshots[task.id]?.state, "download $index")
        }
        manager.close()
    }

    @Test
    fun `a borrowed UI scope never runs filesystem work on its own thread`() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "yukitoki-test-ui") }
        val callerDispatcher = caller.asCoroutineDispatcher()
        val threads = ConcurrentHashMap.newKeySet<String>()
        val fileSystem = object : ForwardingFileSystem(FakeFileSystem() as FileSystem) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
                threads += Thread.currentThread().name
                return path
            }
        }
        val scope = CoroutineScope(SupervisorJob() + callerDispatcher)
        val manager = DefaultDownloadManager(
            httpClient = client(),
            scope = scope,
            fileSystem = SynchronizedFileSystem(fileSystem),
            ioDispatcher = Dispatchers.IO,
        )
        try {
            runBlocking {
                val file = withContext(callerDispatcher) {
                    val task = manager.enqueue(request("ui.bin"))
                    assertIs<DownloadResult.Success>(task.await()).file
                }
                assertEquals(CommandResult.Accepted, withContext(callerDispatcher) {
                    val id = manager.state.value.snapshots.keys.single()
                    manager.remove(id, deleteFile = true)
                })
                assertEquals("/ui.bin".toPath(), file.path)
            }
            assertTrue(threads.isNotEmpty(), "the filesystem was never used")
            // Coroutine debug mode appends " @coroutine#n" to thread names.
            assertTrue(threads.none { it.startsWith("yukitoki-test-ui") }, "filesystem work ran on the caller's thread: $threads")
        } finally {
            manager.close()
            scope.cancel()
            caller.shutdownNow()
        }
    }
}
