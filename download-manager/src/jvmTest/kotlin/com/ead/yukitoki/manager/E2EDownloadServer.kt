package com.ead.yukitoki.manager

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real embedded HTTP server (Ktor CIO) used by the end-to-end suite. Unlike the
 * MockEngine-based common tests, every request here crosses real sockets, so the manager
 * exercises its true resume/Range/segment/timeout behavior against an actual server.
 *
 * Routes:
 *  - `GET|HEAD /file`            full Range + HEAD + ETag/Last-Modified support.
 *  - `GET|HEAD /norange`         serves the body but advertises no `Accept-Ranges` and
 *                                ignores `Range` (forces single-stream / segment fallback).
 *  - `GET /throttled`           Range-aware, streams slowly so a download can be paused.
 *  - `GET /flaky/{key}?fail=N`   responds `503` the first N times for {key}, then `200`.
 *  - `GET /redirect`            `302` to `/file`.
 *  - `GET /notfound`            `404`.
 *  - `GET /stall`               emits a few bytes then hangs forever (idle-timeout bait).
 *  - `GET /changedetag`         answers a ranged request `206` but with a *different* ETag
 *                                than HEAD advertised (trips the ServerChanged guard).
 */
class E2EDownloadServer(payloadSize: Int = 512 * 1024) {
    /** Deterministic payload so any byte slice can be content-verified. */
    val payload: ByteArray = ByteArray(payloadSize) { (it % 256).toByte() }
    val etag: String = "\"v1-${payload.size}\""
    val lastModified: String = "Wed, 21 Oct 2026 07:28:00 GMT"

    private val flakyHits = ConcurrentHashMap<String, AtomicInteger>()
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    var port: Int = 0
        private set

    fun start() {
        val started = embeddedServer(CIO, port = 0) { module() }.start(wait = false)
        server = started
        port = runBlocking { started.engine.resolvedConnectors().first().port }
    }

    fun stop() {
        server?.stop(100, 500)
        server = null
    }

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    private fun Application.module() {
        routing {
            head("/file") { call.serveHead(supportRange = true) }
            get("/file") { call.serveBody(supportRange = true) }

            head("/norange") { call.serveHead(supportRange = false) }
            get("/norange") { call.serveBody(supportRange = false) }

            get("/throttled") { call.serveBody(supportRange = true, throttle = true) }

            get("/flaky/{key}") {
                val key = call.parameters["key"] ?: "default"
                val failTimes = call.request.queryParameters["fail"]?.toIntOrNull() ?: 1
                val hit = flakyHits.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
                if (hit <= failTimes) {
                    call.respond(HttpStatusCode.ServiceUnavailable, "flaky failure $hit/$failTimes")
                } else {
                    call.serveBody(supportRange = true)
                }
            }

            get("/redirect") {
                call.response.headers.append(HttpHeaders.Location, "/file")
                call.respond(HttpStatusCode.Found, "")
            }

            get("/notfound") { call.respond(HttpStatusCode.NotFound, "nope") }

            get("/stall") {
                call.respondBytesWriter(status = HttpStatusCode.OK) {
                    writeFully(payload.copyOfRange(0, 64))
                    flush()
                    // Never close: the manager's idle-timeout watchdog must fire.
                    delay(120_000)
                }
            }

            get("/changedetag") {
                val range = call.request.headers[HttpHeaders.Range]
                if (range == null) {
                    // Stream the first (full) attempt slowly so a pause can land before
                    // the resume; the resume's ranged request gets the mutated ETag below.
                    call.serveBody(supportRange = true, throttle = true)
                } else {
                    val (start, end) = parseRange(range, payload.size)
                    call.response.headers.append(HttpHeaders.ETag, "\"mutated\"")
                    call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-$end/${payload.size}")
                    call.respondBytes(payload.copyOfRange(start, end + 1), status = HttpStatusCode.PartialContent)
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.serveHead(supportRange: Boolean) {
        respond(object : OutgoingContent.NoContent() {
            override val status = HttpStatusCode.OK
            override val headers: Headers = Headers.build {
                append(HttpHeaders.ContentLength, payload.size.toString())
                if (supportRange) append(HttpHeaders.AcceptRanges, "bytes")
                append(HttpHeaders.ETag, etag)
                append(HttpHeaders.LastModified, lastModified)
            }
        })
    }

    private suspend fun io.ktor.server.application.ApplicationCall.serveBody(
        supportRange: Boolean,
        throttle: Boolean = false,
    ) {
        if (request.httpMethod == io.ktor.http.HttpMethod.Head) {
            serveHead(supportRange)
            return
        }
        response.headers.append(HttpHeaders.ETag, etag)
        response.headers.append(HttpHeaders.LastModified, lastModified)
        if (supportRange) response.headers.append(HttpHeaders.AcceptRanges, "bytes")

        val rangeHeader = if (supportRange) request.headers[HttpHeaders.Range] else null
        val (start, end) = if (rangeHeader != null) parseRange(rangeHeader, payload.size) else 0 to payload.size - 1
        val slice = payload.copyOfRange(start, end + 1)
        val status = if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        if (rangeHeader != null) {
            response.headers.append(HttpHeaders.ContentRange, "bytes $start-$end/${payload.size}")
        }

        if (throttle) {
            // Paced body with a real Content-Length (so framing is correct and the
            // manager tracks the true total) that drips out over ~1s, leaving a wide
            // window to pause mid-download.
            respond(ThrottledContent(slice, status))
        } else {
            respondBytes(slice, status = status)
        }
    }

    /**
     * A body of known length that writes in small chunks with a delay between each, so a
     * download is slow enough to be paused. A real Content-Length keeps HTTP framing valid
     * (no chunked encoding) and lets the manager detect truncation.
     */
    private class ThrottledContent(
        private val data: ByteArray,
        override val status: HttpStatusCode,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = data.size.toLong()

        override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
            val chunk = 16 * 1024
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + chunk, data.size)
                channel.writeFully(data, offset, end) // (source, startIndex, endIndex)
                channel.flush()
                offset = end
                delay(25)
            }
        }
    }

    private companion object {
        /** Parses `bytes=start-` or `bytes=start-end` into an inclusive [start, end]. */
        fun parseRange(header: String, total: Int): Pair<Int, Int> {
            val spec = header.removePrefix("bytes=").trim()
            val parts = spec.split('-')
            val start = parts[0].toInt()
            val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (total - 1)
            return start to end
        }
    }
}
