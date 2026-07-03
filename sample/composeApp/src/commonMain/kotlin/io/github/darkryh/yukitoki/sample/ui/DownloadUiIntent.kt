package io.github.darkryh.yukitoki.sample.ui

import io.github.darkryh.yukitoki.manager.DownloadId
import io.github.darkryh.yukitoki.manager.DownloadPriority

sealed interface DownloadUiIntent {
    data class UpdateUrl(val url: String) : DownloadUiIntent
    data class AddDownload(val priority: DownloadPriority = DownloadPriority.NORMAL) : DownloadUiIntent
    data class PauseDownload(val id: DownloadId) : DownloadUiIntent
    data class ResumeDownload(val id: DownloadId) : DownloadUiIntent
    data class CancelDownload(val id: DownloadId) : DownloadUiIntent
    data class RetryDownload(val id: DownloadId) : DownloadUiIntent
    data object DecreaseConcurrency : DownloadUiIntent
    data object IncreaseConcurrency : DownloadUiIntent
}
