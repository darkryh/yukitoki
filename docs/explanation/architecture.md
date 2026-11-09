# Architecture and design

This page explains how Yukitoki is built and why it makes the choices it does. It's background
reading — you don't need any of it to use the library, but it helps when you're deciding how far to
trust the defaults or debugging unusual behavior.

## Design goals

Yukitoki was built to satisfy four goals at once, and most of its structure follows from the tension
between them:

1. **One implementation, every target.** The queue, state machine, retry logic, and resume logic live
   in `commonMain`. Only genuinely platform-specific pieces — the I/O dispatcher, the filesystem, the
   HTTP engine, the free-space check, the default download directory, browser intents — are
   `expect`/`actual`.
2. **Never block the UI thread.** The library must be safe to drive directly from `viewModelScope`,
   which runs on `Dispatchers.Main`.
3. **Correct files or no files.** A download either produces a byte-perfect file at the destination or
   leaves nothing partial behind (subject to your cleanup policy). No half-written files masquerading
   as complete.
4. **Fast when it's safe, conservative when it isn't.** Use parallelism and resume aggressively when
   the server supports them, and fall back cleanly when it doesn't — without the caller having to know
   which case they're in.

## The actor-based coordinator

All mutable queue state — what's pending, what's active, each download's state, the concurrency limit
— lives behind a single coordinator that processes messages one at a time. Commands (`enqueue`,
`pause`, `setMaxConcurrentDownloads`, …) and internal events (a worker made progress, succeeded, failed)
are funnelled through this single-writer loop.

This is the **actor model**, and it's a deliberate alternative to sprinkling locks over shared state:

- There is exactly one writer, so there are no data races on the queue and no lock ordering to get
  wrong.
- State transitions are serialized and therefore easy to reason about: a download can't be
  simultaneously "pausing" and "succeeding" because both are messages handled in sequence.
- The public `StateFlow` is just the latest coordinator state published outward, so every observer
  sees a consistent snapshot.

The cost is that the coordinator is a single point through which all control flow passes — which is
exactly why it must never do anything slow.

## Threading model

This is the key to goal #2. There are two kinds of work, and they run in different places:

- **The coordinator** runs on one thread at a time of the I/O dispatcher, whichever scope you pass to
  `DownloadManager.create`. Enqueueing resolves file names and reserves paths, so it can touch the
  disk, which is why it never runs on your scope's dispatcher.
- **Each download worker** is launched on the same **I/O dispatcher**, `Dispatchers.IO` on Android,
  JVM, and iOS. This is where the blocking okio reads and writes, atomic moves, and metadata calls
  happen.

So when you call `manager.pause(id)` from the main thread, you're sending a message to the coordinator
(cheap, non-blocking); the actual file work was never on your thread to begin with. That separation is
what makes "drive it straight from a ViewModel" a safe instruction rather than a footgun.

## The file-safety model

Goal #3 is enforced by never writing to the destination path directly. A download:

1. Streams incoming bytes into a temporary `.part` file next to the destination.
2. Validates the result — expected byte count, `Content-Length`, and any `ChecksumSpec`.
3. Only then **atomically moves** the `.part` file onto the destination path.

Because the move is atomic, an observer of the destination path only ever sees "not there" or "the
complete, verified file" — never a partial. If anything fails before the move, the destination is
untouched, and the `.part` file is deleted or kept according to `deletePartialOnFailure` /
`deletePartialOnCancel`.

## Resume

