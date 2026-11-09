package io.github.darkryh.yukitoki.integration

import io.github.darkryh.yukitoki.manager.DownloadManager
import io.github.darkryh.yukitoki.manager.DownloadManagerConfig
import okio.FileSystem

internal actual val checkFileSystem: FileSystem = FileSystem.SYSTEM
internal actual val platformName: String = "jvm"
internal actual val engineReportsHttpVersion: Boolean = true
internal actual val canReachHostVolume: Boolean = true

internal actual fun newManager(config: DownloadManagerConfig): DownloadManager = DownloadManager.create(config)

internal actual fun usedHeapBytes(): Long? =
    Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
