# Tune concurrency and segmentation

Yukitoki has two independent parallelism dials. This guide explains what each one does and when to
change it from the default. Most apps should leave both on `Auto`.

| Dial | Scope | Controlled by | Default |
|------|-------|---------------|---------|
| **Concurrency** | How many downloads run at once | `DownloadManagerConfig.concurrency` | `Auto` |
| **Segmentation** | How many parallel connections one file uses | `DownloadRequest.segmentation` | `Auto` |

## Concurrency — how many downloads at once

This bounds the number of **active downloads** across the whole queue. Everything else waits in the
queue in priority order.

Set it when you construct the manager:

```kotlin
val manager = DefaultDownloadManager(
    httpClient = httpClient,
    scope = appScope,
    config = DownloadManagerConfig(
        concurrency = DownloadConcurrency.Fixed(3), // at most 3 downloads run simultaneously
    ),
)
```

or change it at runtime:

```kotlin
manager.setConcurrency(DownloadConcurrency.Fixed(1)) // e.g. on a metered connection
manager.setConcurrency(DownloadConcurrency.Auto)
```

- Use `DownloadConcurrency.Auto` (the default) unless you have a specific reason.
- Use `Fixed(n)` when a product rule requires it — a hard cap on parallel downloads, a "download one
  at a time" setting, or throttling on a metered network.

Lowering the limit never cancels a running download; it just stops new ones from starting until the
active count falls to the new limit.

## Segmentation — how many connections per file

Segmentation splits **one** file into byte-range slices fetched in parallel, which can dramatically
speed up a large file on a fast link. It's set per request:

```kotlin
import io.github.darkryh.yukitoki.manager.DownloadSegmentation

// Auto (default): the manager decides per file.
manager.enqueue(url = bigFileUrl, segmentation = DownloadSegmentation.Auto)

// Cap the number of range workers for this file.
manager.enqueue(url = bigFileUrl, segmentation = DownloadSegmentation.Fixed(4))

// Force a single connection — safest for unusual or flaky servers.
manager.enqueue(url = oddServerUrl, segmentation = DownloadSegmentation.SingleStream)
```

What each option means:

- **`Auto`** — small files stay single-stream; larger files use bounded parallel ranges, but **only
  after the server proves** it supports byte ranges. If it can't be proven, the manager silently
  falls back to a single validated stream.
- **`Fixed(n)`** — a **maximum** segment count, not a demand. The manager may still use fewer segments,
  or fall back to single-stream, if the server pushes back (e.g. rate-limits). `n` must be ≥ 1.
- **`SingleStream`** — always one connection. The most compatible option, and the one with the most
  reliable pause/resume (see below).

Segmentation is **GET-only**. The manager probes with `HEAD`, falls back to a tiny
`GET Range: bytes=0-0` probe if `HEAD` is broken, then requests isolated slices with
`Accept-Encoding: identity`. Slices are validated (`206 Partial Content`, `Content-Range`, byte
counts) and merged into the normal `.part` file before checksum and atomic finalization.

## How the two interact under rate limits

When a host returns `429 Too Many Requests`, the manager treats it as backpressure: it honors a
numeric `Retry-After`, reduces per-origin request pressure, retries segmented downloads with fewer
segments, and can drop a file to single-stream. You don't need to configure this — but it's why
`Fixed(n)` is a ceiling, not a guarantee.

## Segmentation and pause/resume

Pause/resume is most reliable on the **single-stream** path: the `.part` file is preserved and resumed
with HTTP `Range`. Segmented downloads keep their slices as internal temp files that are cleaned on
pause. If reliable pause/resume matters more than raw speed for a given file, prefer
`DownloadSegmentation.SingleStream`.

## Related

- [Control the queue](control-the-queue.md) — `setConcurrency`, `move`, priorities.
- [Architecture and design](../explanation/architecture.md) — why segmentation is conservative.
- [API reference: DownloadConcurrency, DownloadSegmentation](../reference/api.md#downloadconcurrency)
