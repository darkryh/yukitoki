# Architecture and design

This page explains how Yukitoki is built and why it makes the choices it does. It's background
reading — you don't need any of it to use the library, but it helps when you're deciding how far to
trust the defaults or debugging unusual behavior.

## Design goals

Yukitoki was built to satisfy four goals at once, and most of its structure follows from the tension
between them:

1. **One implementation, every target.** The queue, state machine, retry logic, and resume logic live
   in `commonMain`. Only genuinely platform-specific pieces — the I/O dispatcher, the filesystem, the
   default download directory, browser intents — are `expect`/`actual`.
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
`pause`, `setConcurrency`, …) and internal events (a worker started, made progress, succeeded, failed)
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

- **The coordinator** runs on the dispatcher of the `CoroutineScope` you pass to
  `DefaultDownloadManager`. It only ever touches in-memory state — it never reads or writes a file.
  Because it does no blocking work, running it on a UI-confined scope (`viewModelScope`, i.e. Main) is
  harmless.
- **Each download worker** is launched on an **I/O dispatcher** (`ioDispatcher`). This is where the
  blocking okio reads and writes, atomic moves, and metadata calls happen. The default is
  `Dispatchers.IO` on JVM/Android and `Dispatchers.Default` on iOS (Kotlin/Native exposes no public
  `Dispatchers.IO`, and `Default` is an off-main thread pool, which is what matters).

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

A paused or interrupted single-stream download leaves its `.part` file in place. On resume, the
manager issues an HTTP `Range` request starting at the bytes already on disk, guarded by an
`If-Range` validator (the server's `ETag` or `Last-Modified`). The validator is what makes resume
**safe**: if the resource changed since the partial was written, the condition fails and the manager
reports `DownloadFailure.ServerChanged` rather than stitching together two different versions of a
file.

This follows the standard HTTP model (`Range` / `Accept-Ranges` / `Content-Range` / `If-Range`), so it
interoperates with any correct HTTP server without special server support.

Resume is designed around the single-stream path. Segmented downloads keep their in-flight slices as
internal temp files that are cleaned up on pause — which is why the docs recommend
`DownloadSegmentation.Single` when reliable pause/resume matters more than raw speed for a
particular file.

## Segmentation: prove-then-parallelize

Segmented downloading — fetching byte ranges of one file over several connections — is a big speedup
for large files, but only if the server actually supports ranges correctly. Many don't, or do so
inconsistently. Yukitoki treats parallelism as something the server must **earn**:

1. It probes metadata with `HEAD`.
2. If `HEAD` is broken or inconclusive, it falls back to a tiny `GET Range: bytes=0-0` probe.
3. Only if range support is proven does it request isolated slices, each with
   `Accept-Encoding: identity` (so a compressing proxy can't scramble byte offsets).
4. Each slice response is validated (`206 Partial Content`, `Content-Range`, expected byte counts)
   before its bytes are merged into the single `.part` file.

If any of that fails to prove out, the download quietly uses the validated single-stream path instead.
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

The idle/stall timeout (`idleTimeout`) is a separate safety net for a specific failure the HTTP
client's own timeouts miss: a socket that connects and then goes silent forever. It fails the attempt
if no bytes arrive for the configured window.

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
It explicitly does not promise byte progress, a final path, checksums, or custom headers — because on
that platform the browser, not Yukitoki, owns the transfer. For anything needing auth or verification
on wasm, the recommended pattern is a server-issued signed URL.

## The ownership model

Yukitoki deliberately does **not** own two things: your `HttpClient` and your `CoroutineScope`. You
create, configure, and dispose of both. This keeps the library out of the business of guessing your
engine, your timeouts, your proxy, and your lifecycle — and it means the manager composes cleanly with
whatever DI and lifecycle framework you already use. The manager runs under a child `SupervisorJob` of
your scope, so cancelling your scope reliably tears the manager down, and one failed download never
cancels the others.

Network and charging constraints (Wi-Fi-only, charging-only) are intentionally *not* part of the
request model either. They belong to a platform scheduler that decides *whether* to enqueue; Yukitoki's
job is to execute a download well once you've decided to start it.

## Where this is a beta

`1.0.0-beta04` is a feature-complete beta. The architecture above is stable and unlikely to change,
but as a pre-`1.0.0` release the exact shape of some public types may still be refined in response to
feedback before the stable release. Pin the version you build against and consult the
[changelog](../../CHANGELOG.md) when upgrading.

## See also

- [API reference](../reference/api.md) — the exact public surface.
- [Tune concurrency and segmentation](../how-to/tune-concurrency-and-segmentation.md) — the practical dials.
- [Handle failures and retries](../how-to/handle-failures-and-retries.md) — the failure taxonomy in use.
