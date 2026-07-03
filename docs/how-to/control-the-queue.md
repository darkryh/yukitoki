# Control the queue: pause, resume, cancel, retry, reorder

This guide covers the command API — how to steer downloads after you've enqueued them. Every command
suspends and returns a [`CommandAck`](../reference/api.md#commandack) telling you whether the manager
accepted it.

It assumes you have a `DownloadManager` and a `DownloadId` from `enqueue(...)`.

## Read the acknowledgement

Commands don't throw for a rejected request; they return `CommandAck.Rejected` with a reason (for
example, the download already finished, or the manager was shut down).

```kotlin
when (val ack = manager.pause(id)) {
    is CommandAck.Accepted -> { /* the command was applied */ }
    is CommandAck.Rejected -> log("Couldn't pause: ${ack.reason}")
}
```

If you don't need the reason, you can ignore the return value.

## Pause and resume

```kotlin
manager.pause(id)   // moves a running download toward Paused; the .part file is kept
manager.resume(id)  // continues a paused download, resuming from the .part file over HTTP Range
```

Pause is designed around the **single-stream** resume path: the partial `.part` file is preserved and
the resume uses HTTP `Range` (validated with `If-Range`) so bytes already on disk aren't re-fetched.
Segmented downloads keep their slices as internal temp files that are cleaned on pause; treat
pause/resume as best-effort for segmented transfers and reliable for single-stream ones. See
[Tune concurrency and segmentation](tune-concurrency-and-segmentation.md).

## Cancel

```kotlin
manager.cancel(id)  // stops the download; the .part file follows deletePartialOnCancel
```

Whether the partial file is deleted is governed by `DownloadManagerConfig.deletePartialOnCancel`
(default `true`). A cancelled download ends in `DownloadState.Cancelled`.

> `manager.cancel(id)` cancels **one** download. The no-argument `manager.cancel()` is a different
> method — it tears down the entire manager and is meant for `ViewModel.onCleared()`. Don't confuse them.

## Retry a failed download

```kotlin
manager.retry(id)  // re-queues a download that ended in Failed
```

This is a manual retry for a download that already exhausted its automatic `RetryPolicy` and landed
in `DownloadState.Failed`. Automatic retries (backoff + jitter for transient errors) happen inside a
single attempt without you calling anything — see [Handle failures and retries](handle-failures-and-retries.md).

## Reorder the queue

Move a pending download to a different position. Position `0` is the front of the queue.

```kotlin
manager.move(id, newPosition = 0)  // jump this download to the front
```

Reordering only affects **pending** downloads; an already-running download keeps running. Priority
(`DownloadPriority`) still applies — moving within the queue reorders items of the same effective
priority band.

## Act on the whole queue

Each single-item command has a bulk counterpart:

```kotlin
manager.pauseAll()
manager.resumeAll()
manager.cancelAll()
manager.retryAllFailed()
```

`retryAllFailed()` re-queues every download currently in `Failed`; the others apply to whatever is in
a state that can accept the command.

## Change concurrency at runtime

```kotlin
manager.setConcurrency(DownloadConcurrency.Fixed(2))  // at most 2 active downloads
manager.setConcurrency(DownloadConcurrency.Auto)      // back to the manager's automatic choice
```

Lowering concurrency doesn't kill running downloads; it stops new ones from starting until the active
count drops to the new limit. See [Tune concurrency and segmentation](tune-concurrency-and-segmentation.md).

## After shutdown

Once you call `shutdown(...)` or `cancel()`, every command returns `CommandAck.Rejected` and
`await(id)` returns `null` instead of hanging. This lets teardown be race-free — late commands are
rejected cleanly rather than throwing.

## Related

- [Handle failures and retries](handle-failures-and-retries.md)
- [Download inside a ViewModel + Compose](download-in-a-viewmodel.md)
- [API reference: DownloadManager](../reference/api.md#downloadmanager)
