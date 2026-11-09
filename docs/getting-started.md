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

This factory builds its own Ktor client on the engine Yukitoki bundles for the platform (Java on JVM desktop, OkHttp on Android, Darwin on iOS), creates its own scope, and uses the platform filesystem. Android initialization is automatic. The engine is named explicitly, so the result never depends on which engine happens to be first on your classpath.

To customize the owned client, pass `configureClient`. It runs after Yukitoki's defaults (an `HttpTimeout` with no total request timeout and a 30-second connect timeout), so it can override them:

```kotlin
val manager = DownloadManager.create {
    install(DefaultRequest) { headers.append("User-Agent", "MyApp/1.0") }
}
```

Enqueue a task, observe it, and await the result. `task.state` never completes, so code after a bare `collect` never runs. Collect it in its own coroutine:

```kotlin
val task = manager.enqueue("https://example.com/archive.zip")

coroutineScope {
    val progress = launch {
        task.state.collect { snapshot ->
            when (val state = snapshot?.state) {
                is DownloadState.Running -> println(state.progress.fraction)
                else -> println(state)
            }
        }
    }

    when (val result = task.await()) {
        is DownloadResult.Success -> println(result.file.path)
        is DownloadResult.Failure -> println(result.failure)
        DownloadResult.Cancelled -> println("cancelled")
    }

    progress.cancel()
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

Yukitoki's bundled engines are not on your compile classpath, so add the engine you name, on the Ktor version Yukitoki uses (`3.6.1` for `1.0.0-beta08`). OkHttp is Android and JVM only, so this code belongs in that platform's source set:

```kotlin
androidMain.dependencies {
    implementation("io.ktor:ktor-client-okhttp:3.6.1")
}
```

```kotlin
val client = HttpClient(OkHttp) {
    // engine-specific plugins and configuration
}

val manager = DownloadManager.create(client, applicationScope)
```

The injected client and scope remain caller-owned. Close them yourself after `manager.shutdown()` or `manager.close()`.

Download requests opt out of the client's total request timeout, so a short `requestTimeoutMillis`, or the CIO engine's built-in 15-second limit, cannot abort a long transfer. Connect and socket timeouts you configure still apply.

## Lifecycle

```kotlin
manager.close()                         // immediate, non-suspending
manager.shutdown(cancelActive = true)  // suspending cancellation
manager.shutdown(cancelActive = false) // drain active transfers
```

Use one of these. Whichever you pick, every unfinished download ends as `Cancelled` in `state`, its partial file is removed, and `await` keeps working: a saved file still returns `Success`.

Yukitoki is an in-process manager. Its queue is not restored after process death or reboot.
