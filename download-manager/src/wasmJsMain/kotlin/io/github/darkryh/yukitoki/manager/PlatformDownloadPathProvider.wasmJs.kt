package io.github.darkryh.yukitoki.manager

import okio.Path

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual object PlatformDownloadPathProvider {
    actual val isSupported: Boolean = false

    actual fun defaultDownloadDirectory(): Path =
        throw UnsupportedOperationException("Browser downloads do not expose a filesystem path. Use DownloadIntents.")
}
