package io.github.darkryh.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.Url
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
    val segmentation: DownloadSegmentation = DownloadSegmentation.Auto,
    val expectedChecksum: ChecksumSpec? = null,
) {
    init {
        require(url.isNotBlank()) { "url must not be blank" }
        validateDownloadUrl(url)
        fileName?.let(::validateDownloadFileName)
        require(expectedSizeBytes == null || expectedSizeBytes >= 0L) { "expectedSizeBytes must be >= 0" }
    }
}

sealed interface DownloadSegmentation {
    /**
     * Let the manager decide whether a file should use HTTP byte-range workers.
     * Small files stay single-stream; larger files use bounded parallel ranges only
     * when the server advertises safe range support.
     */
    data object Auto : DownloadSegmentation

    /** Force the simple single-stream path. This is the safest option for unusual servers. */
    data object SingleStream : DownloadSegmentation

    /** Cap per-file range workers. The manager may still fall back to single-stream. */
    data class Fixed(val segments: Int) : DownloadSegmentation {
        init {
            require(segments >= 1) { "segments must be >= 1" }
        }
    }
}

data class ChecksumSpec(
    val algorithm: ChecksumAlgorithm,
    val expectedHex: String,
) {
    init {
        val expectedLength = when (algorithm) {
            ChecksumAlgorithm.MD5 -> 32
            ChecksumAlgorithm.SHA_256 -> 64
        }
        require(expectedHex.length == expectedLength) {
            "${algorithm.name} checksum must be $expectedLength hex characters"
        }
        require(expectedHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "expectedHex must contain only hexadecimal characters"
        }
    }
}

enum class ChecksumAlgorithm {
    MD5,
    SHA_256,
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
    val concurrency: DownloadConcurrency = DownloadConcurrency.Auto,
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
        require(bufferSizeBytes >= 4 * 1024) { "bufferSizeBytes must be >= 4KB" }
        require(idleTimeoutMs >= 0) { "idleTimeoutMs must be >= 0 (0 disables)" }
    }
}

sealed interface DownloadConcurrency {
    data object Auto : DownloadConcurrency
    data class Fixed(val downloads: Int) : DownloadConcurrency {
        init {
            require(downloads >= 1) { "downloads must be >= 1" }
        }
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
    data class HttpStatus(
        val code: Int,
        val message: String? = null,
        val retryAfterMillis: Long? = null,
    ) : DownloadFailure
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

internal sealed interface DownloadCommand {
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
    data class SetConcurrency(val concurrency: DownloadConcurrency) : DownloadCommand
}

sealed interface CommandAck {
    data class Accepted(val id: DownloadId? = null) : CommandAck
    data class Rejected(val reason: String) : CommandAck
}

private fun validateDownloadUrl(url: String) {
    require(uriSchemeRegex.containsMatchIn(url)) { "url must be absolute and include a scheme" }
    val parsed = try {
        Url(url)
    } catch (t: Throwable) {
        throw IllegalArgumentException("url must be an absolute URL", t)
    }
    val protocol = parsed.protocol.name
    require(protocol in supportedDownloadRequestSchemes) {
        "url must use one of: ${supportedDownloadRequestSchemes.joinToString()}"
    }
    if (protocol == "http" || protocol == "https") {
        require(parsed.host.isNotBlank()) { "url must include a host" }
    }
}

private fun validateDownloadFileName(fileName: String) {
    val trimmed = fileName.trim()
    require(trimmed.isNotEmpty()) { "fileName must not be blank" }
    require(trimmed != "." && trimmed != "..") { "fileName must be a file name, not a directory reference" }
    require(fileName.none { it == '/' || it == '\\' }) { "fileName must not contain path separators" }
    require(fileName.none { it.code < 32 || it in windowsReservedFileNameChars }) {
        "fileName contains characters that are not safe across supported platforms"
    }
}

private val windowsReservedFileNameChars = setOf('<', '>', ':', '"', '|', '?', '*')
private val uriSchemeRegex = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val supportedDownloadRequestSchemes = setOf("http", "https", "data", "blob")
