# Yukitoki documentation

Documentation for the Yukitoki Kotlin Multiplatform download manager, organized by what you need.

These docs describe `1.0.0-beta08`.

## Tutorial — *learning*

Start here if you're new. A single guided lesson that takes you from an empty project to a working,
observable download.

- **[Getting started](getting-started.md)**

## How-to guides — *working*

Task-focused recipes. Each assumes you already know the basics and solves one real problem.

- [Download inside a ViewModel](how-to/download-in-a-viewmodel.md)
- [Control the queue: pause, resume, cancel, retry, reorder](how-to/control-the-queue.md)
- [Verify file integrity with checksums](how-to/verify-file-integrity.md)
- [Tune concurrency and segmentation](how-to/tune-concurrency-and-segmentation.md)
- [Handle failures and retries](how-to/handle-failures-and-retries.md)
- [Set up Android](how-to/set-up-android.md)
- [Download on wasm / in the browser](how-to/download-on-wasm.md)

## Reference — *information*

Dry, complete facts about the public API. Look things up here.

- [API reference](reference/api.md)

## Explanation — *understanding*

Background and design rationale — read when you want to understand *why* Yukitoki behaves the way
it does.

- [Architecture and design](explanation/architecture.md)
- [Project status](../PROJECT_STATUS.md): what's out of scope, what's verified, and what's left before `1.0.0`

---

New to the four-part split above? It's the [Diátaxis](https://diataxis.fr/) framework: tutorials
teach, how-to guides solve a task, reference states facts, and explanation gives understanding.
Each page sticks to one job so you always know what you're reading.
