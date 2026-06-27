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
- Command API (`enqueue`, `pause`, `resume`, `cancel`, `retry`, `reorder`, `setConcurrency`)
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

val id = DownloadId.new()
manager.command(
    DownloadCommand.Enqueue(
        id = id,
        request = DownloadRequest(
            url = "https://example.com/file.docx",
            destinationPath = "/downloads/file.docx".toPath(),
        )
    )
)

val result: DownloadResult? = manager.awaitTerminal(id)

// or, for a single one-shot download:
val r: DownloadResult = manager.downloadNow(DownloadRequest(url = "https://example.com/a.bin"))
```

## ViewModel + Compose

```kotlin
class DownloadsViewModel(private val manager: DownloadManager) : ViewModel() {
    val state = manager.observeState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.observeState().value)

    fun enqueue(url: String) = viewModelScope.launch {
        manager.command(DownloadCommand.Enqueue(DownloadRequest(url)))
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
val item: DownloadSnapshot? by manager.observeItem(id).collectAsStateWithLifecycle(null)
```

## Threading model

The coordinator (queue + state machine) runs on the dispatcher of the `scope` you pass — it never touches the filesystem, so a UI-confined scope is fine. Each download **worker** is launched on an I/O dispatcher (`ioDispatcher`, default per platform: `Dispatchers.IO` on JVM/Android, `Dispatchers.Default` on Native/Wasm), so the blocking okio reads/writes never run on your caller thread. You can override it:

```kotlin
DefaultDownloadManager(httpClient, scope, ioDispatcher = myIoDispatcher)
```

## Lifecycle

- `shutdown(cancelActive = false)` — graceful: stops accepting work and **waits for in-flight downloads to finish** (their files are finalized) before returning. Outstanding `awaitTerminal` callers are released.
- `shutdown(cancelActive = true)` — cancels active downloads, then returns.
- `cancel()` — non-suspending, immediate teardown for `ViewModel.onCleared()` and similar; cancels everything at once.
- After shutdown/cancel, `command()` returns `CommandAck.Rejected` and `awaitTerminal()` returns `null` instead of hanging.

## Android setup

Resolving the default Downloads directory needs an application `Context`. Register it once (e.g. in `Application.onCreate`) **before** enqueuing a download that omits `destinationPath`:

```kotlin
AndroidDownloadPathContext.register(applicationContext)
```

If you always pass an explicit `DownloadRequest.destinationPath`, this is not required. When it is needed and missing, the download fails fast with a clear `DownloadFailure.FileSystem` message rather than writing to an unwritable location.

## Configuration

`DownloadManagerConfig` (constructor) controls global behavior:

- `maxConcurrentDownloads` (default 2) — also changeable at runtime via `DownloadCommand.SetConcurrency`.
- `bufferSizeBytes` (default 64 KiB).
- `deletePartialOnFailure` / `deletePartialOnCancel` (default true).
- `idleTimeoutMs` (default 30 000) — per-attempt stall guard: if no bytes arrive for this long the attempt fails with `DownloadFailure.Timeout` (and may retry). Set `0` to disable. This is independent of, and complementary to, your `HttpClient` connect/request timeouts.

Per-request options live on `DownloadRequest`: `overwritePolicy`, `retryPolicy`, `expectedSizeBytes`, `expectedChecksum`, `maxSegments`, `priority`, `progressGranularity`, custom `headers`, `fileName`.

## Production contract

- `DefaultDownloadManager` is the filesystem-backed manager for JVM, Android, and iOS. It streams through Ktor into a `.part` file and atomically moves the completed file into place.
- Download parallelism has two levels: `DownloadManagerConfig.maxConcurrentDownloads` bounds active downloads, and `DownloadRequest.maxSegments` enables per-file byte-range workers when the server supports `Accept-Ranges: bytes` and exposes a positive `Content-Length`.
- Segmented downloads are GET-only. The manager probes metadata with `HEAD`, requests isolated `Range` slices with `Accept-Encoding: identity`, validates each `206 Partial Content` response, then merges segment temp files into the normal `.part` file before checksum and atomic finalization.
- If segmented preflight cannot prove range support, the manager falls back to the validated single-stream path. If a segment response is inconsistent, the download fails and temp files are cleaned according to the failure cleanup policy.
- Single-stream pause preserves the `.part` file for resume. Segmented temp slices are implementation details and are cleaned on failure/cancel; callers should treat pause/resume as best for the single-stream resume path.
- Cancel and failure cleanup follow `DownloadManagerConfig.deletePartialOnCancel` and `deletePartialOnFailure`.
- Retries are attempted only for network failures, timeouts (including the idle/stall timeout), HTTP 5xx, 408, and 429, and use the configured exponential backoff and jitter.
- Caller-supplied `Range` is rejected because resume headers are owned by the manager.
- Network and charging constraints are rejected by the default manager; enforce them in a platform scheduler before enqueue.

## Wasm

Browsers do not expose a normal writable Downloads directory to Kotlin/Wasm. Use `DownloadIntents.dispatch(request)` on wasm for simple GET downloads. It creates a browser download intent and returns once the browser accepts the intent; it does not claim byte progress, final filesystem path, checksum validation, custom headers, or charging/network constraints.

For authenticated exports or checksum-required assets on wasm, prefer a server-issued same-origin URL or short-lived signed URL and let the browser handle the download.

## Notes

- Keep this module domain-agnostic; do not import feature/app types here.
- App-layer integrations should map UI intents to this API.
- If `destinationPath` is omitted, a platform default Downloads directory is used (see Android setup).
- HTTP resume behavior is aligned with the standard `Range`/`Accept-Ranges`/`Content-Range` model and conditional resume with `If-Range`.
