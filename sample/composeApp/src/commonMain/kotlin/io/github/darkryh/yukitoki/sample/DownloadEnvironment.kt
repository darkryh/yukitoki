package io.github.darkryh.yukitoki.sample

import io.ktor.client.HttpClient
import okio.FileSystem
import okio.Path

/**
 * Per-platform wiring the sample needs to drive the download manager: a configured Ktor
 * [HttpClient] (a different engine per platform) and a writable directory to download into.
 * The shared Compose UI only depends on this interface, so the same UI runs on Android,
 * iOS and desktop.
 */
interface DownloadEnvironment {
    val httpClient: HttpClient
    val fileSystem: FileSystem
    val downloadsDir: Path
    fun close()
}
