package com.ead.yukitoki.manager

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface DownloadManager {
    suspend fun command(command: DownloadCommand): CommandAck
    fun observeState(): StateFlow<DownloadManagerState>
    fun observeItem(downloadId: DownloadId): Flow<DownloadSnapshot?>
    suspend fun awaitTerminal(downloadId: DownloadId): DownloadResult?
    suspend fun downloadNow(request: DownloadRequest): DownloadResult

    /**
     * Graceful teardown. When [cancelActive] is false, in-flight downloads are allowed
     * to finish (their final files are written) before this returns; when true, active
     * downloads are cancelled. Outstanding [awaitTerminal] callers are released.
     * Suspends until shutdown completes.
     */
    suspend fun shutdown(cancelActive: Boolean = true)

    /**
     * Non-suspending, immediate teardown for lifecycle callbacks that are not
     * coroutines (e.g. `ViewModel.onCleared()`). Cancels the coordinator and all
     * active downloads at once without waiting; partial files follow the configured
     * cleanup policy. For an orderly drain prefer [shutdown].
     */
    fun cancel()
}

