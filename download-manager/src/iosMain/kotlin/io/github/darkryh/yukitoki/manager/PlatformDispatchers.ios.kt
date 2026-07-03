package io.github.darkryh.yukitoki.manager

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Kotlin/Native exposes no public Dispatchers.IO; Default is a multi-threaded pool off
// the main thread, which is what matters for keeping UI-confined callers responsive.
internal actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.Default
