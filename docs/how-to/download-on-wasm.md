# Download on wasm / in the browser

Browsers don't expose a writable Downloads directory to Kotlin/Wasm, so the filesystem-backed
`DefaultDownloadManager` isn't the right tool there. Instead, hand the download to the browser with
`DownloadIntents`.

## Use a browser download intent

`DownloadIntents.dispatch(request)` creates a browser download intent (the same mechanism as clicking
a download link) and returns once the browser accepts it.

```kotlin
import io.github.darkryh.yukitoki.manager.DownloadIntents
import io.github.darkryh.yukitoki.manager.DownloadRequest
import io.github.darkryh.yukitoki.manager.DownloadResult

suspend fun save(url: String) {
    if (!DownloadIntents.isSupported) {
        // Not running in a browser context.
        return
    }

    val result = DownloadIntents.dispatch(
        DownloadRequest(
            url = url,
            fileName = "report.pdf", // suggested download name
        ),
    )

    when (result) {
        is DownloadResult.Success -> { /* the browser accepted the download */ }
        is DownloadResult.Failure -> { /* the intent could not be dispatched */ }
    }
}
```

Check `DownloadIntents.isSupported` first — it's `true` only in a browser context.

## What an intent does and doesn't give you

`dispatch` returns as soon as the browser **accepts** the intent. It deliberately does **not** provide:

- byte-level progress,
- the final filesystem path,
- checksum validation,
- custom request headers.

The browser owns the actual transfer, so those things are outside Yukitoki's control on wasm. This is
best suited to **simple GET downloads** of public resources.

## Authenticated or checksum-required downloads

For an authenticated export or an asset you must checksum, don't try to force it through a browser
intent. Instead, have your server issue a **same-origin URL or a short-lived signed URL** and let the
browser download that. The signing/auth happens server-side; the browser just fetches the ready URL.

## Supported URL schemes

On wasm, download intents accept browser-owned schemes in addition to `http`/`https` — notably
`data:` and `blob:` URLs, which are useful for downloading content your app generated in memory.

## Related

- [API reference: DownloadIntents](../reference/api.md#downloadintents)
- [Architecture and design](../explanation/architecture.md) — why wasm is a separate path.
