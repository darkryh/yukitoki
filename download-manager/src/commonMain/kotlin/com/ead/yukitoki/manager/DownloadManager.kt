package com.ead.yukitoki.manager

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface DownloadManager {
    val state: StateFlow<DownloadManagerState>

    fun observe(downloadId: DownloadId): Flow<DownloadSnapshot?>
    suspend fun await(downloadId: DownloadId): DownloadResult?

    suspend fun enqueue(request: DownloadRequest, id: DownloadId = DownloadId.new()): DownloadId
    suspend fun download(request: DownloadRequest): DownloadResult
    suspend fun pause(downloadId: DownloadId): CommandAck
    suspend fun resume(downloadId: DownloadId): CommandAck
    suspend fun cancel(downloadId: DownloadId): CommandAck
    suspend fun retry(downloadId: DownloadId): CommandAck
    suspend fun move(downloadId: DownloadId, newPosition: Int): CommandAck
    suspend fun pauseAll(): CommandAck
    suspend fun resumeAll(): CommandAck
    suspend fun cancelAll(): CommandAck
    suspend fun retryAllFailed(): CommandAck
    suspend fun setConcurrency(concurrency: DownloadConcurrency): CommandAck

    /**
     * Graceful teardown. When [cancelActive] is false, in-flight downloads are allowed
     * to finish (their final files are written) before this returns; when true, active
     * downloads are cancelled. Outstanding [await] callers are released.
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
