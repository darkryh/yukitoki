package io.github.darkryh.yukitoki.manager

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher used for blocking filesystem work (okio sinks/sources, atomic moves,
 * metadata, directory creation). The coordinator never touches the filesystem, so it
 * can stay on the caller's dispatcher; workers are offloaded here so that passing a
 * UI-confined scope (e.g. `viewModelScope`) never blocks the main thread.
 */
internal expect val platformIoDispatcher: CoroutineDispatcher
