# Download in a ViewModel

This uses `ViewModel` from `androidx.lifecycle:lifecycle-viewmodel`, which your project needs as its own dependency.

```kotlin
class DownloadsViewModel : ViewModel() {
    private val manager = DownloadManager.create()
    val downloads = manager.state

    fun enqueue(url: String) = viewModelScope.launch {
        manager.enqueue(url)
    }

    fun pause(id: DownloadId) = viewModelScope.launch { manager.pause(id) }
    fun resume(id: DownloadId) = viewModelScope.launch { manager.resume(id) }
    fun cancel(id: DownloadId) = viewModelScope.launch { manager.cancel(id) }

    override fun onCleared() {
        manager.close()
    }
}
```

`close()` is immediate and non-suspending. `manager.state` stops updating after it, which is fine here because the ViewModel is going away. If the manager should outlive the ViewModel, create it in an application-level owner and inject it instead.

Yukitoki remains in-process; a ViewModel does not make transfers persistent across process death.