A paused single-stream download, or one waiting to retry, leaves its `.part` file in place. On resume,
the manager issues an HTTP `Range` request starting at the bytes already on disk, guarded by an
`If-Range` validator (the server's `ETag` or `Last-Modified`). The validator is what makes resume
**safe**: if the resource changed since the partial was written, a correct server answers `200` with
the new file, and the manager discards the partial and starts over. A server that answers `206` with
a different validator fails with `DownloadFailure.ServerChanged`. If the server never gave a validator,
nothing can prove the file is unchanged, so the manager starts over instead of resuming. A `416` means
the server has no bytes at that offset, so it starts over then too. Two versions of a file are never
stitched together.

This follows the standard HTTP model (`Range` / `Accept-Ranges` / `Content-Range` / `If-Range`), so it
interoperates with any correct HTTP server without special server support.

Segmented downloads write every slice at its offset in one internal temp file, which is moved to the
`.part` path only once complete. A retry or a resume continues each slice from where it stopped, under the
same rule: the file's validator and size must be unchanged, or the slices start over. Slice progress is kept
in memory, so it lasts as long as the manager does.

## Segmentation: prove-then-parallelize

Segmented downloading — fetching byte ranges of one file over several connections — is a big speedup
for large files, but only if the server actually supports ranges correctly. Many don't, or do so
inconsistently. Yukitoki treats parallelism as something the server must **earn**:

1. It probes metadata with `HEAD`.
2. If `HEAD` is broken or inconclusive, it falls back to a tiny `GET Range: bytes=0-0` probe.
3. Only if range support is proven does it request isolated slices, each with
   `Accept-Encoding: identity` (so a compressing proxy can't scramble byte offsets).
4. Each slice response is validated (`206 Partial Content`, `Content-Range`, expected byte counts)
   as its bytes are written at their offset in one internal temp file, which becomes the `.part` file
   once every slice is complete.

If any of that fails to prove out, the download quietly uses the validated single-stream path instead.
That includes a server that advertises ranges and then answers a slice with the whole file. A slice with
a different validator than the probe saw fails with `DownloadFailure.ServerChanged`.
This is why `DownloadSegmentation.MaxSegments(n)` is documented as a **maximum**, not a demand: the manager
will use up to `n` segments, but fewer — or one — if the server or current conditions call for it.
Segmentation is also GET-only, because ranged parallelism of a non-idempotent method isn't safe.

## Backpressure and retries

Two mechanisms keep Yukitoki polite and resilient on imperfect networks:

- **Rate-limit backpressure.** A `429 Too Many Requests` is treated as a pacing signal, not just an
  error. The manager honors a numeric `Retry-After`, tracks per-origin request pressure, retries
  segmented downloads with fewer segments, and can drop a host to single-stream. This is per-origin, so
  one rate-limiting host doesn't slow downloads from others.
- **Retries with backoff + jitter.** Transient failures (network errors, timeouts, HTTP 5xx/408/429)
  are retried inside a single download attempt using the request's `RetryPolicy` — exponential backoff
  with random jitter to avoid thundering-herd retries. Non-transient failures (404, checksum mismatch,
  permission denied, no space) fail fast, because a retry would produce the same result.

A connection that drops before the promised bytes arrive is transient too, so it's retried even though
its final failure is `SizeMismatch`. Failures are classified by where they happened, not by what the
message says: an I/O error from the disk is a filesystem failure and fails fast, and any other I/O error
came from the network and is retried. Pausing doesn't count as an attempt.

Single-stream requests ask for `Accept-Encoding: identity`, so the file is the bytes the server stores
and `Content-Length` can be checked against them. A server that compresses anyway has its body saved as
sent on JVM and Android, while Darwin decompresses it before Yukitoki sees it.

The idle/stall timeout (`idleTimeout`) is a separate safety net for a specific failure the HTTP
client's own timeouts miss: a socket that connects and then goes silent forever. It fails the attempt
if the response head, or the next body bytes, do not arrive within the configured window. It also
bounds the HEAD and range metadata probes used to plan segmented downloads.

Over HTTP/2, a client sends every request to a host over one connection, so a retry on a silent
connection would wait in the same place, and engines offer no way to drop just that connection. When
the idle timeout fires, a manager from `DownloadManager.create(config)` therefore builds a new client
for later requests. Requests already running on the old client continue, and it closes after the last
one ends. A client you pass in belongs to you, so it's never replaced.

Total request timeouts work the other way: download requests explicitly opt out of them. A total
timeout caps how long a whole transfer may take, which is wrong for a file of unknown size on a link
of unknown speed. Ktor's CIO engine applies a 15-second one by default unless a request sets its own,
so every body-carrying request sets `requestTimeoutMillis = INFINITE` and lets `idleTimeout` decide
whether the transfer is still alive.

## The typed failure model

`DownloadFailure` is a sealed interface rather than exceptions-as-strings for two reasons. First, it
makes the `when` over failures exhaustive — the compiler tells you when a new failure type needs
handling. Second, it forces every internal error to be *classified* at the point it happens (is this a
network problem, a disk problem, a server problem?), which is also what lets the retry logic decide
correctly whether an error is worth retrying.

## Wasm is a separate path

Browsers don't give Kotlin/Wasm a writable Downloads directory, so the whole `.part`-then-move model
doesn't apply there. Rather than pretend, Yukitoki offers a distinct, honest API for the browser:
`DownloadIntents.dispatch` hands the download to the browser and returns once the browser accepts it.
It explicitly does not promise byte progress, a final path, or checksums — because on that platform
the browser, not Yukitoki, owns the transfer. Requests with custom headers go through a buffered
`fetch()` instead, which suits bounded-size payloads and needs CORS across origins; for large
authenticated files, prefer a server-issued signed URL.

## The ownership model

`DownloadManager.create()` builds its own `HttpClient` and `CoroutineScope`, and closes them with the
manager. `DownloadManager.create(httpClient, scope)` does **not** own what you pass: you create,
configure, and dispose of both. That second form keeps the library out of the business of guessing
your engine, your timeouts, your proxy, and your lifecycle, and it means the manager composes cleanly
with whatever DI and lifecycle framework you already use. The manager runs under a child
`SupervisorJob` of your scope, so cancelling your scope reliably tears the manager down, and one
failed download never cancels the others.

Every teardown ends the same way, whether it's `shutdown`, `close`, or a cancelled scope. The coordinator
waits for its workers, settles each unfinished download as cancelled, removes its partial file, and
publishes that final state.

Network and charging constraints (Wi-Fi-only, charging-only) are intentionally *not* part of the
request model either. They belong to a platform scheduler that decides *whether* to enqueue; Yukitoki's
job is to execute a download well once you've decided to start it.

## Where this is a beta

`1.0.0-beta08` is a feature-complete beta. The architecture above is stable and unlikely to change,
but as a pre-`1.0.0` release the exact shape of some public types may still be refined in response to
feedback before the stable release. Pin the version you build against and consult the
[changelog](../../CHANGELOG.md) when upgrading.

## See also

- [API reference](../reference/api.md) — the exact public surface.
- [Tune concurrency and segmentation](../how-to/tune-concurrency-and-segmentation.md) — the practical dials.
- [Handle failures and retries](../how-to/handle-failures-and-retries.md) — failures and retries in use.
