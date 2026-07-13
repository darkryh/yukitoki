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
