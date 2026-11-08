package io.github.darkryh.yukitoki.manager

import io.ktor.http.Headers
import okio.Path
import kotlin.time.Duration.Companion.milliseconds

/** Shorthand for tests that only care about an exact destination path. */
internal fun DownloadRequest(
    url: String,
    destinationPath: Path,
    headers: Headers = Headers.Empty,
    overwritePolicy: OverwritePolicy = OverwritePolicy.DEFAULT,
    retryPolicy: RetryPolicy = RetryPolicy.Default,
    expectedBytes: Long? = null,
    progressGranularity: ProgressGranularity = ProgressGranularity(),
    priority: DownloadPriority = DownloadPriority.NORMAL,
    segmentation: DownloadSegmentation = DownloadSegmentation.Auto,
    expectedChecksum: ChecksumSpec? = null,
): DownloadRequest {
    return DownloadRequest(
        url = url,
        destination = DownloadDestination.File(destinationPath),
        headers = headers,
        overwritePolicy = overwritePolicy,
        retryPolicy = retryPolicy,
        expectedBytes = expectedBytes,
        progressGranularity = progressGranularity,
        priority = priority,
        segmentation = segmentation,
        expectedChecksum = expectedChecksum,
    )
}

internal fun RetryPolicy(
    maxAttempts: Int,
    initialDelayMs: Long,
    maxDelayMs: Long,
    multiplier: Double,
    jitterRatio: Double,
    retryOnHttp5xx: Boolean = true,
    retryOnHttp408: Boolean = true,
    retryOnHttp429: Boolean = true,
): RetryPolicy = RetryPolicy(
    maxAttempts = maxAttempts,
    initialDelay = initialDelayMs.milliseconds,
    maxDelay = maxDelayMs.milliseconds,
    multiplier = multiplier,
    jitterRatio = jitterRatio,
    retryOnHttp5xx = retryOnHttp5xx,
    retryOnHttp408 = retryOnHttp408,
    retryOnHttp429 = retryOnHttp429,
)
