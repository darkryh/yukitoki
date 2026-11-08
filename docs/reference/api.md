# API reference

## Creation

- `DownloadManager.create(config, configureClient)` creates a manager with owned resources and a client on the bundled platform engine (Java on JVM, OkHttp on Android, Darwin on iOS). `configureClient` runs after Yukitoki's defaults.
- `DownloadManager.create(httpClient, scope, config)` borrows caller-owned resources.

Managed filesystem downloads are available on Android, JVM, and iOS. `DownloadLocations.isSupported` is false in the browser.

## Locations and destinations

- `DownloadLocations.downloads: Path` — writable platform Downloads directory; it can be sandboxed.
- `DownloadDestination.Default` — default directory plus a URL-derived safe name.
- `DownloadDestination.Directory(path, fileName?)` — directory with optional name override.
- `DownloadDestination.File(path)` — exact output path.

## DownloadRequest

`DownloadRequest` supports HTTP and HTTPS GET downloads. Properties:

- `url`
- `destination`
- `headers` (a `Range` header throws `IllegalArgumentException`; ranges are managed internally)
- `overwritePolicy` (`DEFAULT` renames directory collisions and rejects exact-file collisions)
- `retryPolicy`
- `expectedBytes`
- `progressGranularity`
- `priority`
- `segmentation`
- `expectedChecksum`

## Tasks and results

`enqueue(request)` and `enqueue(url, destination, priority)` return `DownloadTask`, containing `id`, `state`, `await`, `pause`, `resume`, `cancel`, and `retry`. `download(request)` enqueues and suspends until the download finishes.

`DownloadResult` is `Success(file)`, `Cancelled`, or `Failure(failure)`. `DownloadedFile` holds the final `path` and `sizeBytes`.

`DownloadFailure` is one of `HttpStatus`, `Network`, `Timeout`, `PermissionDenied`, `NoSpace`, `AlreadyExists`, `FileSystem`, `SizeMismatch`, `ChecksumMismatch`, `ServerChanged`, `ManagerClosed`, or `Unexpected`.

Invalid request construction throws `IllegalArgumentException`. A rejected public `enqueue` throws `DownloadRejectedException` carrying its typed failure. `download(request)` returns the typed failure instead.

## State

`DownloadState` progresses through queued, starting, running, pausing/paused, cancelling/cancelled, succeeded, or failed. `DownloadSnapshot.resolvedDestination` contains the concrete path reserved during enqueue.

`DownloadManager.state` contains snapshots, pending IDs, active IDs, and `maxConcurrentDownloads`. `observe(id)` watches one ID; `await(id)` returns null for an unknown or already-pruned ID. `state` is a `StateFlow`, so it can skip intermediate values: `Pausing` and `Cancelling` may never be observed before `Paused` and `Cancelled`.

## Queue commands

- `pause`, `resume`, `cancel`, `retry`
- `pauseAll`, `resumeAll`, `cancelAll`, `retryAllFailed`
- `moveWithinPriority(id, position)` — reorders only inside the task's priority tier.
- `setMaxConcurrentDownloads(n)` — throws `IllegalArgumentException` below 1.
- `remove(id, deleteFile)`
- `clearFinished()`

Commands return `CommandResult.Accepted` or a `CommandResult.Rejected`:

- `NotFound` — no download has this ID.
- `InvalidState(state)` — the command doesn't fit the download's current state.
- `InvalidPosition(position)` — the position is outside the priority queue.
- `ManagerClosed` — the manager is shutting down or closed.
- `Failed(failure)` — the command was valid, but carrying it out failed.

`pauseAll`, `resumeAll` and `cancelAll` apply to every unfinished download, including those waiting for a retry.

## Configuration

`DownloadManagerConfig`:

- `maxConcurrentDownloads = 3`
- `bufferSizeBytes = 64 KiB` (the largest single write to disk)
- `deletePartialOnFailure = true`
- `deletePartialOnCancel = true`
- `idleTimeout = 30.seconds` (`Duration.ZERO` disables it)
- `maxRetainedFinished = 100`

`RetryPolicy` uses `Duration` and supplies `Default` and `None`. `ProgressGranularity` sets two minimums for each update, `minBytesDelta` (default 0) and `minInterval` (default 150 ms). The first and final bytes are always reported. ETA is exposed as `Duration?`.

`DownloadSegmentation` is `Auto`, `Single`, or `MaxSegments(count)`. `MaxSegments` is a ceiling; Yukitoki can reduce it or fall back when a server cannot safely serve ranges, and each origin allows four parallel requests. `Auto` uses one stream below 16 MiB and over HTTP/2, where segments would share one connection.

## Browser intents

`DownloadIntents.dispatch(DownloadIntentRequest(url, fileName, headers))` returns `DownloadIntentResult.Dispatched` or `Rejected`. `Dispatched` only means that the browser accepted the action; it does not claim completion, progress, or a final filesystem path.

`DownloadIntentRequest.headers` (`io.ktor.http.Headers`, default `Headers.Empty`) is only honored on wasm. Empty headers take the original plain `<a download>` anchor-navigation path. Non-empty headers switch to a `fetch()`-backed path that carries those headers, buffers the whole response as a `Blob`, and saves it via a short-lived `blob:` object URL — no streaming, no progress; suitable only for bounded-size authenticated payloads. See [Browser downloads](../how-to/download-on-wasm.md#authenticated-downloads-with-headers). On every non-wasm platform, `dispatch` always returns `Rejected`, so `headers` has no effect there.
