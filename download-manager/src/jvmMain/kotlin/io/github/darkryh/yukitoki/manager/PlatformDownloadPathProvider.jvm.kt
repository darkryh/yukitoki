package io.github.darkryh.yukitoki.manager

import okio.Path
import okio.Path.Companion.toPath
import java.io.File

internal actual object PlatformDownloadPathProvider {
    actual val isSupported: Boolean = true

    actual fun defaultDownloadDirectory(): Path {
        val home = System.getProperty("user.home") ?: "."
        if (System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) {
            linuxDownloadDirectory(home)?.let { return it.toPath() }
        }
        return "$home/Downloads".toPath()
    }

    private fun linuxDownloadDirectory(home: String): String? {
        System.getenv("XDG_DOWNLOAD_DIR")?.takeIf { it.isNotBlank() }?.let {
            return expandHome(it.trim().trim('"'), home)
        }
        val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: "$home/.config"
        val config = File(configHome, "user-dirs.dirs")
        if (!config.isFile) return null
        val raw = config.useLines { lines ->
            lines.firstOrNull { it.trimStart().startsWith("XDG_DOWNLOAD_DIR=") }
                ?.substringAfter('=')
                ?.trim()
                ?.trim('"')
        } ?: return null
        return expandHome(raw, home)
    }

    private fun expandHome(value: String, home: String): String =
        value.replace("$" + "HOME", home).let { if (it == "~") home else it.replace("~/", "$home/") }
}
