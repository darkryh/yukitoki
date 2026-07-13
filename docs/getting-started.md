# Getting started

Add the library to the source set where downloads are used:

```kotlin
commonMain.dependencies {
    implementation("io.github.darkryh.yukitoki:download-manager:<version>")
}
```

Create a manager in common code:

```kotlin
val manager = DownloadManager.create()
```

This factory bundles/selects the platform Ktor engine, creates its own scope, and uses the platform filesystem. Android initialization is automatic.

Enqueue and observe a task:

```kotlin
val task = manager.enqueue("https://example.com/archive.zip")

task.state.collect { snapshot ->
    when (val state = snapshot?.state) {
        is DownloadState.Running -> println(state.progress.fraction)
        else -> println(state)
    }
}
```

Await completion:

```kotlin
when (val result = task.await()) {
    is DownloadResult.Success -> println(result.success.finalPath)
    is DownloadResult.Failure -> println(result.failure)
    DownloadResult.Cancelled -> println("cancelled")
}
```

Choose a destination only when needed:

```kotlin
val request = DownloadRequest(
    url = "https://example.com/model.bin",
    destination = DownloadDestination.Directory(
        path = DownloadLocations.downloads / "models",
        fileName = "speech-model.bin",
    ),
    expectedChecksum = ChecksumSpec(
        ChecksumAlgorithm.SHA_256,
        expectedHex,
    ),
)

manager.enqueue(request)
```

Use an exact path with `DownloadDestination.File(path)`. `OverwritePolicy.DEFAULT` renames collisions for default/directory destinations and fails on collision for an exact file, so ordinary downloads never silently replace data.

## Custom Ktor client

```kotlin
val client = HttpClient(OkHttp) {
    // engine-specific plugins and configuration
}

val manager = DownloadManager.create(client, applicationScope)
```

The injected client and scope remain caller-owned. Close them yourself after `manager.shutdown()` or `manager.close()`.

## Lifecycle

```kotlin
manager.close()                         // immediate, non-suspending
manager.shutdown(cancelActive = true)  // suspending cancellation
manager.shutdown(cancelActive = false) // drain active transfers
```

Yukitoki is an in-process manager. Its queue is not restored after process death or reboot.
