# Yukitoki Sample (Compose Multiplatform)

A single shared Compose UI driving the Yukitoki download manager on **Android, iOS and
desktop**. The UI, the `DownloadViewModel`, and the manager wiring all live in
`composeApp/src/commonMain`; only the Ktor engine and the downloads directory differ per
platform (`DownloadEnvironment`).

```
sample/
  composeApp/
    src/commonMain   App.kt (UI) · DownloadViewModel · DownloadEnvironment (interface)
    src/androidMain  AndroidDownloadEnvironment (OkHttp) · MainActivity · AndroidManifest
    src/desktopMain  DesktopDownloadEnvironment (OkHttp) · main()
    src/iosMain      IosDownloadEnvironment (Darwin) · MainViewController()
  iosApp/            SwiftUI entry that hosts MainViewController() from the framework
```

The screen lets you paste a URL, enqueue it (normal or high priority), watch live progress,
adjust concurrency, and pause / resume / cancel / retry each item — exercising the manager's
public API exactly as a real app would.

## Run

**Desktop**
```
./gradlew :sample:composeApp:run
```

**Android** — install the debug APK on a device/emulator:
```
./gradlew :sample:composeApp:installDebug
```
(or open the project in Android Studio and run the `composeApp` Android configuration.)

**iOS** — the Kotlin side builds the `ComposeApp` framework and `iosApp/` contains the
SwiftUI entry (`iOSApp.swift`) that hosts it. Open an Xcode project that links the framework
(the standard Kotlin Multiplatform / Compose layout) and run on a simulator or device. To
produce the framework directly:
```
./gradlew :sample:composeApp:linkDebugFrameworkIosSimulatorArm64
```

## Note on the Android application target (AGP 9)

`composeApp` applies `com.android.application` together with the Kotlin Multiplatform plugin.
Under AGP 9 that combination is supported only with
`android.builtInKotlin=false` / `android.newDsl=false` (set in the root `gradle.properties`)
and is **deprecated** — AGP 10 will require moving the Android *application* into its own
subproject that depends on a KMP library module. The shared code here is already structured
so that migration is mechanical when you upgrade.
