package io.github.darkryh.yukitoki.sample.ui

import io.github.darkryh.yukitoki.manager.DownloadSnapshot

data class DownloadUiState(
    val url: String = "https://fsn1-speed.hetzner.com/10GB.bin",
    val maxConcurrentDownloads: Int = 2,
    val snapshots: List<DownloadSnapshot> = emptyList()
)
