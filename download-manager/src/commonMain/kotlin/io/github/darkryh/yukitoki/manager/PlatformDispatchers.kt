package io.github.darkryh.yukitoki.manager

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for blocking filesystem work (okio sinks/sources, atomic moves, metadata, directory
 * creation). The coordinator and workers both run here, so passing a UI-confined scope (e.g.
 * `viewModelScope`) never blocks the main thread.
 */
internal expect val platformIoDispatcher: CoroutineDispatcher
