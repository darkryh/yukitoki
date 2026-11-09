package io.github.darkryh.yukitoki.integration

import io.github.darkryh.yukitoki.manager.DownloadManager
import io.github.darkryh.yukitoki.manager.DownloadManagerConfig
import okio.FileSystem

internal actual val checkFileSystem: FileSystem = FileSystem.SYSTEM
internal actual val platformName: String = "ios"

// Ktor's Darwin engine reports every response as HTTP/1.1.
internal actual val engineReportsHttpVersion: Boolean = false

// The simulator writes to the host's file system.
internal actual val canReachHostVolume: Boolean = true

internal actual fun newManager(config: DownloadManagerConfig): DownloadManager = DownloadManager.create(config)

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class, kotlin.experimental.ExperimentalNativeApi::class, ExperimentalStdlibApi::class)
internal actual fun usedHeapBytes(): Long? =
    kotlin.native.runtime.GC.lastGCInfo?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes }
