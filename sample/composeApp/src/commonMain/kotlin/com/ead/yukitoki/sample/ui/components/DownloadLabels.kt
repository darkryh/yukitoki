package com.ead.yukitoki.sample.ui.components

import com.ead.yukitoki.manager.DownloadFailure
import com.ead.yukitoki.manager.DownloadState

fun statusLabel(state: DownloadState): String = when (state) {
    is DownloadState.Queued -> "Queued"
    is DownloadState.Starting -> "Starting"
    is DownloadState.Running -> {
        val p = state.progress
        val pct = p.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
        val speed = p.speedBytesPerSecond?.let { " - ${it / 1024} KB/s" } ?: ""
        "Downloading$pct$speed"
    }
    is DownloadState.Pausing -> "Pausing"
    is DownloadState.Paused -> "Paused"
    is DownloadState.Cancelling -> "Cancelling"
    is DownloadState.Cancelled -> "Cancelled"
    is DownloadState.Succeeded -> "Done - ${state.result.bytesDownloaded} bytes"
    is DownloadState.Failed -> "Failed: ${failureLabel(state.failure)}"
}

fun failureLabel(failure: DownloadFailure): String = when (failure) {
    is DownloadFailure.HttpStatus -> "HTTP ${failure.code}"
    is DownloadFailure.Network -> "network error"
    is DownloadFailure.Timeout -> "timeout"
    is DownloadFailure.PermissionDenied -> "permission denied"
    is DownloadFailure.NoSpace -> "no space"
    is DownloadFailure.AlreadyExists -> "already exists"
    is DownloadFailure.FileSystem -> "file system error"
    is DownloadFailure.IntegrityMismatch -> "size mismatch"
    is DownloadFailure.ChecksumMismatch -> "checksum mismatch"
    is DownloadFailure.ServerChanged -> "server file changed"
    is DownloadFailure.CancelledByUser -> "cancelled"
    is DownloadFailure.PausedByUser -> "paused"
    is DownloadFailure.Unknown -> failure.message
}
