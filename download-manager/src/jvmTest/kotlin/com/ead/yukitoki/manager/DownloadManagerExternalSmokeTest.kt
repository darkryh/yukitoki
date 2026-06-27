package com.ead.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Optional public-internet smoke tests. These validate that the download manager still
 * works against real public providers, but they are intentionally skipped by default so
 * local builds and CI are not coupled to external availability, provider rate limits, or
 * large bandwidth usage.
 *
 * Enable with:
 *   YUKITOKI_EXTERNAL_E2E=true ./gradlew :download-manager:jvmTest
 */
class DownloadManagerExternalSmokeTest {
    private data class PublicResource(
        val name: String,
        val url: String,
        val provider: String,
        val sizeBytes: Long?,
        val supportsRange: Boolean = true,
        val fullDownload: Boolean = false,
    )

    private val fs = FileSystem.SYSTEM

    private val publicResources = listOf(
        PublicResource(
            name = "ovh-1mb",
            provider = "OVHcloud",
            url = "https://proof.ovh.net/files/1Mb.dat",
            sizeBytes = 1_048_576,
            fullDownload = true,
        ),
        PublicResource(
            name = "ovh-10mb",
            provider = "OVHcloud",
            url = "https://proof.ovh.net/files/10Mb.dat",
            sizeBytes = 10_485_760,
        ),
        PublicResource(
            name = "ovh-100mb",
            provider = "OVHcloud",
            url = "https://proof.ovh.net/files/100Mb.dat",
            sizeBytes = 104_857_600,
        ),
        PublicResource(
            name = "hetzner-100mb",
            provider = "Hetzner",
            url = "https://fsn1-speed.hetzner.com/100MB.bin",
            sizeBytes = 100_000_000,
        ),
        PublicResource(
            name = "hetzner-1gb",
            provider = "Hetzner",
            url = "https://fsn1-speed.hetzner.com/1GB.bin",
            sizeBytes = 1_000_000_000,
        ),
        PublicResource(
            name = "hetzner-10gb",
            provider = "Hetzner",
            url = "https://fsn1-speed.hetzner.com/10GB.bin",
            sizeBytes = 10_000_000_000,
        ),
        PublicResource(
            name = "thinkbroadband-5mb",
            provider = "ThinkBroadband",
            url = "http://ipv4.download.thinkbroadband.com/5MB.zip",
            sizeBytes = 5_242_880,
            fullDownload = true,
        ),
        PublicResource(
            name = "thinkbroadband-20mb",
            provider = "ThinkBroadband",
            url = "http://ipv4.download.thinkbroadband.com/20MB.zip",
            sizeBytes = 20_971_520,
        ),
        PublicResource(
            name = "tele2-1mb",
            provider = "Tele2",
            url = "http://speedtest.tele2.net/1MB.zip",
            sizeBytes = 1_048_576,
            fullDownload = true,
        ),
        PublicResource(
            name = "tele2-10mb",
            provider = "Tele2",
            url = "http://speedtest.tele2.net/10MB.zip",
            sizeBytes = 10_485_760,
        ),
        PublicResource(
            name = "cachefly-10mb",
            provider = "CacheFly",
            url = "http://cachefly.cachefly.net/10mb.test",
            sizeBytes = 10_485_760,
            fullDownload = true,
        ),
        PublicResource(
            name = "cloudflare-1mb-generated",
            provider = "Cloudflare",
            url = "https://speed.cloudflare.com/__down?bytes=1000000",
            sizeBytes = 1_000_000,
            supportsRange = false,
            fullDownload = true,
        ),
    )

    @Test
    fun `public resource catalog has broad provider and size coverage`() {
        assertTrue(publicResources.size >= 10)
        assertTrue(publicResources.map { it.provider }.distinct().size >= 5)
        assertTrue(publicResources.any { (it.sizeBytes ?: 0L) <= 1_048_576L })
        assertTrue(publicResources.any { (it.sizeBytes ?: 0L) >= 100_000_000L })
        assertTrue(publicResources.any { it.supportsRange })
        assertTrue(publicResources.any { !it.supportsRange })
    }

    @Test
    fun `public resources support expected range behavior`() = runExternal {
        val client = HttpClient(CIO)
        try {
            publicResources.forEach { resource ->
                withTimeout(20_000) {
                    val response = client.get(resource.url) {
                        header(HttpHeaders.Range, "bytes=0-1023")
                        header(HttpHeaders.AcceptEncoding, "identity")
                    }
                    val bytes = readAtMost(response.bodyAsChannel(), 1024)
                    if (resource.supportsRange) {
                        assertEquals(HttpStatusCode.PartialContent, response.status, "${resource.name} should honor Range")
                        assertEquals(1024, bytes.size, "${resource.name} should return exactly the requested range")
                        assertTrue(response.headers[HttpHeaders.ContentRange] != null, "${resource.name} should return Content-Range")
                    } else {
                        assertEquals(HttpStatusCode.OK, response.status, "${resource.name} should be downloadable without Range support")
                        assertTrue(bytes.isNotEmpty(), "${resource.name} should return bytes")
                    }
                }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `download manager downloads public small resources`() = runExternal {
        val tmpDir = "${System.getProperty("java.io.tmpdir")}/yukitoki-external-${System.nanoTime()}".toPath()
        fs.createDirectories(tmpDir)
        val client = HttpClient(CIO)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val manager = DefaultDownloadManager(
            httpClient = client,
            scope = scope,
            fileSystem = fs,
            config = DownloadManagerConfig(concurrency = DownloadConcurrency.Fixed(2)),
        )
        try {
            publicResources.filter { it.fullDownload }.forEach { resource ->
                withTimeout(60_000) {
                    val destination = "$tmpDir/${resource.name}".toPath()
                    val result = manager.download(
                        DownloadRequest(
                            url = resource.url,
                            destinationPath = destination,
                            segmentation = if (resource.supportsRange) DownloadSegmentation.Auto else DownloadSegmentation.SingleStream,
                        )
                    )
                    assertIs<DownloadResult.Success>(result, resource.name)
                    assertTrue(fs.exists(destination), "${resource.name} should write a file")
                    resource.sizeBytes?.let { expected ->
                        assertEquals(expected, result.success.bytesDownloaded, "${resource.name} byte count mismatch")
                    }
                }
            }
        } finally {
            manager.shutdown(cancelActive = true)
            scope.cancel()
            client.close()
            runCatching { fs.deleteRecursively(tmpDir, mustExist = false) }
        }
    }

    private fun runExternal(block: suspend () -> Unit) {
        if (System.getenv("YUKITOKI_EXTERNAL_E2E") != "true") return
        runBlocking { block() }
    }

    private suspend fun readAtMost(channel: io.ktor.utils.io.ByteReadChannel, byteCount: Int): ByteArray {
        val buffer = ByteArray(byteCount)
        val read = channel.readAvailable(buffer, 0, byteCount)
        return if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }
}
