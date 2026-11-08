# Changelog

All notable changes to Yukitoki are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0-beta06] - 2026-11-08

A deliberately breaking release: the library has no external users yet, so the API changes with no
compatibility layer. Maven coordinates are unchanged.

### Changed (breaking)

- `DefaultDownloadManager` is internal. Create a manager with `DownloadManager.create`.
- `DownloadConcurrency` is replaced by an `Int`: `DownloadManagerConfig.maxConcurrentDownloads` and
  `setMaxConcurrentDownloads(n)`, which throws below 1.
- Removed the `enqueue` `id` parameter, `download(url, destination)`, and `DownloadId.new()`.
- `DownloadSuccess` becomes `DownloadedFile(path, sizeBytes)`, exposed as `DownloadResult.Success.file`
  and `DownloadState.Succeeded.file`.
- `CommandResult.Accepted` is an object. `Rejected` is typed: `NotFound`, `InvalidState(state)`,
  `InvalidPosition(position)`, `ManagerClosed`, `Failed(failure)`.
- `DownloadFailure.IntegrityMismatch` becomes `SizeMismatch(expectedBytes, actualBytes)`.
  `ManagerClosed` is an object. `path` is required on `PermissionDenied`, `NoSpace`, and `AlreadyExists`.
- `DownloadFailure.Unsupported` is removed: a `Range` header or unsupported scheme now throws
  `IllegalArgumentException` when building the `DownloadRequest`.
- Renamed `expectedSizeBytes` to `expectedBytes`, `MaxSegments.segments` to `count`, and
  `DownloadIntentRequest.suggestedFileName` to `fileName`. Intent file names now follow the same rules
  as managed downloads.
- `ProgressGranularity` values are both minimums, and `minBytesDelta` defaults to 0. Updates are
  time-based by default, so a slow download keeps updating.

### Fixed

- Cancelling a paused download reported `Paused`, and `await()` never returned.
- A pause or cancel issued before the worker first ran was overwritten or lost.
- `shutdown` hung when a worker was cancelled before it started. Concurrent `shutdown` calls and
  commands pending at `close()` also hung.
- `cancelAll` skipped paused downloads and downloads in retry backoff, so those retried after being
  cancelled. `pauseAll` skipped backoff too, and `resumeAll` ignored downloads still pausing.
- Queued progress could move `Pausing` or `Cancelling` back to `Running`, and a failure racing a pause
  or cancel overrode it.
- `download()` could report `ManagerClosed` for a finished download when finished entries are not
  retained.
- A fatal `Error` left the worker in `active` forever.
- Command replies could arrive before their state on multi-threaded dispatchers. An accepted command is
  now always visible in `state` when its result returns.
- Cancelling a paused download, or shutting down with one, left its `.part` file on disk.
- Filesystem work ran on the caller's thread when a UI scope was passed to `create(httpClient, scope)`.
  The coordinator now runs on the IO dispatcher, and iOS uses `Dispatchers.IO` for blocking file work.
- Reported speed was a lifetime average; it now covers about the last 500 ms. Segmented downloads now
  report speed and ETA.
- `bufferSizeBytes` had no effect, because each read returned at most 8 KiB.
- A small file no longer lowers the segment count for later large files, and parallelism lost to a
  `429` now recovers, one request per finished download.
- Whole-file requests now wait out an active rate-limit penalty.

### Performance

Measured against the previous engine on loopback with a 512 MiB file, two alternating passes on a busy
machine. CPU and allocation were consistent. Throughput varied too much to quote, including for a bare
read loop used as a control, so these are not speed claims.

- Segments write in place at their offsets in one file, instead of separate files merged at the end,
  which copied every byte twice and doubled temporary disk use. CPU per GiB with four segments fell
  from 2.8-3.4 s to 1.1-1.3 s on CIO, 5.0-5.3 s to 2.3 s on Java, and 4.7-5.9 s to 1.2-1.5 s on OkHttp.
- Single-stream CPU per GiB fell from about 1.1 s to 0.7 s on CIO, 3.7-3.9 s to 2.9-3.0 s on Java, and
  1.8-2.0 s to 1.1-1.4 s on OkHttp. Allocation for a CIO download fell by more than half.
- `state` is published once per batch of up to 256 messages. With 10,000 downloads and 40,000 events
  this took 2.0-2.3 s instead of 26-27 s, and allocated 1.1 GiB instead of 68 GiB on the coordinator.
- `Auto` uses one stream over HTTP/2, where segments share one connection. An explicit `MaxSegments` is
  still honored.
- No probe when `expectedBytes` already shows a file is small, and no range probe after a `404` or `410`.

