package com.ead.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import kotlinx.browser.document
import okio.Path.Companion.toPath
import org.w3c.dom.HTMLAnchorElement

internal actual object PlatformDownloadIntentDispatcher {
    actual val isSupported: Boolean = true

    actual suspend fun dispatch(request: DownloadRequest): DownloadResult {
        val rejection = validateBrowserIntentRequest(request)
        if (rejection != null) return DownloadResult.Failure(DownloadFailure.Unknown(rejection))

        val fileName = intentFileNameFor(request)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = request.url
        anchor.download = fileName
        anchor.rel = "noopener"
        anchor.setAttribute("style", "display:none")
        document.body?.appendChild(anchor)
        return try {
            anchor.click()
            DownloadResult.Success(
                DownloadSuccess(
                    finalPath = request.destinationPath ?: fileName.toPath(),
                    bytesDownloaded = 0L,
                    totalBytes = null,
                )
            )
        } catch (t: Throwable) {
            DownloadResult.Failure(DownloadFailure.Unknown(t.message ?: "Browser rejected download intent."))
        } finally {
            anchor.remove()
        }
    }

    private fun validateBrowserIntentRequest(request: DownloadRequest): String? {
        if (request.method != HttpMethod.Get) {
            return "Browser download intents support GET only."
        }
        if (request.maxSegments > 1) {
            return "Browser download intents cannot control segmented range downloads."
        }
        if (request.constraints.requireWifi || request.constraints.requireCharging) {
            return "Browser download intents cannot enforce network or charging constraints."
        }
        if (request.headers != Headers.Empty && request.headers.entries().isNotEmpty()) {
            return "Browser download intents cannot attach custom request headers."
        }
        if (request.expectedChecksum != null) {
            return "Browser download intents cannot verify checksums."
        }
        if (request.expectedSizeBytes != null) {
            return "Browser download intents cannot verify expected size."
        }
        return null
    }
}
