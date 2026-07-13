# Android setup

No manual setup is required:

```kotlin
val manager = DownloadManager.create()
val task = manager.enqueue("https://example.com/file.bin")
```

The library manifest contributes the Internet permission and a non-exported initializer that stores only the application context. The default destination is the app-specific external Downloads directory, falling back to internal files storage when unavailable.

This location is scoped and writable without storage permission, but it is removed when the app is uninstalled. It is not Android's shared public Downloads collection. Use an Android MediaStore or document-URI flow when the user must own a file outside the app sandbox.

Yukitoki runs in-process. Use WorkManager or an Android service around it when execution must survive UI teardown or continue under background restrictions.
