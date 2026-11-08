# Failures and retries

Runtime transfer failures are typed:

```kotlin
when (val result = task.await()) {
    is DownloadResult.Success -> use(result.file.path)
    DownloadResult.Cancelled -> Unit
    is DownloadResult.Failure -> show(result.failure)
}
```

Configure retries with Kotlin durations:

```kotlin
val policy = RetryPolicy(
    maxAttempts = 4,
    initialDelay = 1.seconds,
    maxDelay = 30.seconds,
    multiplier = 2.0,
    jitterRatio = 0.2,
)
```

Use `RetryPolicy.Default` or `RetryPolicy.None` for common cases. Yukitoki retries network failures, timeouts, and configured HTTP 5xx/408/429 responses. Numeric `Retry-After` is honored.

The independent stream-stall guard is configured globally:

```kotlin
DownloadManager.create(
    DownloadManagerConfig(idleTimeout = 15.seconds)
)
```

`Duration.ZERO` disables the idle timeout. Invalid request values throw during construction. Public `enqueue` throws `DownloadRejectedException` when a task cannot be accepted; one-shot `download` returns the typed failure.
