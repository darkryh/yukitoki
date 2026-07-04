package io.github.darkryh.yukitoki.manager

import okio.Buffer
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Sink
import okio.Source
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Serializes every access to an underlying [FileSystem] behind a single lock, including the
 * `write`/`read`/`close` of the `Sink`/`Source` handles it hands out.
 *
 * Why this exists: okio's `FakeFileSystem` is an in-memory test double that is **not
 * thread-safe** and, unlike a real filesystem, throws `"file is open"` when a path that still
 * has an open `Sink`/`Source` is deleted or reopened. A segmented download runs its per-segment
 * workers concurrently; on Kotlin/Native, under load, a cancelled segment's handle-close can run
 * on a different thread than the coordinator's merge/cleanup, racing `FakeFileSystem`'s
 * open-handle bookkeeping and surfacing a spurious `"file is open"` failure.
 *
 * Real per-platform filesystems (JVM `NIO`, Android, iOS) are thread-safe and have no such
 * open-handle strictness — the production manager never hits this. This wrapper only makes the
 * in-memory test double behave like the real thing so the segmentation tests are deterministic
 * across JVM, Native and JS. It is intentionally test-only.
 */
@OptIn(ExperimentalAtomicApi::class)
class SynchronizedFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {

    // 0 = free, 1 = held. Non-reentrant, but no guarded block ever calls back into another
    // guarded method (every `super.*` targets the delegate, never this wrapper), so a
    // single-threaded platform (wasmJs) can never self-deadlock.
    private val lock = AtomicInt(0)

    private inline fun <T> guarded(block: () -> T): T {
        while (!lock.compareAndSet(0, 1)) { /* spin: contention is rare and test-only */ }
        try {
            return block()
        } finally {
            lock.store(0)
        }
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink =
        guarded { LockedSink(super.sink(file, mustCreate)) }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink =
        guarded { LockedSink(super.appendingSink(file, mustExist)) }

    override fun source(file: Path): Source =
        guarded { LockedSource(super.source(file)) }

    override fun delete(path: Path, mustExist: Boolean) =
        guarded { super.delete(path, mustExist) }

    override fun atomicMove(source: Path, target: Path) =
        guarded { super.atomicMove(source, target) }

    override fun createDirectory(dir: Path, mustCreate: Boolean) =
        guarded { super.createDirectory(dir, mustCreate) }

    override fun metadataOrNull(path: Path): FileMetadata? =
        guarded { super.metadataOrNull(path) }

    override fun list(dir: Path): List<Path> =
        guarded { super.list(dir) }

    private inner class LockedSink(private val delegate: Sink) : Sink {
        override fun write(source: Buffer, byteCount: Long) = guarded { delegate.write(source, byteCount) }
        override fun flush() = guarded { delegate.flush() }
        override fun timeout() = delegate.timeout()
        override fun close() = guarded { delegate.close() }
    }

    private inner class LockedSource(private val delegate: Source) : Source {
        override fun read(sink: Buffer, byteCount: Long): Long = guarded { delegate.read(sink, byteCount) }
        override fun timeout() = delegate.timeout()
        override fun close() = guarded { delegate.close() }
    }
}
