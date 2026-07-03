# Yukitoki Download Manager (KMP)

Generic Kotlin Multiplatform download manager for streaming resource downloads with progress, parallel queue orchestration, cancellation, pause/resume, retries, segmented range downloads, checksums and file safety. Built for coroutines — it drops straight into a `ViewModel` and Compose / Compose Multiplatform.

Targets: JVM, Android, iOS (arm64 + simulator arm64), wasmJs.

## Features

- KMP `commonMain` API, idiomatic coroutines + `Flow`/`StateFlow`
- Actor-based single-writer coordinator (no shared-state locks)
- Blocking file I/O is always offloaded to an I/O dispatcher — **safe to drive from `viewModelScope` (Main) without blocking the UI**
- Configurable parallel downloads with 3-level priority queues
- Streamed downloads via Ktor client into an atomic temp file (`.part` → final move)
- Progress/state via `StateFlow` (whole queue) and per-item `Flow`
- Typed failure model (`DownloadFailure`)
- Retry with exponential backoff + jitter, and a wall-clock idle/stall timeout
- HTTP resume with `.part` files, `Range`, and `If-Range` validators (ETag / Last-Modified)
- Segmented parallel range downloads when the server advertises byte-range support
- Integrity checks for expected byte count, `Content-Length`, and optional MD5 / SHA-256 checksums
- Overwrite policies (`REPLACE`, `FAIL_IF_EXISTS`, `RENAME_UNIQUE`)
- Command API (`enqueue`, `pause`, `resume`, `cancel`, `retry`, `move`, `setConcurrency`)
- Browser/Wasm download intents for cases where the browser owns the actual download

## Install

```kotlin
// settings.gradle.kts → mavenCentral() / mavenLocal()
dependencies {
    implementation("io.github.darkryh.yukitoki:download-manager:1.0.0")
}
```

## Quick usage

```kotlin
val manager = DefaultDownloadManager(
    httpClient = httpClient,   // you own and configure it (install HttpTimeout for connect/headers)
    scope = appScope,          // you own the lifecycle; the manager runs under a child SupervisorJob
)

val id = manager.enqueue(
    url = "https://example.com/file.docx",
    destinationPath = "/downloads/file.docx".toPath(),
)

val result: DownloadResult? = manager.await(id)

// or, for a single one-shot download:
val r: DownloadResult = manager.download("https://example.com/a.bin")
```

By default the manager chooses bounded queue concurrency and per-file segmentation automatically. Small files use a single stream; larger files use segmented HTTP range workers only when the server proves that ranges are safe.

## ViewModel + Compose

```kotlin
class DownloadsViewModel(private val manager: DownloadManager) : ViewModel() {
    val state = manager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.state.value)

    fun enqueue(url: String) = viewModelScope.launch {
        manager.enqueue(url)
    }

    fun pause(id: DownloadId) = viewModelScope.launch {
        manager.pause(id)
    }

    // Non-suspending teardown for a lifecycle callback that isn't a coroutine.
    override fun onCleared() = manager.cancel()
}

@Composable
fun DownloadsScreen(vm: DownloadsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn {
        items(state.snapshots.values.toList(), key = { it.id.value }) { snap ->
            // render snap.state (Queued / Running(progress) / Succeeded / Failed / ...)
        }
    }
}
```

Per-item observation (cheaper recomposition for a detail screen):

```kotlin
val item: DownloadSnapshot? by manager.observe(id).collectAsStateWithLifecycle(null)
```

## Threading model

The coordinator (queue + state machine) runs on the dispatcher of the `scope` you pass — it never touches the filesystem, so a UI-confined scope is fine. Each download **worker** is launched on an I/O dispatcher (`ioDispatcher`, default per platform: `Dispatchers.IO` on JVM/Android, `Dispatchers.Default` on Native/Wasm), so the blocking okio reads/writes never run on your caller thread. You can override it:

```kotlin
DefaultDownloadManager(httpClient, scope, ioDispatcher = myIoDispatcher)
```

## Lifecycle

- `shutdown(cancelActive = false)` — graceful: stops accepting work and **waits for in-flight downloads to finish** (their files are finalized) before returning. Outstanding `await` callers are released.
- `shutdown(cancelActive = true)` — cancels active downloads, then returns.
- `cancel()` — non-suspending, immediate teardown for `ViewModel.onCleared()` and similar; cancels everything at once.
- After shutdown/cancel, control methods return `CommandAck.Rejected` and `await()` returns `null` instead of hanging.

## Android setup

