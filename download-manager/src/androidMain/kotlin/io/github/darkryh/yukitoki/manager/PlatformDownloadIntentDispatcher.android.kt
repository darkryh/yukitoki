package io.github.darkryh.yukitoki.manager

internal actual object PlatformDownloadIntentDispatcher {
    actual val isSupported: Boolean = false

    actual suspend fun dispatch(request: DownloadIntentRequest): DownloadIntentResult =
        DownloadIntentResult.Rejected("Download intents are not supported on this platform.")
}
