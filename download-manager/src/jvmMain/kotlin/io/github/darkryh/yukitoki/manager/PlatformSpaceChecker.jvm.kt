package io.github.darkryh.yukitoki.manager

import okio.Path
import java.io.File

internal actual object PlatformSpaceChecker {
    actual var mockUsableSpace: Long? = null

    actual fun getUsableSpace(path: Path): Long {
        mockUsableSpace?.let { return it }
        return try {
            val file = File(path.toString())
            var current: File? = file
            while (current != null && !current.exists()) {
                current = current.parentFile
            }
            current?.usableSpace ?: -1L
        } catch (t: Throwable) {
            -1L
        }
    }
}
