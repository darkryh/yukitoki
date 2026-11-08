# Verify file integrity with checksums

This guide shows how to make Yukitoki reject a download whose bytes don't match what you expect. Use
it whenever a corrupted or tampered file would be worse than no file — installers, updates, media you
paid for.

Yukitoki has two independent integrity checks. You can use either or both.

## Check the size

Set `expectedBytes` and the download fails with
[`DownloadFailure.SizeMismatch`](../reference/api.md#tasks-and-results) if the finished file isn't
exactly that many bytes.

```kotlin
manager.enqueue(
    DownloadRequest(
        url = "https://example.com/app-1.2.3.apk",
        expectedBytes = 5_242_880, // 5 MiB, exactly
    )
)
```

The manager also cross-checks the server's `Content-Length` while streaming, so a truncated response
is caught even without `expectedBytes`.

## Check the content hash

Set `expectedChecksum` and the manager hashes the downloaded bytes and compares. A mismatch fails
with [`DownloadFailure.ChecksumMismatch`](../reference/api.md#tasks-and-results), and the bad file is
never moved into place.

```kotlin
import io.github.darkryh.yukitoki.manager.ChecksumSpec
import io.github.darkryh.yukitoki.manager.ChecksumAlgorithm

manager.enqueue(
    DownloadRequest(
        url = "https://example.com/app-1.2.3.apk",
        expectedChecksum = ChecksumSpec(
            algorithm = ChecksumAlgorithm.SHA_256,
            expectedHex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        ),
    )
)
```

Supported algorithms are `SHA_256` (64 hex chars) and `MD5` (32 hex chars). Prefer SHA-256; MD5 is
offered only for interoperating with systems that already publish MD5 digests.

`ChecksumSpec` validates its input **at construction time** — the wrong number of characters, or any
non-hex character, throws `IllegalArgumentException` immediately. So a malformed checksum is a
programming error you catch in development, never a silent no-op. Case doesn't matter (`AbC` == `abc`).

## Use both together

Size and checksum are complementary — the size check is cheap and catches truncation early; the hash
catches corruption anywhere in the file.

```kotlin
manager.enqueue(
    DownloadRequest(
        url = "https://example.com/app-1.2.3.apk",
        expectedBytes = 5_242_880,
        expectedChecksum = ChecksumSpec(ChecksumAlgorithm.SHA_256, publishedSha256),
    )
)
```

## React to a mismatch

Await the result and branch on the failure type. A checksum failure is not retried by the
manager — the same URL will produce the same bad bytes — so surface it to the user or fetch from a
different source.

```kotlin
val result = task.await()
if (result is DownloadResult.Failure) {
    when (val failure = result.failure) {
        is DownloadFailure.ChecksumMismatch ->
            showError("File is corrupt. Expected ${failure.expectedHex}, got ${failure.actualHex}.")
        is DownloadFailure.SizeMismatch ->
            showError("Wrong size: expected ${failure.expectedBytes}, got ${failure.actualBytes}.")
        else -> showError("Download failed: $failure")
    }
}
```

## Related

- [Handle failures and retries](handle-failures-and-retries.md) — handling results and retry policy.
- [API reference: tasks and results](../reference/api.md#tasks-and-results) — the full failure list.
- [API reference: DownloadRequest](../reference/api.md#downloadrequest)
