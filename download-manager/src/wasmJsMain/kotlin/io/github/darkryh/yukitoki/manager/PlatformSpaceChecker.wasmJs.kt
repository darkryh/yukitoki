package io.github.darkryh.yukitoki.manager

import okio.Path

internal actual object PlatformSpaceChecker {
    actual var mockUsableSpace: Long? = null
    actual fun getUsableSpace(path: Path): Long = mockUsableSpace ?: -1L
}
