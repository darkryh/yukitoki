package io.github.darkryh.yukitoki.manager

import okio.Path

/** Writable, platform-appropriate locations supplied by Yukitoki. */
public object DownloadLocations {
    /** Whether this platform exposes filesystem-backed managed downloads. */
    public val isSupported: Boolean
        get() = PlatformDownloadPathProvider.isSupported

    /**
     * A writable Downloads directory for this app. The directory can be sandboxed and is
     * not necessarily the user's shared/public Downloads collection.
     */
    public val downloads: Path
        get() = PlatformDownloadPathProvider.defaultDownloadDirectory()
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect object PlatformDownloadPathProvider {
    public val isSupported: Boolean
    fun defaultDownloadDirectory(): Path
}
