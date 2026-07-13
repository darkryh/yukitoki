package io.github.darkryh.yukitoki.manager

import android.os.Environment
import okio.Path
import okio.Path.Companion.toPath

internal actual object PlatformDownloadPathProvider {
    actual val isSupported: Boolean
        get() = AndroidDownloadPathContext.get() != null

    actual fun defaultDownloadDirectory(): Path {
        val context = AndroidDownloadPathContext.get()
            ?: error(
                "Yukitoki's Android initializer did not run. Keep the manifest-merged " +
                    "YukitokiInitializer enabled, or use an explicit DownloadDestination. " +
                    "An application Context is required to resolve the scoped-storage-safe " +
                    "Downloads directory."
            )
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return baseDir.absolutePath.toPath()
    }
}
