# Yukitoki download-manager

The published KMP library. It includes platform Ktor engines and offers a default factory as well as caller-owned dependency injection.

```kotlin
val manager = DownloadManager.create()
val task = manager.enqueue("https://example.com/file.bin")
val result = task.await()
manager.close()
```

Advanced construction:

```kotlin
val manager = DefaultDownloadManager(
    httpClient = configuredClient,
    scope = applicationScope,
)
```

`DefaultDownloadManager` borrows injected resources. `DownloadManager.create()` owns its automatically-created resources.

Destinations are explicit:

```kotlin
DownloadDestination.Default
DownloadDestination.Directory(DownloadLocations.downloads / "models")
DownloadDestination.File(exactPath)
```

See the repository [getting-started guide](../docs/getting-started.md) and [API reference](../docs/reference/api.md).
