package io.github.darkryh.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.Url
import okio.Path
import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@JvmInline
public value class DownloadId(public val value: String) {
    public companion object {
        public fun new(): DownloadId {
            val now = Clock.System.now().toEpochMilliseconds()
            val random = Random.nextLong().toULong().toString(16)
            return DownloadId("dl-$now-$random")
        }
    }
}

public data class DownloadRequest(
    public val url: String,
    public val destination: DownloadDestination = DownloadDestination.Default,
    public val headers: Headers = Headers.Empty,
    public val overwritePolicy: OverwritePolicy = OverwritePolicy.DEFAULT,
    public val retryPolicy: RetryPolicy = RetryPolicy.Default,
    public val expectedSizeBytes: Long? = null,
    public val progressGranularity: ProgressGranularity = ProgressGranularity(),
    public val priority: DownloadPriority = DownloadPriority.NORMAL,
    public val segmentation: DownloadSegmentation = DownloadSegmentation.Auto,
    public val expectedChecksum: ChecksumSpec? = null,
) {
    init {
        require(url.isNotBlank()) { "url must not be blank" }
        validateDownloadUrl(url)
        (destination as? DownloadDestination.Directory)?.fileName?.let(::validateDownloadFileName)
        require(expectedSizeBytes == null || expectedSizeBytes >= 0L) { "expectedSizeBytes must be >= 0" }
    }
}

public sealed interface DownloadDestination {
    /** Use [DownloadLocations.downloads] and infer a safe file name from the URL. */
    public data object Default : DownloadDestination

    /** Save in [path], optionally overriding the URL-derived file name. */
    public data class Directory(
        val path: Path,
        val fileName: String? = null,
    ) : DownloadDestination {
        init {
            fileName?.let(::validateDownloadFileName)
        }
    }

    /** Save at this exact file path. */
    public data class File(val path: Path) : DownloadDestination
}

public sealed interface DownloadSegmentation {
    /**
     * Let the manager decide whether a file should use HTTP byte-range workers.
     * Small files stay single-stream; larger files use bounded parallel ranges only
     * when the server advertises safe range support.
     */
    public data object Auto : DownloadSegmentation

    /** Force the simple single-stream path. This is the safest option for unusual servers. */
    public data object Single : DownloadSegmentation

    /** Cap per-file range workers. The manager may still fall back to single-stream. */
    public data class MaxSegments(val segments: Int) : DownloadSegmentation {
        init {
            require(segments >= 1) { "segments must be >= 1" }
        }
    }
}

