package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js

// DownloadManager.create() rejects wasm before a client is built (no managed filesystem);
// this actual exists only to satisfy the expect declaration.
internal actual fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Js) { configure() }

internal actual val bundledEngineName: String = "Js"
