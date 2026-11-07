# API reference

## Creation

- `DownloadManager.create(config, configureClient)` creates a manager with owned resources and a client on the bundled platform engine (Java on JVM, OkHttp on Android, Darwin on iOS). `configureClient` runs after Yukitoki's defaults.
- `DownloadManager.create(httpClient, scope, config)` borrows caller-owned resources.
- `DefaultDownloadManager(...)` is the advanced constructor for custom filesystems, dispatchers, and tests.

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
- `headers` (caller-supplied `Range` is rejected)
- `overwritePolicy` (`DEFAULT` renames directory collisions and rejects exact-file collisions)
- `retryPolicy`
- `expectedSizeBytes`
- `progressGranularity`
- `priority`
- `segmentation`
- `expectedChecksum`

## Tasks and results

`enqueue` returns `DownloadTask`, containing `id`, `state`, `await`, `pause`, `resume`, `cancel`, and `retry`.

`DownloadResult` is `Success`, `Cancelled`, or `Failure`. Runtime failures are represented by `DownloadFailure`: HTTP status, network, timeout, permission, space, collision, filesystem, integrity, checksum, remote-file change, unsupported capability, closed manager, or unexpected failure.

Invalid request construction throws `IllegalArgumentException`. A rejected public `enqueue` throws `DownloadRejectedException` carrying its typed failure. `download(request)` returns the typed failure instead.

## State

`DownloadState` progresses through queued, starting, running, pausing/paused, cancelling/cancelled, succeeded, or failed. `DownloadSnapshot.resolvedDestination` contains the concrete path reserved during enqueue.

`DownloadManager.state` contains snapshots, pending IDs, active IDs, and current concurrency. `observe(id)` watches one ID; `await(id)` returns null for an unknown or already-pruned ID.

## Queue commands

- `pause`, `resume`, `cancel`, `retry`
- `pauseAll`, `resumeAll`, `cancelAll`, `retryAllFailed`
- `moveWithinPriority(id, position)` — reorders only inside the task's priority tier.
- `setConcurrency(Default | Fixed(n))`
- `remove(id, deleteFile)`
- `clearFinished()`

Commands return `CommandResult.Accepted` or `Rejected`.

## Configuration

`DownloadManagerConfig`:

- `concurrency = DownloadConcurrency.Default` (currently three active downloads)
- `bufferSizeBytes = 64 KiB`
- `deletePartialOnFailure = true`
- `deletePartialOnCancel = true`
- `idleTimeout = 30.seconds` (`Duration.ZERO` disables it)
- `maxRetainedFinished = 100`

`RetryPolicy` uses `Duration` and supplies `Default` and `None`. `ProgressGranularity` uses a byte delta and `minInterval: Duration`. ETA is exposed as `Duration?`.

`DownloadSegmentation` is `Auto`, `Single`, or `MaxSegments(n)`. `MaxSegments` is a ceiling; Yukitoki can reduce it or fall back when a server cannot safely serve ranges.

## Browser intents

`DownloadIntents.dispatch(DownloadIntentRequest(url, suggestedFileName, headers))` returns `DownloadIntentResult.Dispatched` or `Rejected`. `Dispatched` only means that the browser accepted the action; it does not claim completion, progress, or a final filesystem path.

`DownloadIntentRequest.headers` (`io.ktor.http.Headers`, default `Headers.Empty`) is only honored on wasm. Empty headers take the original plain `<a download>` anchor-navigation path. Non-empty headers switch to a `fetch()`-backed path that carries those headers, buffers the whole response as a `Blob`, and saves it via a short-lived `blob:` object URL — no streaming, no progress; suitable only for bounded-size authenticated payloads. See [Browser downloads](../how-to/download-on-wasm.md#authenticated-downloads-with-headers). On every non-wasm platform, `dispatch` always returns `Rejected`, so `headers` has no effect there.