public data class ChecksumSpec(
    public val algorithm: ChecksumAlgorithm,
    public val expectedHex: String,
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

public enum class ChecksumAlgorithm {
    MD5,
    SHA_256,
}

public enum class DownloadPriority {
    HIGH,
    NORMAL,
    LOW,
}

public enum class OverwritePolicy {
    /** Rename for default/directory destinations; fail on collision for an exact file. */
    DEFAULT,
    FAIL_IF_EXISTS,
    REPLACE,
    RENAME_UNIQUE,
}

public data class RetryPolicy(
    public val maxAttempts: Int,
    public val initialDelay: Duration,
    public val maxDelay: Duration,
    public val multiplier: Double,
    public val jitterRatio: Double,
    public val retryOnHttp5xx: Boolean = true,
    public val retryOnHttp408: Boolean = true,
    public val retryOnHttp429: Boolean = true,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(!initialDelay.isNegative()) { "initialDelay must be >= 0" }
        require(maxDelay >= initialDelay) { "maxDelay must be >= initialDelay" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in [0,1]" }
    }

    public companion object {
        public val Default: RetryPolicy = RetryPolicy(
            maxAttempts = 3,
            initialDelay = 500.milliseconds,
            maxDelay = 8.seconds,
            multiplier = 2.0,
            jitterRatio = 0.2,
        )

        public val None: RetryPolicy = RetryPolicy(
            maxAttempts = 1,
            initialDelay = Duration.ZERO,
            maxDelay = Duration.ZERO,
            multiplier = 1.0,
            jitterRatio = 0.0,
            retryOnHttp5xx = false,
            retryOnHttp408 = false,
            retryOnHttp429 = false,
        )
    }
}

public data class ProgressGranularity(
    public val minBytesDelta: Long = 64 * 1024,
    public val minInterval: Duration = 150.milliseconds,
) {
    init {
        require(minBytesDelta >= 0L) { "minBytesDelta must be >= 0" }
        require(!minInterval.isNegative()) { "minInterval must be >= 0" }
    }
}

public data class DownloadManagerConfig(
    public val concurrency: DownloadConcurrency = DownloadConcurrency.Default,
    public val bufferSizeBytes: Int = 64 * 1024,
    public val deletePartialOnFailure: Boolean = true,
    public val deletePartialOnCancel: Boolean = true,
    /**
     * Per-attempt idle timeout: if no bytes arrive during this duration, the attempt
     * fails with [DownloadFailure.Timeout] (and may retry). Set to [Duration.ZERO] to
     * disable it. This guards against stalled sockets independently of the
     * caller's [io.ktor.client.HttpClient] timeout configuration.
     */
    public val idleTimeout: Duration = 30.seconds,
    /** Maximum finished entries retained in [DownloadManager.state]. Zero retains none. */
    public val maxRetainedFinished: Int = 100,
) {
    init {
        require(bufferSizeBytes >= 4 * 1024) { "bufferSizeBytes must be >= 4KB" }
        require(!idleTimeout.isNegative()) { "idleTimeout must be >= 0 (zero disables)" }
        require(maxRetainedFinished >= 0) { "maxRetainedFinished must be >= 0" }
    }
}

public sealed interface DownloadConcurrency {
    public data object Default : DownloadConcurrency
    public data class Fixed(val downloads: Int) : DownloadConcurrency {
        init {
            require(downloads >= 1) { "downloads must be >= 1" }
        }
    }
}

public data class DownloadProgress(
    public val bytesDownloaded: Long,
    public val totalBytes: Long?,
    public val speedBytesPerSecond: Long?,
    public val eta: Duration?,
) {
    public val fraction: Double?
        get() = totalBytes?.takeIf { it > 0 }?.let { bytesDownloaded.toDouble() / it.toDouble() }
}

public sealed interface DownloadState {
    public data object Queued : DownloadState
    public data object Starting : DownloadState
    public data class Running(val progress: DownloadProgress) : DownloadState
    public data object Pausing : DownloadState
    public data object Paused : DownloadState
    public data object Cancelling : DownloadState
    public data object Cancelled : DownloadState
    public data class Succeeded(val result: DownloadSuccess) : DownloadState
    public data class Failed(val failure: DownloadFailure) : DownloadState
}

public data class DownloadSuccess(
    public val finalPath: Path,
    public val bytesDownloaded: Long,
    public val totalBytes: Long?,
)

public sealed interface DownloadFailure {
    public data class HttpStatus(
        val code: Int,
        val message: String? = null,
        val retryAfter: Duration? = null,
    ) : DownloadFailure
    public data class Network(val message: String) : DownloadFailure
    public data class Timeout(val message: String) : DownloadFailure
    public data class PermissionDenied(val path: Path? = null, val message: String? = null) : DownloadFailure
    public data class NoSpace(val path: Path? = null, val message: String? = null) : DownloadFailure
    public data class AlreadyExists(val path: Path? = null, val message: String? = null) : DownloadFailure
    public data class FileSystem(val message: String) : DownloadFailure
    public data class IntegrityMismatch(val expectedSize: Long, val actualSize: Long) : DownloadFailure
    public data class ChecksumMismatch(val expectedHex: String, val actualHex: String) : DownloadFailure
    public data class ServerChanged(val message: String) : DownloadFailure
    public data class Unsupported(val message: String) : DownloadFailure
    public data class ManagerClosed(val message: String = "Download manager is closed") : DownloadFailure
    public data class Unexpected(val message: String) : DownloadFailure
}

public data class DownloadSnapshot(
    public val id: DownloadId,
    public val request: DownloadRequest,
    public val state: DownloadState,
    public val attempts: Int,
    public val queuePosition: Int?,
    /** The concrete reserved output path, available after enqueue succeeds. */
    public val resolvedDestination: Path?,
)

public sealed interface DownloadResult {
    public data class Success(val success: DownloadSuccess) : DownloadResult
    public data object Cancelled : DownloadResult
    public data class Failure(val failure: DownloadFailure) : DownloadResult
}

public data class DownloadManagerState(
    public val snapshots: Map<DownloadId, DownloadSnapshot>,
    public val pending: List<DownloadId>,
    public val active: Set<DownloadId>,
    public val maxConcurrentDownloads: Int,
)

internal sealed interface DownloadCommand {
    data class Enqueue(
        val request: DownloadRequest,
        val id: DownloadId = DownloadId.new(),
        /** Completed with the terminal result; registered atomically with the enqueue. */
        val outcome: kotlinx.coroutines.CompletableDeferred<DownloadResult?>? = null,
    ) : DownloadCommand
    data class Pause(val id: DownloadId) : DownloadCommand
    data class Resume(val id: DownloadId) : DownloadCommand
    data class Cancel(val id: DownloadId) : DownloadCommand
    data object PauseAll : DownloadCommand
    data object ResumeAll : DownloadCommand
    data object CancelAll : DownloadCommand
    data class Retry(val id: DownloadId) : DownloadCommand
    data object RetryAllFailed : DownloadCommand
    data class MoveWithinPriority(val id: DownloadId, val newPosition: Int) : DownloadCommand
    data class Remove(val id: DownloadId, val deleteFile: Boolean) : DownloadCommand
    data object ClearFinished : DownloadCommand
    data class SetConcurrency(val concurrency: DownloadConcurrency) : DownloadCommand
}

public sealed interface CommandResult {
    public data class Accepted(val id: DownloadId? = null) : CommandResult
    public data class Rejected(
        val reason: String,
        val failure: DownloadFailure? = null,
    ) : CommandResult
}

public class DownloadRejectedException(
    public val failure: DownloadFailure,
) : IllegalStateException(failure.toString())

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
private val supportedDownloadRequestSchemes = setOf("http", "https")
