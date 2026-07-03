# Handle failures and retries

This guide covers what happens when a download goes wrong: the automatic retries Yukitoki performs
for you, how to tune them, and how to react to a final failure.

## Two layers of retry

1. **Automatic, in-attempt retries.** Inside a single download, transient errors are retried with
   exponential backoff + jitter, governed by the request's `RetryPolicy`. You don't call anything.
2. **Manual re-queue.** Once a download exhausts its policy and lands in `DownloadState.Failed`, it
   stays there until you call [`manager.retry(id)`](control-the-queue.md#retry-a-failed-download).

## What gets retried automatically

Only errors that a retry could plausibly fix:

- Network errors (connection dropped, reset).
- Timeouts — including the per-attempt idle/stall timeout.
- HTTP `5xx`, `408 Request Timeout`, and `429 Too Many Requests`.

Everything else fails fast without retrying, because retrying wouldn't help: a `404`, a checksum
mismatch, a permission error, or no disk space will fail identically on the next attempt.

## Tune the retry policy

The default is 3 attempts with 500 ms → 8 s exponential backoff and 20% jitter. Override it per
request:

```kotlin
import io.github.darkryh.yukitoki.manager.RetryPolicy

manager.enqueue(
    url = "https://example.com/flaky.bin",
    retryPolicy = RetryPolicy(
        maxAttempts = 5,
        initialDelayMs = 1_000,
        maxDelayMs = 30_000,
        multiplier = 2.0,
        jitterRatio = 0.3,
        retryOnHttp5xx = true,
        retryOnHttp408 = true,
        retryOnHttp429 = true,
    ),
)
```

You can turn off retrying for a specific HTTP class — for example, don't retry `429` if you'd rather
surface rate-limiting to the user immediately:

```kotlin
val policy = RetryPolicy.default().copy(retryOnHttp429 = false)
```

`RetryPolicy` validates its arguments on construction (`maxAttempts ≥ 1`, `maxDelayMs ≥ initialDelayMs`,
`multiplier ≥ 1.0`, `jitterRatio` in `[0, 1]`), so a nonsensical policy throws immediately rather than
misbehaving at runtime.

## Guard against stalled connections

A socket that connects but then goes silent won't trip a normal request timeout. The manager's
per-attempt **idle timeout** covers this: if no bytes arrive for `idleTimeoutMs` (default 30 s), the
attempt fails with `DownloadFailure.Timeout` and may retry.

```kotlin
val manager = DefaultDownloadManager(
    httpClient = httpClient,
    scope = appScope,
    config = DownloadManagerConfig(idleTimeoutMs = 15_000), // 0 disables it
)
```

This is independent of, and complementary to, the `HttpTimeout` you configure on your `HttpClient`
(which covers connect/request time).

## React to a final failure

When a download ends in `DownloadState.Failed`, branch on the typed
[`DownloadFailure`](../reference/api.md#downloadfailure):

```kotlin
manager.observe(id).collect { snapshot ->
    val failure = (snapshot?.state as? DownloadState.Failed)?.failure ?: return@collect
    val message = when (failure) {
        is DownloadFailure.HttpStatus -> "Server returned ${failure.code}"
        is DownloadFailure.Network -> "Network problem — check the connection"
        is DownloadFailure.Timeout -> "The download stalled"
        is DownloadFailure.PermissionDenied -> "Can't write to ${failure.path}"
        is DownloadFailure.NoSpace -> "Not enough free space"
        is DownloadFailure.AlreadyExists -> "A file is already there"
        is DownloadFailure.FileSystem -> "Storage error: ${failure.message}"
        is DownloadFailure.IntegrityMismatch -> "Wrong size (${failure.actualSize} bytes)"
        is DownloadFailure.ChecksumMismatch -> "File is corrupt"
        is DownloadFailure.ServerChanged -> "The file changed on the server; start over"
        DownloadFailure.CancelledByUser -> "Cancelled"
        is DownloadFailure.Unknown -> "Unexpected error: ${failure.message}"
    }
    showError(message)
}
```

Because `DownloadFailure` is a sealed interface, the `when` is exhaustive — add a new failure type
in a future version and the compiler flags every `when` that needs updating.

## A note on `HttpStatus` and `Retry-After`

`DownloadFailure.HttpStatus` carries the `code` and, when the server sent one, a
`retryAfterMillis`. For `429`/`503` the manager already honors a numeric `Retry-After` internally
before giving up, so by the time you see a `Failed` state the automatic pacing has been tried.

## Related

- [Control the queue](control-the-queue.md) — `retry`, `retryAllFailed`.
- [Verify file integrity with checksums](verify-file-integrity.md) — non-retryable integrity failures.
- [API reference: DownloadFailure, RetryPolicy](../reference/api.md#downloadfailure)
