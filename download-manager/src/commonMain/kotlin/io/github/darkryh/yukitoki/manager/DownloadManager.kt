package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface DownloadTask {
    public val id: DownloadId
    public val state: Flow<DownloadSnapshot?>

    public suspend fun await(): DownloadResult
    public suspend fun pause(): CommandResult
    public suspend fun resume(): CommandResult
    public suspend fun cancel(): CommandResult
    public suspend fun retry(): CommandResult
}

public interface DownloadManager : AutoCloseable {
    public val state: StateFlow<DownloadManagerState>

    public fun observe(downloadId: DownloadId): Flow<DownloadSnapshot?>
    public suspend fun await(downloadId: DownloadId): DownloadResult?

    public suspend fun enqueue(request: DownloadRequest, id: DownloadId = DownloadId.new()): DownloadTask

    public suspend fun enqueue(
        url: String,
        destination: DownloadDestination = DownloadDestination.Default,
        priority: DownloadPriority = DownloadPriority.NORMAL,
    ): DownloadTask = enqueue(
        DownloadRequest(url = url, destination = destination, priority = priority)
    )

    public suspend fun download(request: DownloadRequest): DownloadResult

    public suspend fun download(
        url: String,
        destination: DownloadDestination = DownloadDestination.Default,
    ): DownloadResult = download(DownloadRequest(url = url, destination = destination))

    public suspend fun pause(downloadId: DownloadId): CommandResult
    public suspend fun resume(downloadId: DownloadId): CommandResult
    public suspend fun cancel(downloadId: DownloadId): CommandResult
    public suspend fun retry(downloadId: DownloadId): CommandResult
    public suspend fun moveWithinPriority(downloadId: DownloadId, newPosition: Int): CommandResult
    public suspend fun pauseAll(): CommandResult
    public suspend fun resumeAll(): CommandResult
    public suspend fun cancelAll(): CommandResult
    public suspend fun retryAllFailed(): CommandResult
    public suspend fun setConcurrency(concurrency: DownloadConcurrency): CommandResult

    /** Remove one terminal entry from manager state, optionally deleting its completed file. */
    public suspend fun remove(downloadId: DownloadId, deleteFile: Boolean = false): CommandResult

    /** Remove all succeeded, failed, and cancelled entries from manager state. */
    public suspend fun clearFinished(): CommandResult

    /**
     * Graceful teardown. With [cancelActive] false, active downloads drain before resources
     * owned by a default-created manager are released.
     */
    public suspend fun shutdown(cancelActive: Boolean = true)

    /** Immediate non-suspending lifecycle teardown. */
    public override fun close()

    public companion object {
        /**
         * Create a batteries-included manager. Yukitoki selects the bundled platform Ktor
         * engine and owns the resulting client and coroutine scope.
         */
        public fun create(
            config: DownloadManagerConfig = DownloadManagerConfig(),
            configureClient: HttpClientConfig<*>.() -> Unit = {},
        ): DownloadManager {
            check(DownloadLocations.isSupported) {
                "Managed filesystem downloads are unavailable on this platform. Use DownloadIntents."
            }
            val client = HttpClient { configureClient() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val delegate = DefaultDownloadManager(client, scope, config = config)
            return OwnedDownloadManager(delegate, client, scope)
        }

        /** Create a manager borrowing caller-owned networking and coroutine resources. */
        public fun create(
            httpClient: HttpClient,
            scope: CoroutineScope,
            config: DownloadManagerConfig = DownloadManagerConfig(),
        ): DownloadManager {
            check(DownloadLocations.isSupported) {
                "Managed filesystem downloads are unavailable on this platform. Use DownloadIntents."
            }
            return DefaultDownloadManager(httpClient, scope, config = config)
        }
    }
}

private class OwnedDownloadManager(
    private val delegate: DownloadManager,
    private val client: HttpClient,
    private val scope: CoroutineScope,
) : DownloadManager by delegate {
    private var resourcesClosed = false

    override suspend fun shutdown(cancelActive: Boolean) {
        try {
            delegate.shutdown(cancelActive)
        } finally {
            releaseResources()
        }
    }

    public override fun close() {
        try {
            delegate.close()
        } finally {
            releaseResources()
        }
    }

    private fun releaseResources() {
        if (resourcesClosed) return
        resourcesClosed = true
        client.close()
        scope.cancel()
    }
}
