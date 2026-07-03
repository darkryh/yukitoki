package io.github.darkryh.yukitoki.manager

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual object PlatformDownloadPathProvider {
    actual fun defaultDownloadDirectory(): Path {
        // iOS apps can only write inside their sandbox. `NSUserDirectory`
        // resolves to `/var/mobile` on device (and the host user's home in
        // the simulator) — both are sandbox-forbidden and silently break
        // any large file write (e.g. Whisper model downloads).
        //
        // Documents is always writable, survives app updates, and matches
        // what `SttCapabilityProbe.ios.kt` already uses for free-space
        // probing — so model downloads land on the same volume we probed.
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
