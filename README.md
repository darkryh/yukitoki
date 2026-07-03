# Yukitoki

A generic **Kotlin Multiplatform download manager** for streaming file downloads with progress,
a parallel queue, pause/resume, retries, segmented range downloads, checksums, and file safety.

Built for coroutines — it drops straight into a `ViewModel` and Compose / Compose Multiplatform.

**Current version:** `1.0.0-beta01` · [changelog](CHANGELOG.md)

**Targets:** JVM · Android · iOS (arm64 + simulator arm64) · wasmJs (browser)

---

## Why Yukitoki

- **One API, every target.** Write the download logic once in `commonMain`; only the Ktor engine
  and the default downloads directory differ per platform.
- **Safe to drive from the UI thread.** An actor-based coordinator owns all queue state, and every
  blocking file read/write is offloaded to an I/O dispatcher — so `viewModelScope` (Main) never blocks.
- **Resilient by default.** Retries with exponential backoff + jitter, HTTP resume (`Range` / `If-Range`),
  a stall/idle timeout, and rate-limit (`429`) backpressure are built in.
- **Fast when it's safe.** Large files use segmented parallel range workers — but only when the server
  proves it supports byte ranges. Everything else falls back to a validated single stream.
- **Correct files or none.** Downloads stream into a `.part` temp file and are atomically moved into
  place only after size and optional MD5 / SHA-256 checks pass.

## Install

Yukitoki is published to Maven Central.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google() // for the Android target
    }
}
```

```kotlin
// build.gradle.kts — in your KMP module's commonMain (or a JVM/Android module)
dependencies {
    implementation("io.github.darkryh.yukitoki:download-manager:1.0.0-beta01")
}
```

You also provide a **Ktor `HttpClient`** with an engine for each target — Yukitoki does not pick one for you:

| Target  | Suggested engine        | Artifact                          |
|---------|-------------------------|-----------------------------------|
| Android | OkHttp (or CIO)         | `io.ktor:ktor-client-okhttp`      |
| JVM     | CIO (or OkHttp)         | `io.ktor:ktor-client-cio`         |
| iOS     | Darwin                  | `io.ktor:ktor-client-darwin`      |
| wasmJs  | Js                      | `io.ktor:ktor-client-js`          |

`ktor-client-core`, `kotlinx-coroutines-core`, and `okio` come transitively as `api` dependencies.

## 30-second example

```kotlin
val manager = DefaultDownloadManager(
    httpClient = httpClient, // you own and configure it
    scope = appScope,        // you own the lifecycle
)

// Fire-and-await a single download:
val result = manager.download("https://example.com/report.pdf")
when (result) {
    is DownloadResult.Success -> println("Saved to ${result.success.finalPath}")
    is DownloadResult.Failure -> println("Failed: ${result.failure}")
}

// Or enqueue onto the parallel queue and observe progress:
val id = manager.enqueue("https://example.com/video.mp4")
manager.observe(id).collect { snapshot -> render(snapshot?.state) }
```

If you omit the destination path, the file lands in the platform's default Downloads directory
(Android needs [one line of setup](docs/how-to/set-up-android.md)).

## Documentation

| I want to…                                   | Read                                                                 |
|----------------------------------------------|----------------------------------------------------------------------|
| Get my first download working, step by step  | [Getting started](docs/getting-started.md) — *tutorial*              |
| Solve a specific task                         | [How-to guides](docs/how-to/) — *recipes*                            |
| Look up a type, function, or parameter        | [API reference](docs/reference/api.md) — *facts*                     |
| Understand how it works and why               | [Architecture](docs/explanation/architecture.md) — *explanation*     |

**Popular how-to guides**

- [Download inside a ViewModel + Compose](docs/how-to/download-in-a-viewmodel.md)
- [Control the queue: pause, resume, cancel, retry, reorder](docs/how-to/control-the-queue.md)
- [Verify file integrity with checksums](docs/how-to/verify-file-integrity.md)
- [Tune concurrency and segmentation](docs/how-to/tune-concurrency-and-segmentation.md)
- [Handle failures and retries](docs/how-to/handle-failures-and-retries.md)
- [Set up Android](docs/how-to/set-up-android.md)
- [Download on wasm / in the browser](docs/how-to/download-on-wasm.md)

## Sample app

A single shared Compose UI drives the manager on Android, iOS, and desktop. See
[`sample/`](sample/README.md) — paste a URL, watch live progress, adjust concurrency, and
pause / resume / cancel / retry each item.

```bash
./gradlew :sample:composeApp:run          # desktop
./gradlew :sample:composeApp:installDebug # Android
```

## Compatibility

| Component            | Version        |
|----------------------|----------------|
| Kotlin               | 2.4.0          |
| Ktor client          | 3.5.x          |
| Okio                 | 3.17.x         |
| kotlinx-coroutines   | 1.11.x         |
| Android `minSdk`     | 26             |

## License

Apache License 2.0 — see [LICENSE](LICENSE).
