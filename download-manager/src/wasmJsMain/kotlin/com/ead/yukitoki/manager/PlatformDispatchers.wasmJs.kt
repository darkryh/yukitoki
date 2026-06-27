package com.ead.yukitoki.manager

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Wasm/JS has no dedicated IO dispatcher; the browser build uses download intents
// rather than the filesystem-backed manager, so Default is only a compile-time default.
internal actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.Default
