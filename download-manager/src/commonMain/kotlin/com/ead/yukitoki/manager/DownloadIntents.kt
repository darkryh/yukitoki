package com.ead.yukitoki.manager

import okio.Path
import okio.Path.Companion.toPath

object DownloadIntents {
    val isSupported: Boolean
        get() = PlatformDownloadIntentDispatcher.isSupported

    suspend fun dispatch(request: DownloadRequest): DownloadResult =
        PlatformDownloadIntentDispatcher.dispatch(request)
}

internal expect object PlatformDownloadIntentDispatcher {
    val isSupported: Boolean
    suspend fun dispatch(request: DownloadRequest): DownloadResult
}

internal fun intentFileNameFor(request: DownloadRequest): String {
    return request.fileName?.takeIf { it.isNotBlank() }?.sanitizeDownloadFileName()
        ?: request.destinationPath?.name?.takeIf { it.isNotBlank() }?.sanitizeDownloadFileName()
        ?: request.url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/', "")
            .takeIf { it.isNotBlank() }
            ?.sanitizeDownloadFileName()
        ?: "download.bin"
}

internal fun browserIntentResultPath(request: DownloadRequest): Path =
    request.destinationPath ?: intentFileNameFor(request).toPath()

private fun String.sanitizeDownloadFileName(): String =
    filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ' ' }
        .trim()
        .ifBlank { "download.bin" }
