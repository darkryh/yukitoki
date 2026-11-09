# Integration tests

These tests run real downloads over real sockets, on each platform's real engine. `download-manager`'s own tests use fakes, so they're fast but can't catch how a real engine, server, disk, or browser behaves. Bugs only these tests can see include iOS network failures being reported as `Unexpected` and browser downloads dropped by the popup blocker.

Nothing here is published. A plain `./gradlew build` compiles the tests but doesn't run them, because they need the server in `server/`.

## Run them

```bash
integration-tests/run.sh                         # JVM and iOS
integration-tests/run.sh jvm ios android         # Choose platforms
integration-tests/run.sh chrome firefox safari   # Real browsers
integration-tests/run.sh --large jvm             # Add the 2.5 GiB files
integration-tests/run.sh --real-cdn android      # Add a real CDN, and network loss on Android
integration-tests/run.sh '--tests=*Http2Test*' jvm
```

The script starts the server, creates a test certificate, trusts it on each platform, and exits non-zero if anything fails. A test that needs a flag you didn't pass prints `SKIPPED` and counts as passed, so read the output or pass the flag.

You need:

| To run | You need |
|---|---|
| Anything | JDK 21, Node, and OpenSSL |
| `ios`, or the full-disk tests | macOS. The script boots the simulator; set `IOS_DEVICE` to a name or UDID to choose one |
| `android` | A running emulator and `adb` on your `PATH` |
| `chrome`, `firefox` | The browser. Set `CHROME_PATH` or `FIREFOX_PATH` if it isn't in `/Applications` |
| `safari` | `safaridriver --enable`, or **Allow remote automation** in Safari's Developer settings. Saves test files to `~/Downloads` and removes them. Click **Allow** if it asks about downloads from `localhost` |

## What's covered

| Test | What it proves |
|---|---|
| `Http2Test` | Whole files, segments, pause and resume, reset streams, closed connections, connections that stop answering, and shared connections all give the exact file over HTTP/2. `Auto` uses one stream where the engine reports HTTP/2 |
| `FlakyNetworkTest` | Over HTTP/1.1 and HTTP/2, repeated drops and stalls still give the exact file, segments keep their progress across retries and pauses, and `retry` works after attempts run out |
| `LargeFileTest` | A 2.5 GiB file resumes from past the `Int` limit, passes its checksum, and arrives intact in segments, with the heap under 200 MiB |
| `DiskFullTest` | A full disk is `NoSpace`, leaves nothing behind, and the manager keeps working |
| `RealCdnTest` | One stream, `Auto`, segments, and a resume all give the published file from a real CDN |
| `NetworkLossTest` | On Android, turning the radios off mid-download recovers, and so does moving from Wi-Fi to mobile data |
| `browser/run.mjs` | With the popup blocker on, what gets saved, whether the app stays open, which tabs open, and that the app's own click listeners don't fire |

`RealCdnTest` and `NetworkLossTest` download released Node and Android SDK files, and compare them with fixed SHA-256 values. Keep these on hosts built for repeated downloads. Free speed-test mirrors rate limit and then silently drop your IP, which looks exactly like a client bug.

## Add a test

Put shared tests in `src/commonTest`, using `realTest` for real time and a clean directory, and `assertSavedIntact` to compare the file with the server. To make the server misbehave in a new way, add a query parameter in `server/server.mjs`.
