package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Builds the client owned by [DownloadManager.create] on the engine Yukitoki bundles for this
 * platform. The engine is always named explicitly: a bare `HttpClient {}` picks whichever
 * engine `ServiceLoader` finds first, which depends on classpath order and can differ between
 * a Gradle run and a packaged app.
 */
internal expect fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

/** Human-readable name of the bundled engine, for diagnostics and tests. */
internal expect val bundledEngineName: String
