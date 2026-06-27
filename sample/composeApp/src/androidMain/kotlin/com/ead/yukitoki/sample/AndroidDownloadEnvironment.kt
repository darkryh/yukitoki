package com.ead.yukitoki.sample

import android.content.Context
import android.os.Environment
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Android wiring: OkHttp engine + the app's external Downloads directory. */
class AndroidDownloadEnvironment(context: Context) : DownloadEnvironment {
    override val httpClient: HttpClient = HttpClient(OkHttp)
    override val fileSystem: FileSystem = FileSystem.SYSTEM
    override val downloadsDir: Path = run {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        dir.absolutePath.toPath()
    }

    override fun close() {
        httpClient.close()
    }
}
