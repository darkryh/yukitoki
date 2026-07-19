package io.github.darkryh.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloadIntentsWasmTest {
    @Test
    fun `wasm dispatches without claiming transfer completion`() = runTest {
        val result = DownloadIntents.dispatch(
            DownloadIntentRequest(
                url = "data:text/plain,hello",
                suggestedFileName = "hello.txt",
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
                suggestedFileName = "hello.txt",
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
                suggestedFileName = "report.pdf",
                headers = headersOf("Authorization", "Bearer test-token"),
            )
        )

        assertIs<DownloadIntentResult.Rejected>(result)
    }
}
