# Yukitoki

Yukitoki is an in-process Kotlin Multiplatform download manager for Android, JVM desktop, and iOS. It provides streaming downloads, observable tasks, pause/resume, retry, priorities, bounded concurrency, segmented ranges, checksums, and safe file finalization.

The browser target exposes download intents because browsers do not expose an ordinary writable Downloads filesystem.

Current development version: `1.0.0-SNAPSHOT`.

## Install

The last published preview is:

```kotlin
implementation("io.github.darkryh.yukitoki:download-manager:1.0.0-beta04")
```

The pre-1.0 API documented below is currently available from this repository. The artifact bundles an appropriate Ktor engine for Android, JVM, and iOS.

## Basic use

```kotlin
val manager = DownloadManager.create()

val task = manager.enqueue("https://example.com/report.pdf")
task.state.collect { snapshot -> println(snapshot?.state) }

when (val result = task.await()) {
    is DownloadResult.Success -> println("Saved to ${result.success.finalPath}")
    is DownloadResult.Failure -> println("Failed: ${result.failure}")
    DownloadResult.Cancelled -> println("Cancelled")
}

manager.close()
```

No platform `expect`/`actual`, Ktor engine, coroutine scope, Android initialization, or explicit path is required for the default case.

## Destinations

```kotlin
val defaultDirectory = DownloadLocations.downloads

manager.enqueue(
    DownloadRequest(
        url = "https://example.com/model.bin",
        destination = DownloadDestination.Directory(
            path = DownloadLocations.downloads / "models",
        ),
    )
)
```

`DownloadLocations.downloads` is writable and platform appropriate, but can be app-sandboxed. On Android it is app-specific storage; writing to the shared public Downloads collection requires an Android MediaStore/URI workflow.

## Advanced networking

Inject a caller-owned client and scope when engine-specific configuration is required:

```kotlin
val manager = DownloadManager.create(
    httpClient = myHttpClient,
    scope = applicationScope,
)
```

Injected resources remain caller-owned. A manager created with the no-argument factory owns and closes its client and scope.

Downloads never inherit a total request timeout: every download request opts out of one, including the CIO engine's built-in 15-second default, so a transfer can run as long as bytes keep arriving. Your client's connect and socket timeouts still apply, and `DownloadManagerConfig.idleTimeout` fails a download whose server stops responding.

## Platform scope

- Android: automatic application-context initialization and bundled OkHttp engine.
- JVM desktop: bundled Java (JDK `HttpClient`) engine and Linux XDG Downloads-directory support.
- iOS device and Intel/Apple Silicon simulators: bundled Darwin engine and sandboxed `Documents/Downloads`.
- Wasm browser: `DownloadIntents`; managed filesystem downloads are unsupported.

Yukitoki does not currently persist its queue across process death or reboot and does not start an Android foreground/background service. Schedule it through platform background infrastructure when that behavior is required.

The API is Kotlin-first. The iOS artifact is intended for shared Kotlin/Compose code; a separate Swift-native façade is not part of the 1.0 contract.

## Documentation

- [Getting started](docs/getting-started.md)
- [API reference](docs/reference/api.md)
- [Architecture](docs/explanation/architecture.md)
- [How-to guides](docs/README.md)
- [Sample app](sample/README.md)

## Validation

The project uses Kotlin explicit API mode and built-in ABI validation. Run:

```bash
./gradlew :download-manager:allTests :download-manager:build :download-manager:checkKotlinAbi
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
