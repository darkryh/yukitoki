# Download inside a ViewModel + Compose

This guide wires the download manager into a `ViewModel` and renders the queue with Compose. It's the
standard shape for an Android or Compose Multiplatform app.

It assumes you've read [Getting started](../getting-started.md) and have a `DownloadManager` instance.

## Provide the manager to the ViewModel

Create one manager for the app (via your DI container, or a simple singleton) and inject the
`DownloadManager` interface — never the concrete class — into the ViewModel.

```kotlin
class DownloadsViewModel(
    private val manager: DownloadManager,
) : ViewModel() {

    // Re-expose the manager's queue-wide state, scoped to the ViewModel.
    val state: StateFlow<DownloadManagerState> = manager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.state.value)

    fun enqueue(url: String) = viewModelScope.launch {
        manager.enqueue(url)
    }

    fun pause(id: DownloadId) = viewModelScope.launch { manager.pause(id) }
    fun resume(id: DownloadId) = viewModelScope.launch { manager.resume(id) }
    fun cancel(id: DownloadId) = viewModelScope.launch { manager.cancel(id) }
    fun retry(id: DownloadId) = viewModelScope.launch { manager.retry(id) }

    // Non-suspending teardown — onCleared() is not a coroutine.
    override fun onCleared() = manager.cancel()
}
```

Two things make this safe:

- The manager's coordinator runs on the dispatcher of the scope you gave `DefaultDownloadManager`,
  and it never touches the filesystem — so driving it from `viewModelScope` (Main) is fine.
- File I/O runs on an I/O dispatcher inside the manager, so `enqueue`/`pause`/… never block the UI.

Use `manager.cancel()` (not `shutdown`) in `onCleared()` because it doesn't suspend. If the same
manager outlives the screen (app-scoped), don't tear it down here at all — cancel it when the app
process is done instead.

## Render the whole queue

Collect the queue state and list every download. `state.snapshots` is a
`Map<DownloadId, DownloadSnapshot>`.

```kotlin
@Composable
fun DownloadsScreen(vm: DownloadsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    LazyColumn {
        items(state.snapshots.values.toList(), key = { it.id.value }) { snap ->
            DownloadRow(
                snapshot = snap,
                onPause = { vm.pause(snap.id) },
                onResume = { vm.resume(snap.id) },
                onCancel = { vm.cancel(snap.id) },
                onRetry = { vm.retry(snap.id) },
            )
        }
    }
}

@Composable
fun DownloadRow(
    snapshot: DownloadSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    when (val state = snapshot.state) {
        is DownloadState.Running -> {
            LinearProgressIndicator(progress = { (state.progress.fraction ?: 0.0).toFloat() })
            Text("${state.progress.bytesDownloaded} bytes")
            Button(onClick = onPause) { Text("Pause") }
        }
        DownloadState.Paused -> Button(onClick = onResume) { Text("Resume") }
        is DownloadState.Failed -> {
            Text("Failed: ${state.failure}")
            Button(onClick = onRetry) { Text("Retry") }
        }
        is DownloadState.Succeeded -> Text("Saved to ${state.result.finalPath}")
        else -> Text(state.toString()) // Queued, Starting, Pausing, Cancelling, Cancelled
    }
}
```

`collectAsStateWithLifecycle` comes from
`androidx.lifecycle:lifecycle-runtime-compose` (Android) or the corresponding Compose Multiplatform
artifact. On plain desktop Compose, use `collectAsState()` instead.

## Render a single item (detail screen)

For a detail screen, observing one item recomposes far less than collecting the whole queue.

```kotlin
@Composable
fun DownloadDetail(manager: DownloadManager, id: DownloadId) {
    val snapshot: DownloadSnapshot? by manager
        .observe(id)
        .collectAsStateWithLifecycle(initialValue = null)

    val progress = (snapshot?.state as? DownloadState.Running)?.progress
    Text("Downloaded ${progress?.bytesDownloaded ?: 0} bytes")
}
```

## Enqueue with options

The `enqueue(...)` convenience overload takes the common per-request options directly, so you rarely
need to build a `DownloadRequest` by hand:

```kotlin
fun enqueueHighPriority(url: String) = viewModelScope.launch {
    manager.enqueue(
        url = url,
        priority = DownloadPriority.HIGH,
        overwritePolicy = OverwritePolicy.RENAME_UNIQUE,
    )
}
```

## Related

- [Control the queue](control-the-queue.md) — the full command set.
- [Handle failures and retries](handle-failures-and-retries.md) — reacting to `DownloadState.Failed`.
- [Set up Android](set-up-android.md) — required if you enqueue without a destination path.