Resolving the default Downloads directory needs an application `Context`. Register it once (e.g. in `Application.onCreate`) **before** enqueuing a download that omits `destinationPath`:

```kotlin
AndroidDownloadPathContext.register(applicationContext)
```

If you always pass an explicit `DownloadRequest.destinationPath`, this is not required. When it is needed and missing, the download fails fast with a clear `DownloadFailure.FileSystem` message rather than writing to an unwritable location.

## Configuration

`DownloadManagerConfig` (constructor) controls global behavior:

- `concurrency` (default `DownloadConcurrency.Auto`) — use `DownloadConcurrency.Fixed(n)` only when the app has a specific queue limit.
- `bufferSizeBytes` (default 64 KiB).
- `deletePartialOnFailure` / `deletePartialOnCancel` (default true).
- `idleTimeoutMs` (default 30 000) — per-attempt stall guard: if no bytes arrive for this long the attempt fails with `DownloadFailure.Timeout` (and may retry). Set `0` to disable. This is independent of, and complementary to, your `HttpClient` connect/request timeouts.

Per-request options live on `DownloadRequest` and are also available on the simple `enqueue(...)` / `download(...)` overloads: `overwritePolicy`, `retryPolicy`, `expectedSizeBytes`, `expectedChecksum`, `segmentation`, `priority`, `progressGranularity`, custom `headers`, `fileName`, and `method`.

```kotlin
val request = DownloadRequest(
    url = "https://example.com/video.mp4",
    destinationPath = "/downloads/video.mp4".toPath(),
    segmentation = DownloadSegmentation.Fixed(4), // optional advanced override
)
```

`DownloadRequest` rejects unsafe inputs at construction time: blank or relative URLs, unsupported URL schemes, negative expected sizes, malformed checksum hex values, and `fileName` values that contain path separators or characters unsafe across supported platforms. `DefaultDownloadManager` accepts HTTP(S) URLs; browser download intents may also use browser-owned schemes such as `data:` and `blob:`.

## Production contract

- `DefaultDownloadManager` is the filesystem-backed manager for JVM, Android, and iOS. It streams through Ktor into a `.part` file and atomically moves the completed file into place.
- Download parallelism has two levels: `DownloadManagerConfig.concurrency` bounds active downloads, and `DownloadRequest.segmentation` controls per-file byte-range workers.
- Automatic segmentation is GET-only. The manager probes metadata with `HEAD`, falls back to a tiny `GET Range: bytes=0-0` probe when `HEAD` is broken or inconclusive, then requests isolated `Range` slices with `Accept-Encoding: identity`.
- Segmented responses are validated (`206 Partial Content`, `Content-Range`, expected byte counts), then merged into the normal `.part` file before checksum and atomic finalization.
- If automatic segmented preflight cannot prove range support, the manager falls back to the validated single-stream path. Explicit `DownloadSegmentation.Fixed(n)` is treated as a maximum segment count, not an instruction to ignore server pressure.
- `429 Too Many Requests` is treated as a pacing signal: the manager honors numeric `Retry-After`, reduces per-origin request pressure, retries segmented downloads with fewer segments, and can fall back to single-stream when a host is rate-limiting.
- Single-stream pause preserves the `.part` file for resume. Segmented temp slices are implementation details and are cleaned on failure/cancel; callers should treat pause/resume as best for the single-stream resume path.
- Cancel and failure cleanup follow `DownloadManagerConfig.deletePartialOnCancel` and `deletePartialOnFailure`.
- Retries are attempted only for network failures, timeouts (including the idle/stall timeout), HTTP 5xx, 408, and 429, and use the configured exponential backoff and jitter.
- Caller-supplied `Range` is rejected because resume headers are owned by the manager.
- Network and charging constraints are intentionally outside the request model; enforce them in a platform scheduler before enqueue.

## Wasm

Browsers do not expose a normal writable Downloads directory to Kotlin/Wasm. Use `DownloadIntents.dispatch(request)` on wasm for simple GET downloads. It creates a browser download intent and returns once the browser accepts the intent; it does not claim byte progress, final filesystem path, checksum validation, or custom headers.

For authenticated exports or checksum-required assets on wasm, prefer a server-issued same-origin URL or short-lived signed URL and let the browser handle the download.

## Notes

- Keep this module domain-agnostic; do not import feature/app types here.
- App-layer integrations should map UI intents to this API.
- If `destinationPath` is omitted, a platform default Downloads directory is used (see Android setup).
- HTTP resume behavior is aligned with the standard `Range`/`Accept-Ranges`/`Content-Range` model and conditional resume with `If-Range`.
