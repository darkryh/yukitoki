# Control the queue

`enqueue` returns a task handle:

```kotlin
val task = manager.enqueue(url)
task.pause()
task.resume()
task.cancel()
task.retry()
```

ID-based commands are also available for central queue UIs. They return `CommandResult.Accepted` or `CommandResult.Rejected`.

```kotlin
manager.pause(id)
manager.resume(id)
manager.cancel(id)
manager.retry(id)

manager.pauseAll()
manager.resumeAll()
manager.cancelAll()
manager.retryAllFailed()
```

Priorities are strict tiers (`HIGH`, `NORMAL`, `LOW`). Reordering is intentionally tier-local:

```kotlin
manager.moveWithinPriority(id, newPosition = 0)
```

Change active-download concurrency at runtime:

```kotlin
manager.setConcurrency(DownloadConcurrency.Fixed(2))
manager.setConcurrency(DownloadConcurrency.Default)
```

Finished snapshots are bounded by `maxRetainedFinished`. Remove them explicitly with `remove(id, deleteFile)` or `clearFinished()`.
