# Tune concurrency and segmentation

Queue concurrency controls the number of active files:

```kotlin
val config = DownloadManagerConfig(
    concurrency = DownloadConcurrency.Fixed(2),
)
```

`DownloadConcurrency.Default` currently means three active downloads. It is named `Default`, not `Auto`, because it is a stable default rather than a device heuristic.

Segmentation controls parallel range requests for one file:

```kotlin
DownloadRequest(url, segmentation = DownloadSegmentation.Auto)
DownloadRequest(url, segmentation = DownloadSegmentation.Single)
DownloadRequest(url, segmentation = DownloadSegmentation.MaxSegments(4))
```

`MaxSegments` is a ceiling. Yukitoki probes range support, validates every `Content-Range`, reduces pressure after rate limiting, and can fall back to a single stream. Use `Single` for unusual servers or when predictable pause/resume behavior matters more than throughput.
