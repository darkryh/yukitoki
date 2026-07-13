package io.github.darkryh.yukitoki.manager

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
}
