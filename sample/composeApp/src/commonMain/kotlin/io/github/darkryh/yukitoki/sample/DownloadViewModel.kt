package io.github.darkryh.yukitoki.sample

import io.github.darkryh.yukitoki.manager.DownloadManager
import io.github.darkryh.yukitoki.manager.DownloadPriority
import io.github.darkryh.yukitoki.sample.ui.DownloadUiIntent
import io.github.darkryh.yukitoki.sample.ui.DownloadUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A plain, platform-agnostic presentation model wrapping a [DownloadManager].
 * It processes [DownloadUiIntent] and exposes a single [DownloadUiState] StateFlow.
 * Kept free of any androidx/lifecycle dependency so the exact same class compiles
 * and runs on Android, iOS and desktop.
 */
class DownloadViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val manager: DownloadManager = DownloadManager.create()

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
                scope.launch { manager.setMaxConcurrentDownloads((currentConcurrency - 1).coerceAtLeast(1)) }
            }
            is DownloadUiIntent.IncreaseConcurrency -> {
                val currentConcurrency = state.value.maxConcurrentDownloads
                scope.launch { manager.setMaxConcurrentDownloads(currentConcurrency + 1) }
            }
        }
    }

    private fun enqueue(url: String, priority: DownloadPriority = DownloadPriority.NORMAL) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            manager.enqueue(url = trimmed, priority = priority)
        }
    }

    /** Non-suspending teardown for a UI lifecycle callback (e.g. `onDispose`). */
    fun close() {
        manager.close()
        scope.cancel()
    }
}
