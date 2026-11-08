package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The common suite uses an in-memory filesystem; this writes through the real iOS one. */
class DownloadRealFileSystemIosTest {
    private val payload = Random(seed = 1).nextBytes(1024 * 1024 + 7)
    private val fs = PlatformDownloadFileSystem.default

    private fun client() = HttpClient(
        MockEngine { request ->
            val range = request.headers[HttpHeaders.Range]
            when {
                request.method == HttpMethod.Head -> respond(
                    "",
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        HttpHeaders.ContentLength to listOf(payload.size.toString()),
                    ),
                )
                range != null -> {
                    val (start, end) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                    respond(
                        payload.copyOfRange(start, end + 1),
                        HttpStatusCode.PartialContent,
                        headersOf(
                            HttpHeaders.ContentRange to listOf("bytes $start-$end/${payload.size}"),
                            HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
                        ),
                    )
                }
                else -> respond(
                    payload,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentLength to listOf(payload.size.toString())),
                )
            }
        }
    )

    private fun downloadAndVerify(segmentation: DownloadSegmentation) = runTest {
        val directory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "yukitoki-ios-${Random.nextLong()}"
        fs.createDirectories(directory)
        val scope = CoroutineScope(SupervisorJob())
        val manager = DownloadManager.create(client(), scope)
        try {
            val target = "$directory/file.bin".toPath()
            val result = manager.download(
                DownloadRequest(
                    url = "https://example.test/file.bin",
                    destination = DownloadDestination.File(target),
                    segmentation = segmentation,
                )
            )

            assertEquals(payload.size.toLong(), assertIs<DownloadResult.Success>(result).file.sizeBytes)
            assertContentEquals(payload, fs.read(target) { readByteArray() })
            assertEquals(listOf("file.bin"), fs.list(directory).map { it.name })
        } finally {
            manager.shutdown()
            scope.cancel()
            fs.deleteRecursively(directory)
        }
    }

    @Test
    fun `single stream writes the exact bytes to disk`() = downloadAndVerify(DownloadSegmentation.Single)

    @Test
    fun `segments write the exact bytes to disk at their offsets`() = downloadAndVerify(DownloadSegmentation.MaxSegments(4))
}
