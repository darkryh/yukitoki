package io.github.darkryh.yukitoki.manager

import okio.Path
import okio.Path.Companion.toPath

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object PlatformDownloadPathProvider {
    actual fun defaultDownloadDirectory(): Path = "/browser-downloads".toPath()
}
