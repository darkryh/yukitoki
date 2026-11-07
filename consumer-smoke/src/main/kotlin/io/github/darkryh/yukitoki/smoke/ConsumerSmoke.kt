package io.github.darkryh.yukitoki.smoke

import com.sun.net.httpserver.HttpServer
import io.github.darkryh.yukitoki.manager.ChecksumAlgorithm
import io.github.darkryh.yukitoki.manager.ChecksumSpec
import io.github.darkryh.yukitoki.manager.DownloadDestination
import io.github.darkryh.yukitoki.manager.DownloadManager
import io.github.darkryh.yukitoki.manager.DownloadRequest
import io.github.darkryh.yukitoki.manager.DownloadResult
import io.github.darkryh.yukitoki.manager.DownloadSegmentation
import io.github.darkryh.yukitoki.manager.RetryPolicy
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Consumer-side guard for issue #15. Fails (non-zero exit) when:
 *  - Yukitoki leaks the CIO engine (15 s default request timeout) onto a consumer's
 *    runtime classpath,
 *  - a bare `HttpClient()` in the consumer resolves to anything but the bundled Java engine,
 *  - or `DownloadManager.create()` cannot complete a paced download.
 */
fun main() {
    val failures = mutableListOf<String>()

    if (runCatching { Class.forName("io.ktor.client.engine.cio.CIO") }.isSuccess) {
        failures += "ktor-client-cio is on the consumer runtime classpath"
    }

    val bareEngine = HttpClient().use { it.engine::class.qualifiedName }
    if (bareEngine != "io.ktor.client.engine.java.JavaHttpEngine") {
        failures += "bare HttpClient() resolved to $bareEngine"
    }

    val payload = ByteArray(64 * 1024) { (it % 253).toByte() }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/slow") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { out ->
                payload.asList().chunked(payload.size / 8).forEach { chunk ->
                    out.write(chunk.toByteArray())
                    out.flush()
                    Thread.sleep(150)
                }
            }
        }
        executor = Executors.newCachedThreadPool()
        start()
    }

    try {
        val dir = Files.createTempDirectory("yukitoki-consumer-smoke")
        val target = dir.resolve("paced.bin")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val result = runBlocking {
            val manager = DownloadManager.create()
            try {
                withTimeout(60.seconds) {
                    manager.download(
                        DownloadRequest(
                            url = "http://127.0.0.1:${server.address.port}/slow",
                            destination = DownloadDestination.File(target.toOkioPath()),
                            retryPolicy = RetryPolicy.None,
                            segmentation = DownloadSegmentation.Single,
                            expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, sha256),
                        )
                    )
                }
            } finally {
                manager.shutdown()
            }
        }
        if (result !is DownloadResult.Success) failures += "DownloadManager.create() download failed: $result"
        dir.toFile().deleteRecursively()
    } finally {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    val classpathHead = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        .take(3).joinToString { java.io.File(it).name }
    if (failures.isEmpty()) {
        println("consumer-smoke OK (engine=$bareEngine, classpath starts with: $classpathHead)")
    } else {
        System.err.println("consumer-smoke FAILED (classpath starts with: $classpathHead)")
        failures.forEach { System.err.println("  - $it") }
        exitProcess(1)
    }
}
