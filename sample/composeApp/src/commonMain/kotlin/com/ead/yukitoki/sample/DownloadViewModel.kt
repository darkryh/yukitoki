package com.ead.yukitoki.sample

import com.ead.yukitoki.manager.DefaultDownloadManager
import com.ead.yukitoki.manager.DownloadConcurrency
import com.ead.yukitoki.manager.DownloadId
import com.ead.yukitoki.manager.DownloadManager
import com.ead.yukitoki.manager.DownloadPriority
import com.ead.yukitoki.manager.DownloadRequest
import com.ead.yukitoki.sample.ui.DownloadUiIntent
import com.ead.yukitoki.sample.ui.DownloadUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * A plain, platform-agnostic presentation model wrapping a [DownloadManager].
 * It processes [DownloadUiIntent] and exposes a single [DownloadUiState] StateFlow.
 * Kept free of any androidx/lifecycle dependency so the exact same class compiles
 * and runs on Android, iOS and desktop.
 */
class DownloadViewModel(private val environment: DownloadEnvironment) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val manager: DownloadManager = DefaultDownloadManager(
        httpClient = environment.httpClient,
        scope = scope,
        fileSystem = environment.fileSystem,
    )

    // Keep track of the local UI state like the URL input field
    private val _urlInput = MutableStateFlow("https://fsn1-speed.hetzner.com/10GB.bin")

    // Unify manager state and local UI state into a single DownloadUiState
    val state: StateFlow<DownloadUiState> = combine(
        _urlInput,
        manager.state
    ) { url, managerState ->
        DownloadUiState(
            url = url,
            maxConcurrentDownloads = managerState.maxConcurrentDownloads,
            snapshots = managerState.snapshots.values.toList()
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DownloadUiState()
    )

    /**
     * Process an intent/action initiated from the view.
     */
    fun onIntent(intent: DownloadUiIntent) {
        when (intent) {
            is DownloadUiIntent.UpdateUrl -> {
                _urlInput.value = intent.url
            }
            is DownloadUiIntent.AddDownload -> {
                enqueue(_urlInput.value, intent.priority)
            }
            is DownloadUiIntent.PauseDownload -> {
                scope.launch { manager.pause(intent.id) }
            }
            is DownloadUiIntent.ResumeDownload -> {
                scope.launch { manager.resume(intent.id) }
            }
            is DownloadUiIntent.CancelDownload -> {
                scope.launch { manager.cancel(intent.id) }
            }
            is DownloadUiIntent.RetryDownload -> {
                scope.launch { manager.retry(intent.id) }
            }
            is DownloadUiIntent.DecreaseConcurrency -> {
                val currentConcurrency = state.value.maxConcurrentDownloads
                scope.launch { manager.setConcurrency(DownloadConcurrency.Fixed((currentConcurrency - 1).coerceAtLeast(1))) }
            }
            is DownloadUiIntent.IncreaseConcurrency -> {
                val currentConcurrency = state.value.maxConcurrentDownloads
                scope.launch { manager.setConcurrency(DownloadConcurrency.Fixed(currentConcurrency + 1)) }
            }
        }
    }

    private fun enqueue(url: String, priority: DownloadPriority = DownloadPriority.NORMAL) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val destination = "${environment.downloadsDir}/${fileNameFor(trimmed)}".toPath()
        scope.launch {
            manager.enqueue(DownloadRequest(url = trimmed, destinationPath = destination, priority = priority))
        }
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
