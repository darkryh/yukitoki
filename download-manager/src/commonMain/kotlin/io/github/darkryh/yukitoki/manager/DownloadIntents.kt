package io.github.darkryh.yukitoki.manager

/** A browser-owned download request. Completion and the final filesystem path are not observable. */
public data class DownloadIntentRequest(
    public val url: String,
    public val suggestedFileName: String? = null,
) {
    init {
        require(url.isNotBlank()) { "url must not be blank" }
        suggestedFileName?.let(::validateIntentFileName)
    }
}

public sealed interface DownloadIntentResult {
    /** The browser accepted the intent. This does not mean the transfer has completed. */
    public data object Dispatched : DownloadIntentResult
    public data class Rejected(val reason: String) : DownloadIntentResult
}

public object DownloadIntents {
    public val isSupported: Boolean
        get() = PlatformDownloadIntentDispatcher.isSupported

    public suspend fun dispatch(request: DownloadIntentRequest): DownloadIntentResult =
        PlatformDownloadIntentDispatcher.dispatch(request)
}

internal expect object PlatformDownloadIntentDispatcher {
    public val isSupported: Boolean
    public suspend fun dispatch(request: DownloadIntentRequest): DownloadIntentResult
}

internal fun intentFileNameFor(request: DownloadIntentRequest): String =
    request.suggestedFileName?.takeIf { it.isNotBlank() }?.sanitizeDownloadFileName()
        ?: request.url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/', "")
            .takeIf { it.isNotBlank() }
            ?.sanitizeDownloadFileName()
        ?: "download.bin"

private fun validateIntentFileName(fileName: String) {
    require(fileName.isNotBlank()) { "suggestedFileName must not be blank" }
    require(fileName != "." && fileName != "..") { "suggestedFileName must be a file name" }
    require(fileName.none { it == '/' || it == '\\' }) { "suggestedFileName must not contain path separators" }
}

private fun String.sanitizeDownloadFileName(): String =
    filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ' ' }
        .trim()
        .ifBlank { "download.bin" }
