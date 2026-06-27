package com.ead.yukitoki.sample

import com.ead.yukitoki.manager.DefaultDownloadManager
import com.ead.yukitoki.manager.DownloadCommand
import com.ead.yukitoki.manager.DownloadId
import com.ead.yukitoki.manager.DownloadManager
import com.ead.yukitoki.manager.DownloadManagerConfig
import com.ead.yukitoki.manager.DownloadManagerState
import com.ead.yukitoki.manager.DownloadPriority
import com.ead.yukitoki.manager.DownloadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * A plain, platform-agnostic presentation model wrapping a [DownloadManager]. It is created
 * once per screen (see `App`) and disposed with [close]. Kept free of any androidx/lifecycle
 * dependency so the exact same class compiles and runs on Android, iOS and desktop.
 */
class DownloadViewModel(private val environment: DownloadEnvironment) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val manager: DownloadManager = DefaultDownloadManager(
        httpClient = environment.httpClient,
        scope = scope,
        fileSystem = environment.fileSystem,
        config = DownloadManagerConfig(maxConcurrentDownloads = 2),
    )

    /** The whole queue, observed by the UI via `collectAsState`. */
    val state: StateFlow<DownloadManagerState> = manager.observeState()

    fun enqueue(url: String, priority: DownloadPriority = DownloadPriority.NORMAL) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val destination = "${environment.downloadsDir}/${fileNameFor(trimmed)}".toPath()
        scope.launch {
            manager.command(
                DownloadCommand.Enqueue(
                    DownloadRequest(url = trimmed, destinationPath = destination, priority = priority),
                )
            )
        }
    }

    fun pause(id: DownloadId) = dispatch(DownloadCommand.Pause(id))
    fun resume(id: DownloadId) = dispatch(DownloadCommand.Resume(id))
    fun cancel(id: DownloadId) = dispatch(DownloadCommand.Cancel(id))
    fun retry(id: DownloadId) = dispatch(DownloadCommand.Retry(id))
    fun setConcurrency(value: Int) = dispatch(DownloadCommand.SetConcurrency(value.coerceAtLeast(1)))

    private fun dispatch(command: DownloadCommand) {
        scope.launch { manager.command(command) }
    }

    /** Non-suspending teardown for a UI lifecycle callback (e.g. `onDispose`). */
    fun close() {
        manager.cancel()
        environment.close()
    }

    private fun fileNameFor(url: String): String {
        val candidate = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val sanitized = candidate.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return sanitized.ifBlank { "download-${DownloadId.new().value}.bin" }
    }
}
