package com.ead.yukitoki.sample

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/** iOS wiring: the Darwin (NSURLSession) engine + the app's Documents directory. */
class IosDownloadEnvironment : DownloadEnvironment {
    override val httpClient: HttpClient = HttpClient(Darwin)
    override val fileSystem: FileSystem = FileSystem.SYSTEM
    override val downloadsDir: Path = run {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        (documents ?: NSTemporaryDirectory()).toPath()
    }

    override fun close() {
        httpClient.close()
    }
}