### Removed

- `Auto`'s 6-segment tier, which the per-origin limit of four requests made unreachable. `Auto` now
  uses one stream below 16 MiB, two below 128 MiB, and four above.

## [1.0.0-beta05] - 2026-11-07

### Fixed

- Downloads longer than 15 seconds no longer fail with `DownloadFailure.Timeout` on JVM desktop
  ([#15](https://github.com/darkryh/yukitoki/issues/15)). Yukitoki bundled Ktor CIO, whose engine
  cancels a whole call, body included, after a 15-second `requestTimeout` by default, and
  `DownloadManager.create()` used a bare `HttpClient {}`, so the engine depended on classpath order.
  The bug often appeared only in packaged apps, which list jars alphabetically.
  - Every download request (single-stream and each range segment) now opts out of a total request
    timeout. This also protects clients passed to `create(httpClient, scope)`, including CIO clients
    with no `HttpTimeout` plugin. Connect and socket timeouts are still respected.
  - `create()` names the bundled engine explicitly on every platform and installs `HttpTimeout` with
    no total request timeout and a 30-second connect timeout. `configureClient` runs afterwards and
    can override both.
- `idleTimeout` now also bounds the wait for a response head and the HEAD / range metadata probes, so
  a server that accepts a connection but never answers fails with `DownloadFailure.Timeout` instead
  of hanging.
- Metadata probes no longer swallow coroutine cancellation.

### Changed

- **JVM desktop bundles the Java engine (`ktor-client-java`, built on the JDK `HttpClient`) instead
  of CIO.** CIO is no longer on consumers' runtime classpath through Yukitoki. If your app called a
  bare `HttpClient()` and relied on Yukitoki to supply CIO, it now resolves to the Java engine; add
  `ktor-client-cio` yourself to keep CIO.
- **Android consumers must compile against API 37.** Ktor 3.6 depends on OkHttp 5.5, whose AAR
  metadata requires `compileSdk = 37` (Android Gradle Plugin 9.x).
- Dependencies: Ktor 3.6.1, Okio 3.18.2, Kotlin 2.4.21.
- The JVM ABI dump gains public no-arg constructors for `CommandResult.Accepted`,
  `DownloadManagerConfig`, and `ProgressGranularity`, emitted by Kotlin 2.4.21. Additive only.

## [1.0.0-beta04] - 2026-07-19

### Added

- `DownloadIntentRequest.headers` (`io.ktor.http.Headers`, default `Headers.Empty`) for authenticated
  wasm downloads. On wasm, non-empty headers switch `DownloadIntents.dispatch` from plain
  `<a download>` anchor navigation to a `fetch()`-backed path that carries the headers, buffers the
  response as a `Blob`, and saves it via a short-lived `blob:` object URL — no streaming, no progress;
  suitable for bounded-size authenticated payloads, not large files. Empty headers (the default) keep
  the original anchor-navigation behavior unchanged, and every non-wasm platform is unaffected since
  `dispatch` there always returns `Rejected` regardless of headers.

## [1.0.0-beta03] - 2026-07-13

### Added

- `DownloadManager.create()` with owned resources and bundled OkHttp, CIO, and Darwin engines.
- Public `DownloadLocations` and explicit `DownloadDestination` types.
- `DownloadTask` handles, bounded finished-snapshot retention, removal APIs, automatic Android initialization, and Kotlin ABI validation.

### Changed

- Managed requests are now GET-only. The old `HttpMethod` property never supported request bodies, so advertising POST/PUT was misleading.
- Retry delays, progress intervals, idle timeouts, retry-after values, and ETA now use `kotlin.time.Duration`.
- Browser dispatch now returns `DownloadIntentResult.Dispatched` instead of claiming a completed filesystem download.
- `OverwritePolicy.DEFAULT` now renames directory collisions and rejects exact-file collisions.
- Development builds default to `1.0.0-SNAPSHOT`.

### Removed

- Replaced `destinationPath` and `fileName` with `DownloadDestination.Default`, `Directory`, or `File`.
- Renamed `DownloadConcurrency.Auto` to `Default`, `DownloadSegmentation.SingleStream` to `Single`, `DownloadSegmentation.Fixed` to `MaxSegments`, `CommandAck` to `CommandResult`, queue `move` to `moveWithinPriority`, and manager `cancel()` to `close()`.
- Cancellation is now `DownloadResult.Cancelled`, not `DownloadFailure.CancelledByUser`. Browser callers must use `DownloadIntentRequest` and `DownloadIntentResult`.

### Fixed

- Reserve destinations centrally so simultaneous tasks cannot share a final or partial path.
- Use task-specific partial paths and discard untrusted stale partial data before a new task starts.
- Report destination-resolution failures as typed enqueue rejections, restore replaced files when finalization fails, throttle segmented progress, and prune retained terminal state.

## [1.0.0-beta02] - 2026-07-03

Reliability fixes for segmented downloads and rate-limit pacing. No public API changes —
`1.0.0-beta01` code continues to work unchanged.

### Fixed

- **Rate-limit pacing uses a monotonic clock.** Per-origin backpressure and `429` back-off now
  measure time with a monotonic source instead of the system wall clock, so segmented-download
  pacing stays correct even if the system clock is stepped (for example by an NTP adjustment).
- **Segment permit leak on cancellation.** When a segmented download was cancelled while retrying
  after a `429`, a per-origin request permit could leak, permanently shrinking that host's effective
  parallelism. Permit release is now cancellation-safe.

### Changed

- Test suite made deterministic across JVM, Native, and JS (virtual-clock pacing and a thread-safe
  in-memory test filesystem), removing intermittent CI failures in the segmentation tests.

## [1.0.0-beta01] - 2026-07-03

First public beta of the Yukitoki Kotlin Multiplatform download manager.

### Added

- **Multiplatform download manager** with a common-code API for JVM, Android, iOS
  (arm64 + simulator arm64), and wasmJs.
- **Actor-based coordinator** — a single-writer state machine owns the queue and never touches the
  filesystem, so it is safe to drive from a UI-confined scope such as `viewModelScope`.
- **Parallel queue** with configurable concurrency (`DownloadConcurrency.Auto` / `Fixed(n)`) and
  three priority levels (`HIGH`, `NORMAL`, `LOW`).
- **Command API**: `enqueue`, `download`, `pause`, `resume`, `cancel`, `retry`, `move`,
  their `*All` variants, and `setConcurrency`.
- **Progress and state** exposed as a queue-wide `StateFlow<DownloadManagerState>` and a per-item
  `Flow<DownloadSnapshot?>` via `observe(id)`.
- **HTTP resume** with `.part` files, `Range`, and conditional `If-Range` validators
  (ETag / Last-Modified).
- **Segmented parallel range downloads** — used automatically for larger files only when the server
  proves byte-range support; falls back to a validated single stream otherwise.
- **Retries** with exponential backoff + jitter for network errors, timeouts, and HTTP 5xx / 408 / 429.
- **Rate-limit backpressure**: honors numeric `Retry-After`, reduces per-origin pressure, and can
  drop segment count or fall back to single-stream under `429`.
- **Per-attempt idle/stall timeout** (`idleTimeoutMs`, default 30 s) independent of the Ktor client
  timeouts.
- **Integrity checks**: expected byte count, `Content-Length` validation, and optional
  MD5 / SHA-256 checksums (`ChecksumSpec`).
- **File safety**: streamed to an atomic `.part` temp file, then moved into place; overwrite policies
  `REPLACE`, `FAIL_IF_EXISTS`, `RENAME_UNIQUE`; input validation on URLs, file names, and checksums.
- **Typed failure model** (`DownloadFailure`) covering HTTP status, network, timeout, permission,
  disk space, existing-file, filesystem, integrity/checksum mismatch, server-changed, and cancellation.
- **Lifecycle controls**: suspending `shutdown(cancelActive)` for an orderly drain and non-suspending
  `cancel()` for lifecycle callbacks such as `ViewModel.onCleared()`.
- **Browser download intents** (`DownloadIntents.dispatch`) for simple GET downloads on wasm, where
  the browser owns the actual transfer.
- **Sample app** — a shared Compose Multiplatform UI running on Android, iOS, and desktop.

[Unreleased]: https://github.com/darkryh/yukitoki/compare/v1.0.0-beta06...HEAD
[1.0.0-beta06]: https://github.com/darkryh/yukitoki/compare/v1.0.0-beta05...v1.0.0-beta06
[1.0.0-beta05]: https://github.com/darkryh/yukitoki/compare/v1.0.0-beta04...v1.0.0-beta05
[1.0.0-beta04]: https://github.com/darkryh/yukitoki/compare/v1.0.0-beta03...v1.0.0-beta04
[1.0.0-beta03]: https://github.com/darkryh/yukitoki/compare/v1.0.0-beta02...v1.0.0-beta03
[1.0.0-beta02]: https://github.com/darkryh/yukitoki/compare/v1.0.0-beta01...v1.0.0-beta02
[1.0.0-beta01]: https://github.com/darkryh/yukitoki/releases/tag/v1.0.0-beta01
