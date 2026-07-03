package io.github.darkryh.yukitoki.manager

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import platform.Foundation.*

internal actual object PlatformSpaceChecker {
    actual var mockUsableSpace: Long? = null

    @OptIn(ExperimentalForeignApi::class)
    actual fun getUsableSpace(path: Path): Long {
        mockUsableSpace?.let { return it }
        return try {
            val fileManager = NSFileManager.defaultManager
            var currentPath = path.toString()
            while (currentPath.isNotEmpty() && !fileManager.fileExistsAtPath(currentPath)) {
                val lastSlash = currentPath.lastIndexOf('/')
                if (lastSlash < 0) {
                    currentPath = ""
                    break
                }
                currentPath = currentPath.substring(0, lastSlash)
            }
            if (currentPath.isEmpty()) {
                currentPath = NSHomeDirectory()
            }
            val attributes = fileManager.attributesOfFileSystemForPath(currentPath, null)
            val freeSize = attributes?.get(NSFileSystemFreeSize) as? Long
            freeSize ?: -1L
        } catch (t: Throwable) {
            -1L
        }
    }
}
