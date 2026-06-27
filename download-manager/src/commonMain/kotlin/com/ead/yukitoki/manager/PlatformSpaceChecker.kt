package com.ead.yukitoki.manager

import okio.Path

internal expect object PlatformSpaceChecker {
    var mockUsableSpace: Long?
    /**
     * Returns the available free space in bytes on the partition containing the given path.
     * Returns -1L if space checks are not supported or failed on this platform.
     */
    fun getUsableSpace(path: Path): Long
}
