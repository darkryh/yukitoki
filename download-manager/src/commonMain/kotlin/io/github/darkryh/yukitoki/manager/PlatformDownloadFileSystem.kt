package io.github.darkryh.yukitoki.manager

import okio.FileSystem

internal expect object PlatformDownloadFileSystem {
    val default: FileSystem
}
