package com.ead.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import okio.Path
import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.time.Clock

@JvmInline
value class DownloadId(val value: String) {
    companion object {
        fun new(): DownloadId {
            val now = Clock.System.now().toEpochMilliseconds()
            val random = Random.nextLong().toULong().toString(16)
            return DownloadId("dl-$now-$random")
        }
    }
}

data class DownloadRequest(
    val url: String,
    val destinationPath: Path? = null,
    val fileName: String? = null,
    val method: HttpMethod = HttpMethod.Get,
    val headers: Headers = Headers.Empty,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.REPLACE,
    val retryPolicy: RetryPolicy = RetryPolicy.default(),
    val expectedSizeBytes: Long? = null,
    val progressGranularity: ProgressGranularity = ProgressGranularity(),
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val maxSegments: Int = 1,
    val expectedChecksum: ChecksumSpec? = null,
    val constraints: DownloadConstraints = DownloadConstraints.default(),
) {
    init {
        require(url.isNotBlank()) { "url must not be blank" }
        require(maxSegments >= 1) { "maxSegments must be >= 1" }
        require(expectedSizeBytes == null || expectedSizeBytes >= 0L) { "expectedSizeBytes must be >= 0" }
    }
}

data class ChecksumSpec(
    val algorithm: ChecksumAlgorithm,
    val expectedHex: String,
)

enum class ChecksumAlgorithm {
    MD5,
    SHA_256,
}

data class DownloadConstraints(
    val requireWifi: Boolean = false,
    val requireCharging: Boolean = false,
) {
    companion object {
        fun default(): DownloadConstraints = DownloadConstraints()
    }
}

enum class DownloadPriority {
    HIGH,
    NORMAL,
    LOW,
}

enum class OverwritePolicy {
    FAIL_IF_EXISTS,
    REPLACE,
    RENAME_UNIQUE,
}

data class RetryPolicy(
    val maxAttempts: Int,
    val initialDelayMs: Long,
    val maxDelayMs: Long,
    val multiplier: Double,
    val jitterRatio: Double,
    val retryOnHttp5xx: Boolean = true,
    val retryOnHttp408: Boolean = true,
    val retryOnHttp429: Boolean = true,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(initialDelayMs >= 0) { "initialDelayMs must be >= 0" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in [0,1]" }
    }

    companion object {
        fun default(): RetryPolicy = RetryPolicy(
            maxAttempts = 3,
            initialDelayMs = 500,
            maxDelayMs = 8_000,
            multiplier = 2.0,
            jitterRatio = 0.2,
        )
    }
}

data class ProgressGranularity(
    val minBytesDelta: Long = 64 * 1024,
    val minMillisDelta: Long = 150,
) {
    init {
        require(minBytesDelta >= 0L) { "minBytesDelta must be >= 0" }
        require(minMillisDelta >= 0L) { "minMillisDelta must be >= 0" }
    }
}

data class DownloadManagerConfig(
    val maxConcurrentDownloads: Int = 2,
    val bufferSizeBytes: Int = 64 * 1024,
    val deletePartialOnFailure: Boolean = true,
    val deletePartialOnCancel: Boolean = true,
    /**
     * Per-attempt idle timeout: if no bytes arrive on the stream for this many
     * milliseconds the attempt fails with [DownloadFailure.Timeout] (and may retry).
     * Set to 0 to disable. This guards against stalled sockets independently of the
     * caller's [io.ktor.client.HttpClient] timeout configuration.
     */
    val idleTimeoutMs: Long = 30_000,
) {
    init {
        require(maxConcurrentDownloads >= 1) { "maxConcurrentDownloads must be >= 1" }
        require(bufferSizeBytes >= 4 * 1024) { "bufferSizeBytes must be >= 4KB" }
        require(idleTimeoutMs >= 0) { "idleTimeoutMs must be >= 0 (0 disables)" }
    }
}

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long?,
    val etaMillis: Long?,
) {
    val fraction: Double?
        get() = totalBytes?.takeIf { it > 0 }?.let { bytesDownloaded.toDouble() / it.toDouble() }
}

sealed interface DownloadState {
    data object Queued : DownloadState
    data object Starting : DownloadState
    data class Running(val progress: DownloadProgress) : DownloadState
    data object Pausing : DownloadState
    data object Paused : DownloadState
    data object Cancelling : DownloadState
    data object Cancelled : DownloadState
    data class Succeeded(val result: DownloadSuccess) : DownloadState
    data class Failed(val failure: DownloadFailure) : DownloadState
}

data class DownloadSuccess(
    val finalPath: Path,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
)

sealed interface DownloadFailure {
    data class HttpStatus(val code: Int, val message: String? = null) : DownloadFailure
    data class Network(val message: String) : DownloadFailure
    data class Timeout(val message: String) : DownloadFailure
    data class PermissionDenied(val path: Path? = null, val message: String? = null) : DownloadFailure
    data class NoSpace(val path: Path? = null, val message: String? = null) : DownloadFailure
    data class AlreadyExists(val path: Path? = null, val message: String? = null) : DownloadFailure
    data class FileSystem(val message: String) : DownloadFailure
    data class IntegrityMismatch(val expectedSize: Long, val actualSize: Long) : DownloadFailure
    data class ChecksumMismatch(val expectedHex: String, val actualHex: String) : DownloadFailure
    data class ServerChanged(val message: String) : DownloadFailure
    data object CancelledByUser : DownloadFailure
    data object PausedByUser : DownloadFailure
    data class Unknown(val message: String) : DownloadFailure
}

data class DownloadSnapshot(
    val id: DownloadId,
    val request: DownloadRequest,
    val state: DownloadState,
    val attempts: Int,
    val queuePosition: Int?,
)

sealed interface DownloadResult {
    data class Success(val success: DownloadSuccess) : DownloadResult
    data class Failure(val failure: DownloadFailure) : DownloadResult
}

data class DownloadManagerState(
    val snapshots: Map<DownloadId, DownloadSnapshot>,
    val pending: List<DownloadId>,
    val active: Set<DownloadId>,
    val maxConcurrentDownloads: Int,
)

sealed interface DownloadCommand {
    data class Enqueue(val request: DownloadRequest, val id: DownloadId = DownloadId.new()) : DownloadCommand
    data class Pause(val id: DownloadId) : DownloadCommand
    data class Resume(val id: DownloadId) : DownloadCommand
    data class Cancel(val id: DownloadId) : DownloadCommand
    data object PauseAll : DownloadCommand
    data object ResumeAll : DownloadCommand
    data object CancelAll : DownloadCommand
    data class Retry(val id: DownloadId) : DownloadCommand
    data object RetryAllFailed : DownloadCommand
    data class Move(val id: DownloadId, val newPosition: Int) : DownloadCommand
    data class SetConcurrency(val maxConcurrentDownloads: Int) : DownloadCommand
}

sealed interface CommandAck {
    data class Accepted(val id: DownloadId? = null) : CommandAck
    data class Rejected(val reason: String) : CommandAck
}
