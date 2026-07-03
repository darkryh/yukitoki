package io.github.darkryh.yukitoki.manager

internal actual object PlatformDownloadIntentDispatcher {
    actual val isSupported: Boolean = false

    actual suspend fun dispatch(request: DownloadRequest): DownloadResult =
        DownloadResult.Failure(DownloadFailure.Unknown("Download intents are not supported on this platform."))
}
