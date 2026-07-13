package io.github.darkryh.yukitoki.manager

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement

internal actual object PlatformDownloadIntentDispatcher {
    actual val isSupported: Boolean = true

    actual suspend fun dispatch(request: DownloadIntentRequest): DownloadIntentResult {
        val fileName = intentFileNameFor(request)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = request.url
        anchor.download = fileName
        anchor.rel = "noopener"
        anchor.setAttribute("style", "display:none")
        document.body?.appendChild(anchor)
        return try {
            anchor.click()
            DownloadIntentResult.Dispatched
        } catch (t: Throwable) {
            DownloadIntentResult.Rejected(t.message ?: "Browser rejected download intent.")
        } finally {
            anchor.remove()
        }
    }

}
