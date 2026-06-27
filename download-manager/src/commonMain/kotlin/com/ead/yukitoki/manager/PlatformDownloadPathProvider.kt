package com.ead.yukitoki.manager

import okio.Path

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object PlatformDownloadPathProvider {
    fun defaultDownloadDirectory(): Path
}

