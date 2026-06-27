package com.ead.yukitoki.manager

import okio.Path
import okio.Path.Companion.toPath

actual object PlatformDownloadPathProvider {
    actual fun defaultDownloadDirectory(): Path {
        val home = System.getProperty("user.home") ?: "."
        return "$home/Downloads".toPath()
    }
}

