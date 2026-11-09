package io.github.darkryh.yukitoki.integration

import io.github.darkryh.yukitoki.manager.DownloadManager
import io.github.darkryh.yukitoki.manager.DownloadManagerConfig
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import okio.FileSystem
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal actual val checkFileSystem: FileSystem = FileSystem.SYSTEM
internal actual val platformName: String = "android"
internal actual val engineReportsHttpVersion: Boolean = true

// The emulator has its own disk.
internal actual val canReachHostVolume: Boolean = false

/** Only for the checks' own loopback server, whose certificate the emulator doesn't know. */
private object TrustEverything : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal actual fun newManager(config: DownloadManagerConfig): DownloadManager {
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustEverything), null) }
    return DownloadManager.create(config) {
        @Suppress("UNCHECKED_CAST")
        (this as HttpClientConfig<OkHttpConfig>).engine {
            config {
                sslSocketFactory(tls.socketFactory, TrustEverything)
                hostnameVerifier { _, _ -> true }
            }
        }
    }
}

internal actual fun usedHeapBytes(): Long? =
    Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
