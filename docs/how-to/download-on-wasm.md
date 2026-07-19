# Browser downloads

Browsers do not expose the user's Downloads directory as an ordinary `Path`. Use a browser-owned intent:

```kotlin
val result = DownloadIntents.dispatch(
    DownloadIntentRequest(
        url = "https://example.com/report.pdf",
        suggestedFileName = "report.pdf",
    )
)

when (result) {
    DownloadIntentResult.Dispatched -> Unit
    is DownloadIntentResult.Rejected -> println(result.reason)
}
```

`Dispatched` means the browser accepted the click-equivalent action. It does not mean the transfer completed and does not expose progress or the final path. `DownloadManager.create()` intentionally rejects browser use.

## Authenticated downloads (with headers)

A plain `<a download>` anchor cannot carry request headers, so it cannot authenticate a request.
`DownloadIntentRequest.headers` (default `Headers.Empty`) switches the wasm dispatch to a different
mechanism when non-empty:

```kotlin
val result = DownloadIntents.dispatch(
    DownloadIntentRequest(
        url = "https://example.com/exports/report.pdf",
        suggestedFileName = "report.pdf",
        headers = headersOf("Authorization", "Bearer $token"),
    )
)

when (result) {
    DownloadIntentResult.Dispatched -> Unit
    is DownloadIntentResult.Rejected -> println(result.reason)
}
```

- **Empty headers (the default):** unchanged plain anchor-navigation, exactly as above.
- **Non-empty headers:** issues a `fetch()` request carrying those headers, reads the whole response
  body into memory as a `Blob`, and saves that blob through the same hidden-anchor mechanic via a
  short-lived `blob:` object URL.

This buffers the **entire response in browser memory** before the save starts — there is no
streaming and no progress reporting. It is suitable for bounded-size authenticated payloads (a
document export, a report, a small archive), not large files. A non-2xx response or a network/fetch
failure both surface as `DownloadIntentResult.Rejected` with a description of what went wrong.
