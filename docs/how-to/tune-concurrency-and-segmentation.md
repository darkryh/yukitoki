# Tune concurrency and segmentation

Queue concurrency controls the number of active files:

```kotlin
val config = DownloadManagerConfig(
    maxConcurrentDownloads = 2,
)
```

The default is three active downloads. Change it at runtime with `manager.setMaxConcurrentDownloads(n)`.

Segmentation controls parallel range requests for one file:

```kotlin
DownloadRequest(url, segmentation = DownloadSegmentation.Auto)
DownloadRequest(url, segmentation = DownloadSegmentation.Single)
DownloadRequest(url, segmentation = DownloadSegmentation.MaxSegments(4))
```

`Auto` uses one stream below 16 MiB and over HTTP/2, where segments would share one connection anyway. On iOS, Ktor's Darwin engine doesn't report the HTTP version, so `Auto` can use segments over HTTP/2. That's safe, but no faster. Otherwise it uses two segments below 128 MiB and four above. Each origin allows four parallel requests, shared across downloads. An explicit `MaxSegments` is honored over HTTP/2.

`MaxSegments` is a ceiling. Yukitoki probes range support, validates every `Content-Range`, reduces pressure after rate limiting, and can fall back to a single stream. Retries and resumes continue each segment from where it stopped, as long as the server gave a validator. Use `Single` for unusual servers.
