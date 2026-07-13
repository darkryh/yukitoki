package io.github.darkryh.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import okio.Path
import kotlin.time.Duration.Companion.milliseconds

/** Keeps existing behavioral tests compact while the public pre-1.0 API is intentionally broken. */
internal fun DownloadRequest(
    url: String,
    destinationPath: Path? = null,
    method: HttpMethod = HttpMethod.Get,
    headers: Headers = Headers.Empty,
    overwritePolicy: OverwritePolicy = OverwritePolicy.DEFAULT,
    retryPolicy: RetryPolicy = RetryPolicy.Default,
    expectedSizeBytes: Long? = null,
    progressGranularity: ProgressGranularity = ProgressGranularity(),
    priority: DownloadPriority = DownloadPriority.NORMAL,
    segmentation: DownloadSegmentation = DownloadSegmentation.Auto,
    expectedChecksum: ChecksumSpec? = null,
): DownloadRequest {
    require(method == HttpMethod.Get) { "Managed downloads support GET only" }
    return DownloadRequest(
        url = url,
        destination = destinationPath?.let(DownloadDestination::File) ?: DownloadDestination.Default,
        headers = headers,
        overwritePolicy = overwritePolicy,
        retryPolicy = retryPolicy,
        expectedSizeBytes = expectedSizeBytes,
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
