# Browser downloads

Browsers do not expose the user's Downloads directory as an ordinary `Path`. Use a browser-owned intent:

```kotlin
val result = DownloadIntents.dispatch(
    DownloadIntentRequest(
        url = "https://example.com/report.pdf",
        fileName = "report.pdf",
    )
)

when (result) {
    DownloadIntentResult.Dispatched -> Unit
    is DownloadIntentResult.Rejected -> println(result.reason)
}
```

`Dispatched` means the browser accepted the click-equivalent action. It does not mean the transfer completed and does not expose progress or the final path. `DownloadManager.create()` intentionally rejects browser use.

Browsers honor the `download` attribute only for same-origin URLs. For a cross-origin URL without headers, the browser ignores `fileName` and decides for itself. A file it can't display is saved under the name from the URL. A page it can display **doesn't download**, and `dispatch` still returns `Dispatched`. It opens in a new tab if you dispatched from a click. Outside a click, browsers block new tabs, so Yukitoki loads the URL in a hidden frame and nothing appears. Your app keeps running either way. If the file comes from another origin, have that server send `Content-Disposition: attachment`, or use the `fetch()` path below.

## Authenticated downloads (with headers)

A plain `<a download>` anchor cannot carry request headers, so it cannot authenticate a request.
`DownloadIntentRequest.headers` (default `Headers.Empty`) switches the wasm dispatch to a different
mechanism when non-empty:

```kotlin
val result = DownloadIntents.dispatch(
    DownloadIntentRequest(
        url = "https://example.com/exports/report.pdf",
        fileName = "report.pdf",
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

A cross-origin server must allow the request through CORS, including every header you send. Without
that, the browser blocks the request and you get `Rejected`. The browser doesn't say why, so the
reason names both a network error and CORS as possible causes.
`fileName` is honored on this path, even across origins.
