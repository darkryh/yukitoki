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

`MaxSegments` is a ceiling. Yukitoki probes range support, validates every `Content-Range`, reduces pressure after rate limiting, and can fall back to a single stream. Use `Single` for unusual servers or when predictable pause/resume behavior matters more than throughput.
