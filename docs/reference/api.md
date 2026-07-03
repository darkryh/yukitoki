# API reference

Complete reference for the public API of `io.github.darkryh.yukitoki:download-manager`, version
`1.0.0-beta01`. All types are in the package `io.github.darkryh.yukitoki.manager`.

For task-oriented usage see the [how-to guides](../); for a guided introduction see
[Getting started](../getting-started.md).

## Contents

- [Entry points](#entry-points) — `DownloadManager`, `DefaultDownloadManager`, `DownloadIntents`
- [Requests](#requests) — `DownloadRequest`, `DownloadId`, `DownloadSegmentation`, `ChecksumSpec`,
  `ChecksumAlgorithm`, `DownloadPriority`, `OverwritePolicy`, `RetryPolicy`, `ProgressGranularity`
- [Configuration](#configuration) — `DownloadManagerConfig`, `DownloadConcurrency`
- [State and results](#state-and-results) — `DownloadManagerState`, `DownloadSnapshot`,
  `DownloadState`, `DownloadProgress`, `DownloadSuccess`, `DownloadResult`, `DownloadFailure`,
  `CommandAck`
- [Android](#android) — `AndroidDownloadPathContext`

---

## Entry points

### DownloadManager

The interface every consumer programs against. `interface DownloadManager`.

**Property**

| Member | Type | Description |
|--------|------|-------------|
| `state` | `StateFlow<DownloadManagerState>` | Live snapshot of the whole queue. Never completes. |

**Observation**

| Function | Returns | Description |
|----------|---------|-------------|
| `observe(downloadId: DownloadId)` | `Flow<DownloadSnapshot?>` | Emits state changes for one download. Emits `null` if the id is unknown. |
| `suspend await(downloadId: DownloadId)` | `DownloadResult?` | Suspends until the download terminates. Returns `null` if the manager was shut down or the id is unknown. |

**Starting downloads**

| Function | Returns | Description |
|----------|---------|-------------|
| `suspend enqueue(request: DownloadRequest, id: DownloadId = DownloadId.new())` | `DownloadId` | Adds a download to the queue and returns immediately. |
| `suspend enqueue(url: String, …options, id: DownloadId = DownloadId.new())` | `DownloadId` | Convenience overload that builds the `DownloadRequest` for you. See [`DownloadRequest`](#downloadrequest) for the option list. |
| `suspend download(request: DownloadRequest)` | `DownloadResult` | Enqueues and suspends until the single download finishes. |
| `suspend download(url: String, …options)` | `DownloadResult` | Convenience overload of the above. |

**Commands** — each returns a [`CommandAck`](#commandack).

| Function | Description |
|----------|-------------|
| `suspend pause(downloadId: DownloadId)` | Pause a running download; keeps the `.part` file. |
| `suspend resume(downloadId: DownloadId)` | Resume a paused download via HTTP `Range`. |
| `suspend cancel(downloadId: DownloadId)` | Cancel one download. Partial file follows `deletePartialOnCancel`. |
| `suspend retry(downloadId: DownloadId)` | Re-queue a download that ended in `Failed`. |
| `suspend move(downloadId: DownloadId, newPosition: Int)` | Reorder a pending download (`0` = front). |
| `suspend pauseAll()` | Pause every pausable download. |
| `suspend resumeAll()` | Resume every paused download. |
| `suspend cancelAll()` | Cancel every download. |
| `suspend retryAllFailed()` | Re-queue every failed download. |
| `suspend setConcurrency(concurrency: DownloadConcurrency)` | Change the active-download limit at runtime. |

**Lifecycle**

| Function | Description |
|----------|-------------|
| `suspend shutdown(cancelActive: Boolean = true)` | Graceful teardown. With `cancelActive = false`, in-flight downloads finish and finalize their files before this returns; with `true`, active downloads are cancelled. Outstanding `await` callers are released. |
| `cancel()` | Non-suspending, immediate teardown for lifecycle callbacks that aren't coroutines (e.g. `ViewModel.onCleared()`). Cancels the coordinator and all active downloads at once. Partial files follow the configured cleanup policy. |

> `cancel()` (no argument) tears down the **whole manager**. `cancel(downloadId)` cancels **one**
> download. They are different methods.

After `shutdown` or `cancel()`, commands return `CommandAck.Rejected` and `await` returns `null`.

### DefaultDownloadManager

The filesystem-backed implementation for JVM, Android, and iOS. Streams through Ktor into a `.part`
file and atomically moves the completed file into place.

```kotlin
class DefaultDownloadManager(
    httpClient: HttpClient,
    scope: CoroutineScope,
    fileSystem: FileSystem = PlatformDownloadFileSystem.default,
    config: DownloadManagerConfig = DownloadManagerConfig(),
    ioDispatcher: CoroutineDispatcher = platformIoDispatcher,
) : DownloadManager
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `httpClient` | — | A Ktor `HttpClient` you own and configure (install `HttpTimeout`). Not closed by the manager. |
| `scope` | — | A `CoroutineScope` you own. The manager runs under a child `SupervisorJob`; cancelling the scope tears it down. |
| `fileSystem` | platform default | An okio `FileSystem`. Override for tests (e.g. `FakeFileSystem`). |
| `config` | `DownloadManagerConfig()` | Global tuning — see [`DownloadManagerConfig`](#downloadmanagerconfig). |
| `ioDispatcher` | `Dispatchers.IO` (JVM/Android), `Dispatchers.Default` (iOS) | Where blocking file I/O runs. The coordinator itself runs on `scope`'s dispatcher. |

### DownloadIntents

Browser download intents for wasm, where the browser owns the transfer. `object DownloadIntents`.

| Member | Type | Description |
|--------|------|-------------|
| `isSupported` | `Boolean` | `true` in a browser (wasm) context. |
| `suspend dispatch(request: DownloadRequest)` | `DownloadResult` | Creates a browser download intent; returns once the browser accepts it. Provides no byte progress, final path, checksum validation, or custom headers. |

See [Download on wasm](../how-to/download-on-wasm.md).

---

## Requests

### DownloadRequest

Everything needed to describe one download. `data class DownloadRequest`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `url` | `String` | — | Absolute URL. Required. |
| `destinationPath` | `Path?` | `null` | Where to save. `null` → platform default Downloads directory. |
| `fileName` | `String?` | `null` | Override the file name. Must not contain path separators or characters unsafe across platforms. |
| `method` | `HttpMethod` | `Get` | HTTP method. Automatic segmentation is GET-only. |
| `headers` | `Headers` | `Headers.Empty` | Extra request headers. A caller-supplied `Range` is rejected — resume headers are owned by the manager. |
| `overwritePolicy` | `OverwritePolicy` | `REPLACE` | What to do if the destination exists. |
| `retryPolicy` | `RetryPolicy` | `RetryPolicy.default()` | Automatic retry behavior. |
| `expectedSizeBytes` | `Long?` | `null` | If set, the finished file must be exactly this size. Must be ≥ 0. |
| `progressGranularity` | `ProgressGranularity` | `ProgressGranularity()` | How often progress is emitted. |
| `priority` | `DownloadPriority` | `NORMAL` | Queue priority. |
| `segmentation` | `DownloadSegmentation` | `Auto` | Per-file parallel range strategy. |
| `expectedChecksum` | `ChecksumSpec?` | `null` | Optional content hash to verify. |

**Validation (at construction).** The constructor throws `IllegalArgumentException` for: a blank URL;
a relative URL or one with an unsupported scheme; an HTTP(S) URL with no host; a negative
`expectedSizeBytes`; or a `fileName` that is blank, is `.`/`..`, or contains path separators or unsafe
characters. Supported schemes are `http`, `https`, `data`, `blob`. `DefaultDownloadManager` accepts
`http`/`https`; `data`/`blob` are for browser intents.

### DownloadId

Opaque identifier for a download. `value class DownloadId(val value: String)`.

| Member | Description |
|--------|-------------|
| `value` | The underlying `String`. |
| `DownloadId.new()` | Companion factory that generates a fresh unique id. |

### DownloadSegmentation

`sealed interface DownloadSegmentation` — per-file parallelism strategy.

| Variant | Description |
|---------|-------------|
| `DownloadSegmentation.Auto` | Manager decides: small files single-stream; larger files use bounded ranges only when the server proves range support. |
| `DownloadSegmentation.SingleStream` | Always one connection. Safest for unusual servers; best pause/resume. |
| `DownloadSegmentation.Fixed(segments: Int)` | Cap range workers at `segments` (≥ 1). A maximum, not a demand; may still fall back to single-stream. |

### ChecksumSpec

`data class ChecksumSpec(algorithm: ChecksumAlgorithm, expectedHex: String)`.

Validates on construction: `expectedHex` must be the exact length for the algorithm (MD5 = 32,
SHA-256 = 64 hex chars) and contain only hexadecimal characters; otherwise it throws
`IllegalArgumentException`. Case-insensitive.

### ChecksumAlgorithm

`enum class ChecksumAlgorithm { MD5, SHA_256 }`. Prefer `SHA_256`.

### DownloadPriority

`enum class DownloadPriority { HIGH, NORMAL, LOW }`. Higher-priority pending downloads start first.

### OverwritePolicy

`enum class OverwritePolicy`.

| Value | Behavior when the destination exists |
|-------|--------------------------------------|
| `FAIL_IF_EXISTS` | Fail with `DownloadFailure.AlreadyExists`. |
| `REPLACE` | Overwrite the existing file (default). |
| `RENAME_UNIQUE` | Save under a new, non-colliding name. |

### RetryPolicy

`data class RetryPolicy`. Automatic retry for transient errors.

| Property | Type | Description |
|----------|------|-------------|
| `maxAttempts` | `Int` | Total attempts (≥ 1). |
| `initialDelayMs` | `Long` | First backoff delay (≥ 0). |
| `maxDelayMs` | `Long` | Backoff ceiling (≥ `initialDelayMs`). |
| `multiplier` | `Double` | Backoff growth factor (≥ 1.0). |
| `jitterRatio` | `Double` | Random jitter fraction, `[0, 1]`. |
| `retryOnHttp5xx` | `Boolean` | Retry on HTTP 5xx (default `true`). |
| `retryOnHttp408` | `Boolean` | Retry on HTTP 408 (default `true`). |
| `retryOnHttp429` | `Boolean` | Retry on HTTP 429 (default `true`). |

`RetryPolicy.default()` = 3 attempts, 500 ms initial, 8 s max, ×2.0, 0.2 jitter. Constructor
validates all bounds.

### ProgressGranularity

`data class ProgressGranularity(minBytesDelta: Long = 65536, minMillisDelta: Long = 150)`. Throttles
progress emission: a new `Running` state is emitted only after at least `minBytesDelta` bytes **or**
`minMillisDelta` milliseconds since the last one. Both must be ≥ 0.

---

## Configuration

### DownloadManagerConfig

`data class DownloadManagerConfig`. Global manager behavior, passed to `DefaultDownloadManager`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `concurrency` | `DownloadConcurrency` | `Auto` | Max simultaneously active downloads. |
| `bufferSizeBytes` | `Int` | `65536` (64 KiB) | Streaming buffer size. Must be ≥ 4 KiB. |
| `deletePartialOnFailure` | `Boolean` | `true` | Delete the `.part` file when a download fails. |
| `deletePartialOnCancel` | `Boolean` | `true` | Delete the `.part` file when a download is cancelled. |
| `idleTimeoutMs` | `Long` | `30000` | Per-attempt stall guard: fail with `Timeout` if no bytes arrive for this long. `0` disables. Must be ≥ 0. |

### DownloadConcurrency

`sealed interface DownloadConcurrency`.

| Variant | Description |
|---------|-------------|
| `DownloadConcurrency.Auto` | Manager picks a bounded limit. |
| `DownloadConcurrency.Fixed(downloads: Int)` | Exactly `downloads` active at most (≥ 1). |

---

## State and results

### DownloadManagerState

`data class DownloadManagerState`. The value carried by `DownloadManager.state`.

| Property | Type | Description |
|----------|------|-------------|
| `snapshots` | `Map<DownloadId, DownloadSnapshot>` | Every known download. |
| `pending` | `List<DownloadId>` | Queued, not yet active, in order. |
| `active` | `Set<DownloadId>` | Currently running downloads. |
| `maxConcurrentDownloads` | `Int` | Effective concurrency limit. |

### DownloadSnapshot

`data class DownloadSnapshot`. One download's current state.

| Property | Type | Description |
|----------|------|-------------|
| `id` | `DownloadId` | The download's id. |
| `request` | `DownloadRequest` | The originating request. |
| `state` | `DownloadState` | Current state. |
| `attempts` | `Int` | Attempts made so far. |
| `queuePosition` | `Int?` | Position in the pending queue, or `null` if not pending. |

### DownloadState

`sealed interface DownloadState`.

| Variant | Payload | Meaning |
|---------|---------|---------|
| `Queued` | — | Waiting in the queue. |
| `Starting` | — | Selected to run; opening the connection. |
| `Running` | `progress: DownloadProgress` | Actively downloading. |
| `Pausing` | — | Pause requested, winding down. |
| `Paused` | — | Paused; `.part` retained. |
| `Cancelling` | — | Cancel requested, winding down. |
| `Cancelled` | — | Cancelled. |
| `Succeeded` | `result: DownloadSuccess` | Finished and finalized. |
| `Failed` | `failure: DownloadFailure` | Terminated with an error. |

### DownloadProgress

`data class DownloadProgress`.

| Member | Type | Description |
|--------|------|-------------|
| `bytesDownloaded` | `Long` | Bytes written so far. |
| `totalBytes` | `Long?` | Total size, or `null` if the server didn't report it. |
| `speedBytesPerSecond` | `Long?` | Current speed estimate, or `null`. |
| `etaMillis` | `Long?` | Estimated time remaining, or `null`. |
| `fraction` | `Double?` (computed) | `bytesDownloaded / totalBytes`, or `null` when the total is unknown or 0. |

### DownloadSuccess

`data class DownloadSuccess(finalPath: Path, bytesDownloaded: Long, totalBytes: Long?)`. Where the
file landed and how much was written.

### DownloadResult

`sealed interface DownloadResult` — returned by `download(...)` and `await(...)`.

| Variant | Payload |
|---------|---------|
| `DownloadResult.Success` | `success: DownloadSuccess` |
| `DownloadResult.Failure` | `failure: DownloadFailure` |

### DownloadFailure

`sealed interface DownloadFailure`. Typed terminal errors.

| Variant | Payload | When |
|---------|---------|------|
| `HttpStatus` | `code: Int`, `message: String?`, `retryAfterMillis: Long?` | Non-success HTTP status. |
| `Network` | `message: String` | Connection error. |
| `Timeout` | `message: String` | Request or idle/stall timeout. |
| `PermissionDenied` | `path: Path?`, `message: String?` | Can't write the destination. |
| `NoSpace` | `path: Path?`, `message: String?` | Not enough free space. |
| `AlreadyExists` | `path: Path?`, `message: String?` | Destination exists and policy is `FAIL_IF_EXISTS`. |
| `FileSystem` | `message: String` | Other storage error (e.g. missing Android context). |
| `IntegrityMismatch` | `expectedSize: Long`, `actualSize: Long` | Final size ≠ expected/`Content-Length`. |
| `ChecksumMismatch` | `expectedHex: String`, `actualHex: String` | Content hash didn't match. |
| `ServerChanged` | `message: String` | The resource changed mid-download (failed `If-Range`). |
| `CancelledByUser` | — | Cancelled via the API. |
| `Unknown` | `message: String` | Unclassified error. |

Retried automatically (per `RetryPolicy`): `Network`, `Timeout`, and `HttpStatus` for 5xx/408/429.
All others fail fast.

### CommandAck

`sealed interface CommandAck` — returned by every command method.

| Variant | Payload | Meaning |
|---------|---------|---------|
| `CommandAck.Accepted` | `id: DownloadId?` | The command was accepted. |
| `CommandAck.Rejected` | `reason: String` | The command couldn't be applied (e.g. wrong state, or after shutdown). |

---

## Android

### AndroidDownloadPathContext

Android-only (`androidMain`). Supplies the application `Context` used to resolve the default Downloads
directory. `object AndroidDownloadPathContext`.

| Function | Description |
|----------|-------------|
| `register(context: Context)` | Store the application context. Call once, before enqueuing a download that omits `destinationPath`. Stores `context.applicationContext`, so it won't leak an `Activity`. |
| `get(): Context?` | The registered context, or `null`. |

See [Set up Android](../how-to/set-up-android.md).
