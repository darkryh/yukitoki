package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.java.Java

internal actual fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Java) { configure() }

internal actual val bundledEngineName: String = "Java"
