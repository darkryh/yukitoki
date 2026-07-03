# Set up Android

Yukitoki works on Android with no special setup **if you always pass an explicit destination path**.
You only need the step below when you want downloads to land in the system Downloads directory
automatically.

## When you need this

Resolving Android's default Downloads directory requires an application `Context`. If you call
`enqueue(url)` or `download(url)` **without** a `destinationPath`, the manager needs that `Context`
to know where to write.

If you always pass `destinationPath`, skip this page entirely.

## Register the application context

Register it once, early — `Application.onCreate()` is the natural place — **before** enqueuing any
download that omits `destinationPath`.

```kotlin
import android.app.Application
import io.github.darkryh.yukitoki.manager.AndroidDownloadPathContext

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidDownloadPathContext.register(applicationContext)
    }
}
```

Don't forget to point the manifest at your `Application` class:

```xml
<application
    android:name=".MyApp"
    ... >
```

`register` stores the **application** context (it calls `context.applicationContext` internally), so
there's no risk of leaking an `Activity`.

## What happens if you forget

If a download omits `destinationPath` and no context is registered, the download fails fast with a
clear [`DownloadFailure.FileSystem`](../reference/api.md#downloadfailure) message — it does not write
to an unwritable location or crash. So a missing registration surfaces as a normal, observable
download failure.

## Choosing a Ktor engine

On Android, use the OkHttp or CIO engine for your `HttpClient`:

```kotlin
// build.gradle.kts
implementation("io.ktor:ktor-client-okhttp:3.5.1")
```

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

val httpClient = HttpClient(OkHttp) {
    install(HttpTimeout) { connectTimeoutMillis = 15_000 }
}
```

## Minimum SDK

The library targets **Android `minSdk` 26**. Make sure your module's `minSdk` is 26 or higher.

## Related

- [Download inside a ViewModel + Compose](download-in-a-viewmodel.md) — the typical Android integration.
- [Getting started](../getting-started.md) — the cross-platform basics.
