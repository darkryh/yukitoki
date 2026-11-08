package io.github.darkryh.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.decodeURLPart

/**
 * A browser-owned download request. Completion and the final filesystem path are not observable.
 *
 * [headers] is only honored on wasm: when empty (the default), dispatch performs a plain
 * `<a download>` anchor navigation, exactly as before. When non-empty, dispatch instead issues a
 * `fetch()` request carrying [headers] and saves the response body via a `Blob` object URL — see
 * [PlatformDownloadIntentDispatcher] and `docs/how-to/download-on-wasm.md` for the tradeoffs (the
 * entire response is buffered in memory; there is no progress or streaming). On every other
 * platform, [DownloadIntents.dispatch] always returns [DownloadIntentResult.Rejected], so [headers]
 * has no effect there.
 */
public data class DownloadIntentRequest(
    public val url: String,
    public val fileName: String? = null,
    public val headers: Headers = Headers.Empty,
) {
    init {
        require(url.isNotBlank()) { "url must not be blank" }
        fileName?.let(::validateDownloadFileName)
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

    /**
     * Dispatches [request] as a browser-owned download.
     *
     * On wasm, an empty [DownloadIntentRequest.headers] takes the plain anchor-navigation path
     * (unchanged since the intents API was introduced). A non-empty [DownloadIntentRequest.headers]
     * switches to a `fetch()`-backed path that buffers the whole response as a `Blob` before saving
     * it, so it has no progress reporting and is only suitable for bounded-size payloads. On every
     * other platform this always returns [DownloadIntentResult.Rejected].
     */
    public suspend fun dispatch(request: DownloadIntentRequest): DownloadIntentResult =
        PlatformDownloadIntentDispatcher.dispatch(request)
}

internal expect object PlatformDownloadIntentDispatcher {
    val isSupported: Boolean
    suspend fun dispatch(request: DownloadIntentRequest): DownloadIntentResult
}

internal fun intentFileNameFor(request: DownloadIntentRequest): String =
    request.fileName?.takeIf { it.isNotBlank() }?.sanitizeDownloadFileName()
        ?: fileNameFromUrl(request.url)
        ?: "download.bin"

/** The safe file name a URL's last path segment suggests, or null when it suggests none. */
internal fun fileNameFromUrl(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').substringAfter("://", "")
    if ('/' !in path) return null
    val segment = path.substringAfterLast('/')
    val decoded = runCatching { segment.decodeURLPart() }.getOrDefault(segment)
    return decoded.substringAfterLast('/').substringAfterLast('\\').sanitizeDownloadFileName()
}

private fun String.sanitizeDownloadFileName(): String? =
    filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ' ' }
        .trim()
        .takeUnless { name -> name.all { it == '.' } }
