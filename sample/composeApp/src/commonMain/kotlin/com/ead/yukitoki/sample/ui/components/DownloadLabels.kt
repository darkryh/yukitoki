package com.ead.yukitoki.sample.ui.components

import com.ead.yukitoki.manager.DownloadFailure
import com.ead.yukitoki.manager.DownloadState

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024) return "${((kb * 10).toInt() / 10.0)} KB/s"
    val mb = kb / 1024.0
    return "${((mb * 100).toInt() / 100.0)} MB/s"
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${((kb * 10).toInt() / 10.0)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${((mb * 100).toInt() / 100.0)} MB"
    val gb = mb / 1024.0
    return "${((gb * 100).toInt() / 100.0)} GB"
}

fun formatEta(etaMillis: Long?): String {
    if (etaMillis == null || etaMillis < 0) return "--:--"
    val seconds = etaMillis / 1000
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    if (minutes < 60) return "${minutes}m ${remainingSeconds}s"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return "${hours}h ${remainingMinutes}m"
}

fun statusLabel(state: DownloadState): String = when (state) {
    is DownloadState.Queued -> "Queued"
    is DownloadState.Starting -> "Starting"
    is DownloadState.Running -> {
        val p = state.progress
        val pct = p.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
        val speed = p.speedBytesPerSecond?.let { " - ${formatSpeed(it)}" } ?: ""
        "Downloading$pct$speed"
    }
    is DownloadState.Pausing -> "Pausing"
    is DownloadState.Paused -> "Paused"
    is DownloadState.Cancelling -> "Cancelling"
    is DownloadState.Cancelled -> "Cancelled"
    is DownloadState.Succeeded -> "Done"
    is DownloadState.Failed -> "Failed: ${failureLabel(state.failure)}"
}

fun failureLabel(failure: DownloadFailure): String = when (failure) {
    is DownloadFailure.HttpStatus -> failure.message?.let { "HTTP ${failure.code}: $it" } ?: "HTTP ${failure.code}"
    is DownloadFailure.Network -> "network error: ${failure.message}"
    is DownloadFailure.Timeout -> "timeout: ${failure.message}"
    is DownloadFailure.PermissionDenied -> failure.message?.let { "permission denied: $it" } ?: "permission denied"
    is DownloadFailure.NoSpace -> failure.message?.let { "no space: $it" } ?: "no space"
    is DownloadFailure.AlreadyExists -> failure.message?.let { "already exists: $it" } ?: "already exists"
    is DownloadFailure.FileSystem -> "file system error: ${failure.message}"
    is DownloadFailure.IntegrityMismatch -> "size mismatch: expected ${formatBytes(failure.expectedSize)}, got ${formatBytes(failure.actualSize)}"
    is DownloadFailure.ChecksumMismatch -> "checksum mismatch"
    is DownloadFailure.ServerChanged -> "server file changed: ${failure.message}"
    is DownloadFailure.CancelledByUser -> "cancelled"
    is DownloadFailure.PausedByUser -> "paused"
    is DownloadFailure.Unknown -> failure.message
}
