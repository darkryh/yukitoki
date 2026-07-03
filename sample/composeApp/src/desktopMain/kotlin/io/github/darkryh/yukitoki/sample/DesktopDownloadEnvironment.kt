package io.github.darkryh.yukitoki.sample

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Desktop (JVM) wiring: OkHttp engine + a `~/Downloads/yukitoki-sample` directory. */
class DesktopDownloadEnvironment : DownloadEnvironment {
    override val httpClient: HttpClient = HttpClient(OkHttp)
    override val fileSystem: FileSystem = FileSystem.SYSTEM
    override val downloadsDir: Path = run {
        val dir = "${System.getProperty("user.home")}/Downloads/yukitoki-sample".toPath()
        FileSystem.SYSTEM.createDirectories(dir)
        dir
    }

    override fun close() {
        httpClient.close()
    }
}
