package io.github.darkryh.yukitoki.manager

import io.ktor.client.HttpClient
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Hands out the client for new requests, and swaps it when its connections stop answering.
 *
 * An HTTP/2 client sends every request to a host over one connection, and engines don't offer a way
 * to drop it. When that connection goes silent, retries on the same client queue on it and never get
 * an answer, while a new connection would work. Replacing the client is the only cure that works on
 * every engine.
 *
 * Requests already running on a replaced client keep going, and the client closes when the last one
 * ends. Without [newClient], as when the caller owns the client, nothing is replaced.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ReplaceableHttpClient(
    first: HttpClient,
    private val newClient: (() -> HttpClient)?,
) {
    private val current = AtomicReference(Generation(first))
    private val closed = AtomicBoolean(false)

    /** Call [Generation.release] when the request ends. */
    fun acquire(): Generation {
        while (true) {
            val generation = current.load()
            generation.active.incrementAndFetch()
            // Counting first means a replacement that happens after this check can't close it.
            if (current.load() === generation) return generation
            generation.release()
        }
    }

    /** Stops new requests using [generation]. Only the first caller replaces it. */
    fun replace(generation: Generation) {
        val factory = newClient ?: return
        if (!generation.retired.compareAndSet(expectedValue = false, newValue = true)) return
        val next = Generation(factory())
        current.store(next)
        generation.closeIfDrained()
        // close() may have read the old generation just before the store above.
        if (closed.load()) next.closeNow()
    }

    /** Closes the current client. Replaced ones close as their cancelled requests end. */
    fun close() {
        closed.store(true)
        current.load().closeNow()
    }

    inner class Generation(val client: HttpClient) {
        val active = AtomicInt(0)
        val retired = AtomicBoolean(false)
        private val clientClosed = AtomicBoolean(false)

        fun release() {
            active.decrementAndFetch()
            closeIfDrained()
        }

        fun closeIfDrained() {
            if (retired.load() && active.load() == 0) closeNow()
        }

        fun closeNow() {
            if (clientClosed.compareAndSet(expectedValue = false, newValue = true)) client.close()
        }
    }
}
