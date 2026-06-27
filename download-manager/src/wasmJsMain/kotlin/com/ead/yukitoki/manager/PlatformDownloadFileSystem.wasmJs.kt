package com.ead.yukitoki.manager

import okio.FileSystem

internal actual object PlatformDownloadFileSystem {
    actual val default: FileSystem
        get() = error("Browser download filesystem is not wired yet.")
}
