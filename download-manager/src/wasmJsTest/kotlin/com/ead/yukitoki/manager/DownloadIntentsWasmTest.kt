package com.ead.yukitoki.manager

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloadIntentsWasmTest {
    @Test
    fun `wasm supports simple browser download intent`() = runTest {
        val result = DownloadIntents.dispatch(
            DownloadRequest(
                url = "data:text/plain,hello",
                fileName = "hello.txt",
            )
        )

        assertTrue(DownloadIntents.isSupported)
        assertIs<DownloadResult.Success>(result)
        assertEquals("hello.txt", result.success.finalPath.name)
        assertEquals(0L, result.success.bytesDownloaded)
    }

    @Test
    fun `wasm rejects intent features the browser cannot verify`() = runTest {
        val customHeader = DownloadIntents.dispatch(
            DownloadRequest(
                url = "https://example.com/file.bin",
                destinationPath = "file.bin".toPath(),
                headers = headersOf(HttpHeaders.Authorization to listOf("Bearer token")),
            )
        )
        assertIs<DownloadResult.Failure>(customHeader)

        val post = DownloadIntents.dispatch(
            DownloadRequest(
                url = "https://example.com/file.bin",
                method = HttpMethod.Post,
            )
        )
        assertIs<DownloadResult.Failure>(post)

        val checksum = DownloadIntents.dispatch(
            DownloadRequest(
                url = "https://example.com/file.bin",
                expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, "0".repeat(64)),
            )
        )
        assertIs<DownloadResult.Failure>(checksum)
    }
}
