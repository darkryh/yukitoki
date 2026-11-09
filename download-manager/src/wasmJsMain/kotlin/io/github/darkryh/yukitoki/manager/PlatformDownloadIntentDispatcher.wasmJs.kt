@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.darkryh.yukitoki.manager

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.url.URL
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Headers as JsFetchHeaders

/**
 * wasm/browser [DownloadIntentRequest] dispatch.
 *
 * With no headers the browser owns the transfer, and there is no way to observe completion,
 * progress, or the saved path. A same-origin URL is a plain `<a download>` click. Across origins the
 * browser ignores `download` and may display the file, which must not replace the running app, so
 * this uses a new tab inside a click and a hidden frame outside one, where a new tab gets blocked.
 *
 * With headers (authenticated downloads), a plain anchor cannot carry request headers, so this
 * instead issues a `fetch()` carrying [DownloadIntentRequest.headers], buffers the **entire**
 * response body in memory as a `Blob`, and saves that blob through the same anchor mechanic via a
 * short-lived `blob:` object URL. This has no streaming and no progress reporting: it is suitable
 * for bounded-size authenticated payloads (e.g. a document export), not large files. See
 * `docs/how-to/download-on-wasm.md`.
 */
internal actual object PlatformDownloadIntentDispatcher {
    actual val isSupported: Boolean = true

    actual suspend fun dispatch(request: DownloadIntentRequest): DownloadIntentResult {
        val fileName = intentFileNameFor(request)
        return if (request.headers.isEmpty()) {
            dispatchViaAnchorNavigation(request.url, fileName)
        } else {
            dispatchViaAuthenticatedFetch(request, fileName)
        }
    }

    private fun dispatchViaAnchorNavigation(url: String, fileName: String): DownloadIntentResult {
        val crossOrigin = isCrossOrigin(url)
        if (crossOrigin && !hasUserActivation()) return dispatchViaHiddenFrame(url)
        val anchor = createHiddenDownloadAnchor(url, fileName)
        if (crossOrigin) {
            anchor.target = "_blank"
            anchor.rel = "noopener"
        }
        return try {
            anchor.click()
            DownloadIntentResult.Dispatched
        } catch (t: Throwable) {
            DownloadIntentResult.Rejected(t.message ?: "Browser rejected download intent.")
        } finally {
            anchor.remove()
        }
    }

    private fun dispatchViaHiddenFrame(url: String): DownloadIntentResult {
        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.src = url
        frame.setAttribute("style", "display:none")
        return try {
            document.body?.appendChild(frame)
            // The browser owns a started download, so removing the frame later doesn't cancel it.
            window.setTimeout({
                frame.remove()
                null
            }, HIDDEN_FRAME_LIFETIME_MILLIS)
            DownloadIntentResult.Dispatched
        } catch (t: Throwable) {
            DownloadIntentResult.Rejected(t.message ?: "Browser rejected download intent.")
        }
    }

    private fun isCrossOrigin(url: String): Boolean {
        val resolved = URL(url, window.location.href)
        val isHttp = resolved.protocol == "http:" || resolved.protocol == "https:"
        return isHttp && resolved.origin != window.location.origin
    }

    private suspend fun dispatchViaAuthenticatedFetch(
        request: DownloadIntentRequest,
        fileName: String,
    ): DownloadIntentResult {
        val jsHeaders = JsFetchHeaders()
        request.headers.entries().forEach { (name, values) ->
            values.forEach { value -> jsHeaders.append(name, value) }
        }

        return try {
            val response = window.fetch(request.url, authenticatedGetRequestInit(jsHeaders)).await()

            if (!response.ok) {
                return DownloadIntentResult.Rejected("Download request failed with HTTP ${response.status}.")
            }

            val blob = response.blob().await()
            val objectUrl = URL.createObjectURL(blob)
            val anchor = createHiddenDownloadAnchor(objectUrl, fileName)
            try {
                anchor.click()
                DownloadIntentResult.Dispatched
            } finally {
                anchor.remove()
                // Revoking synchronously can abort the save in some browsers, so revoke on the
                // next macrotask instead of immediately after the click.
                window.setTimeout({
                    URL.revokeObjectURL(objectUrl)
                    null
                }, 0)
            }
        } catch (t: Throwable) {
            val cause = t.message?.removePrefix("Non-Kotlin exception ")?.substringBefore(" of type '")
            DownloadIntentResult.Rejected("Download request failed: $cause (a network error, or blocked by CORS).")
        }
    }

    private fun createHiddenDownloadAnchor(href: String, fileName: String): HTMLAnchorElement {
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = href
        anchor.download = fileName
        anchor.setAttribute("style", "display:none")
        // The app's own click listeners must not see this synthetic click. The download still starts.
        anchor.addEventListener("click", { event -> event.stopPropagation() })
        document.body?.appendChild(anchor)
        return anchor
    }
}

private const val HIDDEN_FRAME_LIFETIME_MILLIS = 60_000

/** False where the browser lacks the API, which picks the path that works without a click. */
private fun hasUserActivation(): Boolean =
    js("(typeof navigator.userActivation !== 'undefined' && navigator.userActivation.isActive)")

/**
 * Builds a minimal `RequestInit` carrying only `method`/`headers`. The `RequestInit(...)` factory
 * function in kotlinx-browser fills every *unset* optional parameter (`cache`, `redirect`, ...)
 * with Kotlin `null` rather than true JS `undefined`, and the browser's `fetch()` rejects an
 * explicit `null` for enum-typed options such as `cache` outright. A bare object literal with only
 * the two keys we need sidesteps that entirely. Kotlin/Wasm requires `js(...)` calls to be the sole
 * expression of a top-level function body, hence this sits outside [PlatformDownloadIntentDispatcher].
 */
private fun authenticatedGetRequestInit(headers: JsFetchHeaders): RequestInit =
    js("({ method: 'GET', headers: headers })")
