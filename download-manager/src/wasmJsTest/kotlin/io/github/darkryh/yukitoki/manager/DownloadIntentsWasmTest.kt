package io.github.darkryh.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlinx.browser.document
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloadIntentsWasmTest {
    @Test
    fun `wasm dispatches without claiming transfer completion`() = runTest {
        val result = DownloadIntents.dispatch(
            DownloadIntentRequest(
                url = "data:text/plain,hello",
                fileName = "hello.txt",
            )
        )

        assertTrue(DownloadIntents.isSupported)
        assertIs<DownloadIntentResult.Dispatched>(result)
    }

    @Test
    fun `intent request rejects unsafe suggested names`() {
        assertFailsWith<IllegalArgumentException> {
            DownloadIntentRequest("https://example.com/file.bin", "../file.bin")
        }
    }

    @Test
    fun `empty headers keep the pre-existing plain anchor-navigation bookkeeping`() = runTest {
        // Explicitly passing Headers.Empty (the default) must behave byte-for-byte like the
        // original wasm dispatch that shipped before DownloadIntentRequest.headers existed: a plain
        // anchor click, dispatched synchronously without ever touching fetch().
        val result = DownloadIntents.dispatch(
            DownloadIntentRequest(
                url = "data:text/plain,hello",
                fileName = "hello.txt",
                headers = Headers.Empty,
            )
        )

        assertIs<DownloadIntentResult.Dispatched>(result)
    }

    @Test
    fun `headers switch to the fetch path and surface fetch failures as rejected`() = runTest {
        // Unlike anchor navigation, the fetch branch awaits the network call, so a host that can
        // never resolve (RFC 2606 ".invalid") deterministically rejects the returned promise and
        // surfaces as Rejected -- proving the fetch branch (not anchor navigation) handled it. This
        // deliberately never triggers a same-frame navigation the way an anchor click to a
        // cross-origin http(s) url could (fetch() is a background network call), which keeps this
        // test from ever tearing down the running test page in a headless browser.
        val result = DownloadIntents.dispatch(
            DownloadIntentRequest(
                url = "https://yukitoki-test-unreachable.invalid/report.pdf",
                fileName = "report.pdf",
                headers = headersOf("Authorization", "Bearer test-token"),
            )
        )

        assertIs<DownloadIntentResult.Rejected>(result)
    }

    @Test
    fun `dispatching does not click anything in the app`() = runTest {
        var clicks = 0
        val listener: (Event) -> Unit = { clicks++ }
        document.addEventListener("click", listener)
        try {
            DownloadIntents.dispatch(DownloadIntentRequest("data:text/plain,hello", "hello.txt"))
        } finally {
            document.removeEventListener("click", listener)
        }
        assertEquals(0, clicks)
    }

    @Test
    fun `a cross-origin download outside a click uses a hidden frame`() = runTest {
        // A new tab would be blocked here, and navigating the page would tear down the test run.
        val url = "https://yukitoki-test-unreachable.invalid/report.pdf"
        val result = DownloadIntents.dispatch(DownloadIntentRequest(url, "report.pdf"))

        assertIs<DownloadIntentResult.Dispatched>(result)
        val frames = document.querySelectorAll("iframe[src='$url']")
        assertEquals(1, frames.length)
        (frames.item(0) as HTMLIFrameElement).remove()
    }
}
