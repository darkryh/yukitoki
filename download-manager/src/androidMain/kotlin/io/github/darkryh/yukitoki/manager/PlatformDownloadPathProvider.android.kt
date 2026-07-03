package io.github.darkryh.yukitoki.manager

import android.os.Environment
import okio.Path
import okio.Path.Companion.toPath

actual object PlatformDownloadPathProvider {
    actual fun defaultDownloadDirectory(): Path {
        val context = AndroidDownloadPathContext.get()
            ?: error(
                "AndroidDownloadPathContext.register(context) must be called (e.g. in " +
                    "Application.onCreate) before a download omits an explicit destinationPath, " +
                    "or set DownloadRequest.destinationPath. Resolving the default Downloads " +
                    "directory needs an application Context to pick a writable, " +
                    "scoped-storage-safe location."
            )
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return baseDir.absolutePath.toPath()
    }
}
