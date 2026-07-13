# Yukitoki sample

One Compose Multiplatform UI runs on Android, JVM desktop, and iOS. The shared `DownloadViewModel` creates the manager with:

```kotlin
private val manager = DownloadManager.create()
```

There are no platform engine factories, download-path adapters, or Android initialization calls in the sample. This intentionally validates the library's default experience. Paste a URL to enqueue it, then pause, resume, cancel, retry, and adjust queue concurrency from the UI.

```bash
./gradlew :sample:composeApp:run
./gradlew :sample:androidApp:assembleDebug
./gradlew :sample:composeApp:linkDebugFrameworkIosSimulatorArm64
```
