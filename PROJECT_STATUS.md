# Project status

Yukitoki is in beta, heading for a stable `1.0.0`. This page records what's in scope, what's verified, and what's left before that release.

## Out of scope for now

These are deliberate decisions, not missing work. They may change in a later release.

- **Background execution.** Yukitoki starts no Android service, schedules no jobs, and uses no iOS background `URLSession`. Downloads run while your process runs.
  - On Android and desktop, you can call Yukitoki from your own foreground service or job.
  - On iOS, downloads run only while the app is active, because iOS suspends apps shortly after they leave the foreground. If you need transfers to continue while suspended, use a background `URLSession` directly.
- **Restoring the queue.** The queue isn't saved, so it doesn't survive process death or a device restart. Enqueue again if your app needs that.

## What the tests cover

[`integration-tests`](integration-tests/README.md) runs real downloads over real sockets, on each platform's real engine.

| Area | JVM | Android | iOS simulator | Browsers |
|---|---|---|---|---|
| HTTP/2: segments, pause and resume, shared connections | Yes | Yes | Yes | |
| HTTP/2: reset streams and closed connections | Yes | Yes | Yes | |
| HTTP/2: connections that stop answering are replaced | Yes | Yes | Yes | |
| Dropped and stalled connections, retry after failure | Yes | Yes | Yes | |
| Segments keep their progress across retries and pauses | Yes | Yes | Yes | |
| Files over 2 GiB, with pause, checksum and a memory limit | Yes | Yes | Yes | |
| Full disk | Yes | | Yes | |
| A real CDN | Yes | Yes | Yes | |
| Network loss and Wi-Fi to mobile data | | Yes | | |
| Downloads in Chrome, Firefox and Safari, with the popup blocker on | | | | Yes |

`download-manager`'s own tests cover the rest of the behavior with fast, deterministic fakes.

## Before 1.0.0

- [ ] Use it in a real app during the beta, and fix what that finds.
- [ ] Test on a physical Android phone and iPhone. The tests so far ran on an emulator and a simulator.
- [ ] Review the public API, because breaking changes need a major version after `1.0.0`:
  - Decide whether `DownloadRequest`, `DownloadManagerConfig` and `RetryPolicy` stay data classes. Adding a field to a public data class changes `copy` and `componentN`, which breaks binary compatibility.
  - Decide whether `DownloadFailure` and `DownloadState` stay sealed. Adding a case breaks exhaustive `when` in apps.
- [ ] Decide on an opt-in setting that always saves browser downloads as files. See [Browser downloads](docs/how-to/download-on-wasm.md) for why a cross-origin page can't download today.

## Known limits

- **`Auto` on iOS:** Ktor's Darwin engine doesn't report the HTTP version, so `Auto` can use segments over HTTP/2. This is safe, but no faster than one stream.
- **HTTP/2 resets in the tests:** under several concurrent streams, OkHttp occasionally sees `stream was reset: NO_ERROR` from the tests' local Node server. A plain Node client doesn't, so the cause isn't known yet. Yukitoki reports it as `Network` and retries, and 120 of 120 stress runs recovered with the exact file.
- **Borrowed clients and silent connections:** a manager replaces its own client when a connection stops answering. It can't replace an `HttpClient` you pass in, so retries there can keep waiting on that connection until the engine drops it.
- **Compressed bodies:** Yukitoki asks for uncompressed bytes. A server that compresses anyway has its body saved as sent on JVM and Android, while Darwin decompresses it.
- **Cross-origin browser downloads:** browsers ignore `fileName` across origins, and a page they can display doesn't download unless its server sends `Content-Disposition: attachment`.
