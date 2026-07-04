# Changelog

All notable changes to Yukitoki are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0-beta02] - 2026-07-03

Reliability fixes for segmented downloads and rate-limit pacing. No public API changes —
`1.0.0-beta01` code continues to work unchanged.

### Fixed

- **Rate-limit pacing uses a monotonic clock.** Per-origin backpressure and `429` back-off now
  measure time with a monotonic source instead of the system wall clock, so segmented-download
  pacing stays correct even if the system clock is stepped (for example by an NTP adjustment).
- **Segment permit leak on cancellation.** When a segmented download was cancelled while retrying
  after a `429`, a per-origin request permit could leak, permanently shrinking that host's effective
  parallelism. Permit release is now cancellation-safe.

### Changed

- Test suite made deterministic across JVM, Native, and JS (virtual-clock pacing and a thread-safe
  in-memory test filesystem), removing intermittent CI failures in the segmentation tests.

## [1.0.0-beta01] - 2026-07-03

First public beta of the Yukitoki Kotlin Multiplatform download manager.

### Added

- **Multiplatform download manager** with a common-code API for JVM, Android, iOS
  (arm64 + simulator arm64), and wasmJs.
- **Actor-based coordinator** — a single-writer state machine owns the queue and never touches the
  filesystem, so it is safe to drive from a UI-confined scope such as `viewModelScope`.
- **Parallel queue** with configurable concurrency (`DownloadConcurrency.Auto` / `Fixed(n)`) and
  three priority levels (`HIGH`, `NORMAL`, `LOW`).
- **Command API**: `enqueue`, `download`, `pause`, `resume`, `cancel`, `retry`, `move`,
  their `*All` variants, and `setConcurrency`.
- **Progress and state** exposed as a queue-wide `StateFlow<DownloadManagerState>` and a per-item
  `Flow<DownloadSnapshot?>` via `observe(id)`.
- **HTTP resume** with `.part` files, `Range`, and conditional `If-Range` validators
  (ETag / Last-Modified).
- **Segmented parallel range downloads** — used automatically for larger files only when the server
  proves byte-range support; falls back to a validated single stream otherwise.
- **Retries** with exponential backoff + jitter for network errors, timeouts, and HTTP 5xx / 408 / 429.
- **Rate-limit backpressure**: honors numeric `Retry-After`, reduces per-origin pressure, and can
  drop segment count or fall back to single-stream under `429`.
- **Per-attempt idle/stall timeout** (`idleTimeoutMs`, default 30 s) independent of the Ktor client
  timeouts.
- **Integrity checks**: expected byte count, `Content-Length` validation, and optional
  MD5 / SHA-256 checksums (`ChecksumSpec`).
- **File safety**: streamed to an atomic `.part` temp file, then moved into place; overwrite policies
  `REPLACE`, `FAIL_IF_EXISTS`, `RENAME_UNIQUE`; input validation on URLs, file names, and checksums.
- **Typed failure model** (`DownloadFailure`) covering HTTP status, network, timeout, permission,
  disk space, existing-file, filesystem, integrity/checksum mismatch, server-changed, and cancellation.
- **Lifecycle controls**: suspending `shutdown(cancelActive)` for an orderly drain and non-suspending
  `cancel()` for lifecycle callbacks such as `ViewModel.onCleared()`.
- **Browser download intents** (`DownloadIntents.dispatch`) for simple GET downloads on wasm, where
  the browser owns the actual transfer.
- **Sample app** — a shared Compose Multiplatform UI running on Android, iOS, and desktop.

[Unreleased]: https://github.com/darkryh/yukitoki/compare/v1.0.0-beta02...HEAD
[1.0.0-beta02]: https://github.com/darkryh/yukitoki/compare/v1.0.0-beta01...v1.0.0-beta02
[1.0.0-beta01]: https://github.com/darkryh/yukitoki/releases/tag/v1.0.0-beta01
