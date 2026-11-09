@file:OptIn(ExperimentalWasmJsInterop::class)

import io.github.darkryh.yukitoki.manager.DownloadIntentRequest
import io.github.darkryh.yukitoki.manager.DownloadIntentResult
import io.github.darkryh.yukitoki.manager.DownloadIntents
import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.url.URLSearchParams

private fun publish(outcome: String, clicks: Int): Unit =
    js("{ globalThis.__outcome = outcome; globalThis.__clicks = clicks; }")

private var clicks = 0

/** Dispatches the download the query describes, then publishes the result for run.mjs to read. */
fun main() {
    val query = URLSearchParams(window.location.search.toJsString())
    val url = query.get("url") ?: return
    val fileName = query.get("fileName")
    val headers: Headers = if (query.get("auth") != null) headersOf("Authorization", "Bearer test") else Headers.Empty
    val onClick = query.get("click") != null

    val dispatch = {
        MainScope().launch {
            val result = DownloadIntents.dispatch(DownloadIntentRequest(url, fileName, headers))
            // Time for a synthetic click to reach the listener below, if one leaks.
            delay(300)
            publish(if (result is DownloadIntentResult.Rejected) "Rejected: ${result.reason}" else "Dispatched", clicks)
        }
        Unit
    }

    document.addEventListener("click", {
        clicks++
        if (onClick && clicks == 1) dispatch()
    })
    if (!onClick) dispatch()
}
