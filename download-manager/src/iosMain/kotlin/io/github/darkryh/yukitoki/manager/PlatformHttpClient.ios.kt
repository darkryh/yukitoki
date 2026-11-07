package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

internal actual fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) { configure() }

internal actual val bundledEngineName: String = "Darwin"
