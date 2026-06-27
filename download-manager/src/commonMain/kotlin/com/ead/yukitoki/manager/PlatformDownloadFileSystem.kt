package com.ead.yukitoki.manager

import okio.FileSystem

internal expect object PlatformDownloadFileSystem {
    val default: FileSystem
}
