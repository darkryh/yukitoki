# Getting started

This tutorial takes you from an empty project to a working download that you can watch progress in
real time. You'll create an `HttpClient`, start a `DefaultDownloadManager`, download a file, observe
its progress, and shut down cleanly.

By the end you'll have run a real download and understand the three things every Yukitoki app does:
**own an `HttpClient`, own a `CoroutineScope`, and talk to the `DownloadManager` interface.**

We'll build a small **JVM** program because it runs with a single Gradle command and needs no
emulator. The manager API is identical on every target — see the how-to guides for Android, iOS,
and wasm specifics once you've finished here.

## Prerequisites

- JDK 17 or newer.
- A Kotlin project that uses Gradle. If you don't have one, `gradle init` a Kotlin Application project.
- Basic familiarity with Kotlin coroutines (`suspend`, `runBlocking`, `CoroutineScope`).

## Step 1 — Add the dependencies

Make sure Maven Central is available, then add Yukitoki and a Ktor engine. On the JVM we'll use the
CIO engine.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.darkryh.yukitoki:download-manager:1.0.0-beta02")
    implementation("io.ktor:ktor-client-cio:3.5.1")
}
```

You don't add `ktor-client-core`, `kotlinx-coroutines-core`, or `okio` — Yukitoki exposes those
transitively.

## Step 2 — Create an HttpClient

Yukitoki never creates a client for you; you own it, configure it, and close it. Install
`HttpTimeout` so a dead connection or a server that never sends headers fails instead of hanging.

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

val httpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
    }
}
```

## Step 3 — Create the manager

`DefaultDownloadManager` needs your `httpClient` and a `CoroutineScope` whose lifetime you control.
The manager runs its work under a child `SupervisorJob` of that scope, so cancelling the scope tears
the manager down.

```kotlin
import io.github.darkryh.yukitoki.manager.DefaultDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val manager = DefaultDownloadManager(
    httpClient = httpClient,
    scope = appScope,
)
```

That's the entire setup. The manager exposes the [`DownloadManager`](reference/api.md#downloadmanager)
interface — always program against that interface, not the concrete class.

## Step 4 — Download one file

The simplest call is `download(...)`. It suspends until the download finishes and returns a
[`DownloadResult`](reference/api.md#downloadresult). Because we didn't pass a destination, the file
lands in your platform's default Downloads directory.

```kotlin
import io.github.darkryh.yukitoki.manager.DownloadResult
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val result = manager.download("https://speed.hetzner.de/1MB.bin")

    when (result) {
        is DownloadResult.Success -> println("Saved ${result.success.bytesDownloaded} bytes to ${result.success.finalPath}")
        is DownloadResult.Failure -> println("Download failed: ${result.failure}")
    }
}
```

Run it:

```bash
./gradlew run
```

Expected output (the exact path depends on your OS):

```
Saved 1048576 bytes to /Users/you/Downloads/1MB.bin
```

You just ran a real, resumable, integrity-checked download in five lines. Everything else in Yukitoki
builds on this.

## Step 5 — Watch progress on the queue

`download(...)` is a one-shot. For anything you want to show in a UI, **enqueue** it instead and
**observe** its snapshots. `enqueue(...)` returns immediately with a
[`DownloadId`](reference/api.md#downloadid); `observe(id)` gives you a `Flow` of state updates.

```kotlin
import io.github.darkryh.yukitoki.manager.DownloadState

fun main() = runBlocking {
    val id = manager.enqueue("https://speed.hetzner.de/10MB.bin")

    manager.observe(id).collect { snapshot ->
        when (val state = snapshot?.state) {
            is DownloadState.Running -> {
                val pct = state.progress.fraction?.let { (it * 100).toInt() } ?: 0
                println("… $pct%")
            }
            is DownloadState.Succeeded -> {
                println("Done: ${state.result.finalPath}")
                return@collect
            }
            is DownloadState.Failed -> {
                println("Failed: ${state.failure}")
                return@collect
            }
            else -> { /* Queued, Starting, Paused, … */ }
        }
    }
}
```

Expected output:

```
… 12%
… 34%
… 67%
… 91%
Done: /Users/you/Downloads/10MB.bin
```

`DownloadProgress.fraction` is `null` until the total size is known (some servers don't send
`Content-Length`), so always handle the `null` case — the tutorial does with the `?: 0` above.

To watch the **whole queue** instead of one item — the usual choice for a list UI — collect
`manager.state`, a `StateFlow<DownloadManagerState>` whose `snapshots` map holds every download.
See [Download inside a ViewModel + Compose](how-to/download-in-a-viewmodel.md).

## Step 6 — Shut down cleanly

When your app is done, drain the manager and close the client. `shutdown(cancelActive = false)`
lets in-flight downloads finish and finalize their files before returning.

```kotlin
manager.shutdown(cancelActive = false)
httpClient.close()
appScope.cancel()
```

In a `ViewModel`, use the non-suspending `manager.cancel()` from `onCleared()` instead — see the
[ViewModel how-to](how-to/download-in-a-viewmodel.md).

## What you learned

- You **own** the `HttpClient` and the `CoroutineScope`; Yukitoki borrows them.
- `download(...)` for a one-shot; `enqueue(...)` + `observe(...)` for anything with a UI.
- Progress arrives as `DownloadState` snapshots, and `fraction` can be `null`.
- `shutdown(...)` drains gracefully; `cancel()` tears down immediately.

## Next steps

- **Put it in an app:** [Download inside a ViewModel + Compose](how-to/download-in-a-viewmodel.md)
- **Add controls:** [Control the queue](how-to/control-the-queue.md)
- **Make it safe:** [Verify file integrity with checksums](how-to/verify-file-integrity.md)
- **Understand it:** [Architecture and design](explanation/architecture.md)
- **Look things up:** [API reference](reference/api.md)
