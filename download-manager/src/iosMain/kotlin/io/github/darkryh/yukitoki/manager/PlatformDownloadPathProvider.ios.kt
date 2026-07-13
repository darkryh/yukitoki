package io.github.darkryh.yukitoki.manager

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal actual object PlatformDownloadPathProvider {
    actual val isSupported: Boolean = true

    actual fun defaultDownloadDirectory(): Path {
        // iOS apps can only write inside their sandbox. `NSUserDirectory`
        // resolves to `/var/mobile` on device (and the host user's home in
        // the simulator) — both are sandbox-forbidden and silently break
        // any large file write (e.g. Whisper model downloads).
        //
        // Documents is writable, survives app updates, and can be exposed in Files
        // when the host app enables the documented Info.plist options.
        val directories = NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        )
        val base = (directories.firstOrNull() as? String)?.takeIf { it.isNotBlank() }
            ?: error("Documents directory unavailable on iOS — app sandbox is broken")
        return "$base/Downloads".toPath()
    }
}
