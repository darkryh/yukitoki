package io.github.darkryh.yukitoki.manager

import okio.FileSystem
import okio.SYSTEM

internal actual object PlatformDownloadFileSystem {
    actual val default: FileSystem = FileSystem.SYSTEM
}
