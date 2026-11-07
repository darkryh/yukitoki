package io.github.darkryh.yukitoki.manager

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.*
import okio.Path.Companion.toPath
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

public class DefaultDownloadManager internal constructor(
    private val httpClient: HttpClient,
    scope: CoroutineScope,
    private val fileSystem: FileSystem,
    config: DownloadManagerConfig,
    private val ioDispatcher: CoroutineDispatcher,
    // Time source for origin-pacing decisions. Defaults to the monotonic clock (immune to
    // NTP steps); tests inject the coroutine test scheduler's time source so pacing delays
    // advance in lockstep with delay() under virtual time instead of racing the wall clock.
    private val timeSource: TimeSource.WithComparableMarks,
    // Jitter source for adaptive rate-limit back-off; injectable so tests are reproducible.
    private val random: Random,
) : DownloadManager {
    public constructor(
        httpClient: HttpClient,
        scope: CoroutineScope,
        fileSystem: FileSystem = PlatformDownloadFileSystem.default,
        config: DownloadManagerConfig = DownloadManagerConfig(),
        ioDispatcher: CoroutineDispatcher = platformIoDispatcher,
    ) : this(
        httpClient = httpClient,
        scope = scope,
        fileSystem = fileSystem,
        config = config,
        ioDispatcher = ioDispatcher,
        timeSource = TimeSource.Monotonic,
        random = Random.Default,
    )

    private data class WorkerHandle(
        val id: DownloadId,
        val request: DownloadRequest,
        val job: Job,
    )

    /** Immutable per-attempt config snapshot handed to a worker so it never reads the
     *  mutable [runtimeConfig] from another thread. */
    private data class WorkerConfig(
        val bufferSizeBytes: Int,
        val deletePartialOnFailure: Boolean,
        val deletePartialOnCancel: Boolean,
        val idleTimeout: Duration,
    )

    private data class ServerMetadata(
        val eTag: String?,
        val lastModified: String?,
    )

    private data class OriginProfile(
        var activeRequests: Int = 0,
        var maxParallelRequests: Int = 4,
        var penaltyUntil: ComparableTimeMark? = null,
        var recentRateLimits: Int = 0,
        var lastGoodSegmentCount: Int? = null,
    )

    private inner class OriginPermit(private val origin: String) {
        // release() runs from a finally while sibling segments are being cancelled (e.g. after
        // a 429). The decrement must survive the caller's cancellation, otherwise the permit
        // leaks and the origin's parallelism budget shrinks permanently — which can wedge
        // acquireOriginPermit in its wait loop. NonCancellable guarantees the update completes.
        suspend fun release() = withContext(NonCancellable) {
            originMutex.withLock {
                val profile = originProfiles[origin] ?: return@withLock
                profile.activeRequests = (profile.activeRequests - 1).coerceAtLeast(0)
            }
        }
    }

    private sealed interface InternalEvent {
        data class Started(val id: DownloadId, val request: DownloadRequest) : InternalEvent
        data class Progress(val id: DownloadId, val progress: DownloadProgress) : InternalEvent
        data class MetadataLearned(val id: DownloadId, val meta: ServerMetadata) : InternalEvent
        data class Succeeded(val id: DownloadId, val success: DownloadSuccess) : InternalEvent
        data class Failed(val id: DownloadId, val failure: DownloadFailure) : InternalEvent
        data class Paused(val id: DownloadId) : InternalEvent
        data class Cancelled(val id: DownloadId) : InternalEvent
    }

    private sealed interface InternalMessage {
        data class UserCommand(val command: DownloadCommand, val reply: CompletableDeferred<CommandResult>) : InternalMessage
        data class WorkerEvent(val event: InternalEvent) : InternalMessage
        data class AwaitTerminal(
            val id: DownloadId,
            val reply: CompletableDeferred<DownloadResult?>,
        ) : InternalMessage
        data class RetryReady(val id: DownloadId) : InternalMessage
        data class Shutdown(val cancelActive: Boolean, val reply: CompletableDeferred<Unit>) : InternalMessage
    }

    private val rootJob = SupervisorJob(scope.coroutineContext[Job])
    private val internalScope = CoroutineScope(scope.coroutineContext + rootJob)
    private val inbox = Channel<InternalMessage>(capacity = Channel.UNLIMITED)

    @Volatile
    private var closed = false

    private var runtimeConfig = config
    private var maxConcurrentDownloads = resolveConcurrentDownloads(config.concurrency)
    private val pendingHigh = ArrayDeque<DownloadId>()
    private val pendingNormal = ArrayDeque<DownloadId>()
    private val pendingLow = ArrayDeque<DownloadId>()
    private val active = linkedMapOf<DownloadId, WorkerHandle>()
    private val snapshots = linkedMapOf<DownloadId, DownloadSnapshot>()
    private val resolvedDestinations = mutableMapOf<DownloadId, Path>()
    private val destinationReservations = mutableMapOf<Path, DownloadId>()
    private val terminalOrder = ArrayDeque<DownloadId>()
    private val terminalWaiters = mutableMapOf<DownloadId, MutableList<CompletableDeferred<DownloadResult?>>>()
    private val resumeAfterPause = mutableSetOf<DownloadId>()
    private val originMutex = Mutex()
    private val originProfiles = mutableMapOf<String, OriginProfile>()

    // Coordinator-confined: only the coordinator coroutine reads/writes this map.
    private val serverMetadata = mutableMapOf<DownloadId, ServerMetadata>()

    // Graceful-shutdown state (coordinator-confined).
    private var draining = false
    private var shutdownReply: CompletableDeferred<Unit>? = null

    private val _state = MutableStateFlow(
        DownloadManagerState(
            snapshots = emptyMap(),
            pending = emptyList(),
            active = emptySet(),
            maxConcurrentDownloads = maxConcurrentDownloads,
        )
    )

    init {
        internalScope.launch { coordinatorLoop() }
    }

    private suspend fun submitCommand(command: DownloadCommand): CommandResult {
        if (closed) return CommandResult.Rejected(
            "Download manager is shut down",
            DownloadFailure.ManagerClosed(),
        )
        val reply = CompletableDeferred<CommandResult>()
        if (!inbox.trySend(InternalMessage.UserCommand(command, reply)).isSuccess) {
            return CommandResult.Rejected(
                "Download manager is shut down",
                DownloadFailure.ManagerClosed(),
            )
        }
        return reply.await()
    }

    internal suspend fun submitInternal(command: DownloadCommand): CommandResult = submitCommand(command)

    override val state: StateFlow<DownloadManagerState> = _state.asStateFlow()

    override fun observe(downloadId: DownloadId): Flow<DownloadSnapshot?> =
        _state.map { it.snapshots[downloadId] }.distinctUntilChanged()

    override suspend fun await(downloadId: DownloadId): DownloadResult? {
        if (closed) return null
        val reply = CompletableDeferred<DownloadResult?>()
        if (!inbox.trySend(InternalMessage.AwaitTerminal(downloadId, reply)).isSuccess) return null
        return select {
            reply.onAwait { it }
            rootJob.onJoin { null }
        }
    }

    override suspend fun enqueue(request: DownloadRequest, id: DownloadId): DownloadTask {
        return when (val ack = submitCommand(DownloadCommand.Enqueue(request, id))) {
            is CommandResult.Accepted -> ManagedDownloadTask(ack.id ?: id)
            is CommandResult.Rejected -> throw DownloadRejectedException(
                ack.failure ?: DownloadFailure.Unexpected(ack.reason)
            )
        }
    }

    override suspend fun download(request: DownloadRequest): DownloadResult {
        val id = DownloadId.new()
        val ack = submitCommand(DownloadCommand.Enqueue(request, id))
        if (ack !is CommandResult.Accepted) {
            val reason = (ack as? CommandResult.Rejected)?.reason ?: "Download was rejected"
            return DownloadResult.Failure(
                (ack as? CommandResult.Rejected)?.failure ?: DownloadFailure.Unexpected(reason)
            )
        }
        return await(id)
            ?: DownloadResult.Failure(DownloadFailure.ManagerClosed("Download did not reach a terminal state"))
    }

    override suspend fun pause(downloadId: DownloadId): CommandResult =
        submitCommand(DownloadCommand.Pause(downloadId))

    override suspend fun resume(downloadId: DownloadId): CommandResult =
        submitCommand(DownloadCommand.Resume(downloadId))

    override suspend fun cancel(downloadId: DownloadId): CommandResult =
        submitCommand(DownloadCommand.Cancel(downloadId))

    override suspend fun retry(downloadId: DownloadId): CommandResult =
        submitCommand(DownloadCommand.Retry(downloadId))

    override suspend fun moveWithinPriority(downloadId: DownloadId, newPosition: Int): CommandResult =
        submitCommand(DownloadCommand.MoveWithinPriority(downloadId, newPosition))

    override suspend fun pauseAll(): CommandResult =
        submitCommand(DownloadCommand.PauseAll)

    override suspend fun resumeAll(): CommandResult =
        submitCommand(DownloadCommand.ResumeAll)

    override suspend fun cancelAll(): CommandResult =
        submitCommand(DownloadCommand.CancelAll)

    override suspend fun retryAllFailed(): CommandResult =
        submitCommand(DownloadCommand.RetryAllFailed)

    override suspend fun setConcurrency(concurrency: DownloadConcurrency): CommandResult =
        submitCommand(DownloadCommand.SetConcurrency(concurrency))

    override suspend fun remove(downloadId: DownloadId, deleteFile: Boolean): CommandResult =
        submitCommand(DownloadCommand.Remove(downloadId, deleteFile))

    override suspend fun clearFinished(): CommandResult =
        submitCommand(DownloadCommand.ClearFinished)

    override suspend fun shutdown(cancelActive: Boolean) {
        closed = true
        val reply = CompletableDeferred<Unit>()
        if (inbox.trySend(InternalMessage.Shutdown(cancelActive, reply)).isSuccess) {
            reply.await()
        }
        rootJob.cancelAndJoin()
    }

    override fun close() {
        closed = true
        rootJob.cancel()
    }

    private inner class ManagedDownloadTask(override val id: DownloadId) : DownloadTask {
        override val state: Flow<DownloadSnapshot?> = observe(id)
        override suspend fun await(): DownloadResult =
            this@DefaultDownloadManager.await(id)
                ?: DownloadResult.Failure(DownloadFailure.ManagerClosed())
        override suspend fun pause(): CommandResult = this@DefaultDownloadManager.pause(id)
        override suspend fun resume(): CommandResult = this@DefaultDownloadManager.resume(id)
        override suspend fun cancel(): CommandResult = this@DefaultDownloadManager.cancel(id)
        override suspend fun retry(): CommandResult = this@DefaultDownloadManager.retry(id)
    }

    private suspend fun coordinatorLoop() {
        for (message in inbox) {
            when (message) {
                is InternalMessage.UserCommand -> handleUserCommand(message)
                is InternalMessage.WorkerEvent -> handleWorkerEvent(message.event)
                is InternalMessage.AwaitTerminal -> handleAwaitTerminal(message)
                is InternalMessage.RetryReady -> handleRetryReady(message.id)
                is InternalMessage.Shutdown -> beginShutdown(message)
            }
            if (!draining) dispatchIfPossible()
            publishState()
            if (draining && active.isEmpty()) {
                finishShutdown()
                return
            }
        }
    }

    private fun handleAwaitTerminal(message: InternalMessage.AwaitTerminal) {
        val snap = snapshots[message.id]
        if (snap == null) {
            // Unknown id: never enqueued (or already pruned). Don't register a waiter
            // that could hang forever — report "no such download".
            message.reply.complete(null)
            return
        }
        val terminal = terminalResultFromSnapshot(snap)
        if (terminal != null) {
            message.reply.complete(terminal)
            return
        }
        terminalWaiters.getOrPut(message.id) { mutableListOf() }.add(message.reply)
    }

    private fun beginShutdown(message: InternalMessage.Shutdown) {
        draining = true
        shutdownReply = message.reply
        pendingHigh.clear()
        pendingNormal.clear()
        pendingLow.clear()
        snapshots.values
            .filter { it.id !in active && !it.state.isTerminal() }
            .map { it.id }
            .forEach { id ->
                val snapshot = snapshots[id] ?: return@forEach
                snapshots[id] = snapshot.copy(state = DownloadState.Cancelled, queuePosition = null)
                completeWaiters(id, DownloadResult.Cancelled)
                markTerminal(id)
            }
        if (message.cancelActive) {
            active.values.forEach { it.job.cancel(CancellationException("shutdown")) }
        }
    }

    private fun finishShutdown() {
        // Release everyone still waiting on a download that will never terminate now.
        val pending = terminalWaiters.values.flatten()
        terminalWaiters.clear()
        pending.forEach { if (!it.isCompleted) it.complete(null) }
        shutdownReply?.complete(Unit)
        shutdownReply = null
    }

    private fun handleUserCommand(message: InternalMessage.UserCommand) {
        if (draining) {
            message.reply.complete(CommandResult.Rejected("Download manager is shutting down"))
            return
        }
        val ack = when (val cmd = message.command) {
            is DownloadCommand.Enqueue -> {
                if (snapshots.containsKey(cmd.id)) {
                    CommandResult.Rejected("Duplicate download id: ${cmd.id.value}")
                } else {
                    val unsupportedReason = cmd.request.unsupportedByDefaultManagerReason()
                    if (unsupportedReason != null) {
                        CommandResult.Rejected(
                            unsupportedReason,
                            DownloadFailure.Unsupported(unsupportedReason),
                        )
                    } else try {
                        val resolvedDestination = reserveDestination(cmd.request, cmd.id, discardStalePartial = true)
                        val snapshot = DownloadSnapshot(
                            id = cmd.id,
                            request = cmd.request,
                            state = DownloadState.Queued,
                            attempts = 0,
                            queuePosition = null,
                            resolvedDestination = resolvedDestination,
                        )
                        snapshots[cmd.id] = snapshot
                        enqueueIdByPriority(cmd.id, cmd.request.priority)
                        CommandResult.Accepted(cmd.id)
                    } catch (t: Throwable) {
                        val reason = t.message ?: "Unable to reserve the download destination"
                        val failure = when (t) {
                            is DownloadFileExistsException -> DownloadFailure.AlreadyExists(t.path, reason)
                            is IOException -> DownloadFailure.FileSystem(reason)
                            is IllegalStateException -> DownloadFailure.FileSystem(reason)
                            else -> DownloadFailure.Unexpected(reason)
                        }
                        CommandResult.Rejected(reason, failure)
                    }
                }
            }
            is DownloadCommand.Pause -> pauseDownload(cmd.id)
            is DownloadCommand.Resume -> resumeDownload(cmd.id)
            is DownloadCommand.Cancel -> cancelDownload(cmd.id)
            DownloadCommand.PauseAll -> {
                (pendingHigh + pendingNormal + pendingLow).forEach { id ->
                    snapshots[id]?.let { snapshots[id] = it.copy(state = DownloadState.Paused) }
                }
                pendingHigh.clear(); pendingNormal.clear(); pendingLow.clear()
                active.values.forEach { it.job.cancel(PauseCancellationException) }
                CommandResult.Accepted()
            }
            DownloadCommand.ResumeAll -> {
                snapshots.values.filter { it.state is DownloadState.Paused }.forEach {
                    snapshots[it.id] = it.copy(state = DownloadState.Queued)
                    enqueueIdByPriority(it.id, it.request.priority)
                }
                CommandResult.Accepted()
            }
            DownloadCommand.CancelAll -> {
                (pendingHigh + pendingNormal + pendingLow).forEach { id ->
                    snapshots[id]?.let { snap ->
                        snapshots[id] = snap.copy(state = DownloadState.Cancelled)
                        serverMetadata.remove(id)
                        completeWaiters(id, DownloadResult.Cancelled)
                        markTerminal(id)
                    }
                }
                pendingHigh.clear(); pendingNormal.clear(); pendingLow.clear()
                active.values.forEach { it.job.cancel(CancellationException("cancel all")) }
                CommandResult.Accepted()
            }
            is DownloadCommand.Retry -> retryDownload(cmd.id)
            DownloadCommand.RetryAllFailed -> {
                snapshots.values.filter { it.state is DownloadState.Failed }
                    .map { it.id }
                    .forEach(::retryDownload)
                CommandResult.Accepted()
            }
            is DownloadCommand.MoveWithinPriority -> moveWithinPriorityDownload(cmd.id, cmd.newPosition)
            is DownloadCommand.SetConcurrency -> {
                runtimeConfig = runtimeConfig.copy(concurrency = cmd.concurrency)
                maxConcurrentDownloads = resolveConcurrentDownloads(cmd.concurrency)
                CommandResult.Accepted()
            }
            is DownloadCommand.Remove -> removeDownload(cmd.id, cmd.deleteFile)
            DownloadCommand.ClearFinished -> clearFinishedDownloads()
        }
        message.reply.complete(ack)
    }

    private fun retryDownload(id: DownloadId): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected("Download not found")
        if (snap.state !is DownloadState.Failed && snap.state !is DownloadState.Cancelled) {
            return CommandResult.Rejected("Retry is only valid for failed/cancelled downloads")
        }
        val resolved = try {
            reserveDestination(snap.request, id)
        } catch (t: Throwable) {
            return CommandResult.Rejected(t.message ?: "Unable to reserve the download destination")
        }
        snapshots[id] = snap.copy(
            state = DownloadState.Queued,
            attempts = 0,
            resolvedDestination = resolved,
        )
        terminalOrder.remove(id)
        enqueueIdByPriority(id, snap.request.priority)
        return CommandResult.Accepted(id)
    }

    private fun pauseDownload(id: DownloadId): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected("Download not found")
        when (snap.state) {
            is DownloadState.Queued -> {
                removeFromPending(id)
                snapshots[id] = snap.copy(state = DownloadState.Paused)
                return CommandResult.Accepted(id)
            }
            is DownloadState.Starting, is DownloadState.Running -> {
                snapshots[id] = snap.copy(state = DownloadState.Pausing)
                active[id]?.job?.cancel(PauseCancellationException)
                return CommandResult.Accepted(id)
            }
            is DownloadState.Paused, is DownloadState.Pausing -> return CommandResult.Accepted(id)
            else -> return CommandResult.Rejected("Pause not allowed in ${snap.state::class.simpleName}")
        }
    }

    private fun resumeDownload(id: DownloadId): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected("Download not found")
        return when (snap.state) {
            is DownloadState.Paused -> {
                snapshots[id] = snap.copy(state = DownloadState.Queued)
                enqueueIdByPriority(id, snap.request.priority)
                CommandResult.Accepted(id)
            }
            is DownloadState.Pausing -> {
                resumeAfterPause.add(id)
                CommandResult.Accepted(id)
            }
            is DownloadState.Queued, is DownloadState.Starting, is DownloadState.Running -> CommandResult.Accepted(id)
            else -> CommandResult.Rejected("Resume not allowed in ${snap.state::class.simpleName}")
        }
    }

    private fun cancelDownload(id: DownloadId): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected("Download not found")
        return when (snap.state) {
            is DownloadState.Queued, is DownloadState.Paused -> {
                removeFromPending(id)
                snapshots[id] = snap.copy(state = DownloadState.Cancelled)
                serverMetadata.remove(id)
                completeWaiters(id, DownloadResult.Cancelled)
                markTerminal(id)
                CommandResult.Accepted(id)
            }
            is DownloadState.Starting, is DownloadState.Running, is DownloadState.Pausing -> {
                snapshots[id] = snap.copy(state = DownloadState.Cancelling)
                resumeAfterPause.remove(id)
                active[id]?.job?.cancel(CancellationException("cancel"))
                CommandResult.Accepted(id)
            }
            is DownloadState.Cancelled, is DownloadState.Failed, is DownloadState.Succeeded -> CommandResult.Accepted(id)
            is DownloadState.Cancelling -> CommandResult.Accepted(id)
        }
    }

    private fun removeDownload(id: DownloadId, deleteFile: Boolean): CommandResult {
        val snapshot = snapshots[id] ?: return CommandResult.Rejected("Download not found")
        if (!snapshot.state.isTerminal()) {
            return CommandResult.Rejected("Only finished downloads can be removed")
        }
        if (deleteFile) {
            val path = (snapshot.state as? DownloadState.Succeeded)?.result?.finalPath
            if (path != null) {
                try {
                    fileSystem.delete(path, mustExist = false)
                } catch (t: Throwable) {
                    return CommandResult.Rejected(t.message ?: "Unable to delete completed file")
                }
            }
        }
        forgetDownload(id)
        return CommandResult.Accepted(id)
    }

    private fun clearFinishedDownloads(): CommandResult {
        snapshots.values.filter { it.state.isTerminal() }.map { it.id }.forEach(::forgetDownload)
        return CommandResult.Accepted()
    }

    /** Reorders [id] within its own priority tier; priority is preserved. */
    private fun moveWithinPriorityDownload(id: DownloadId, newPosition: Int): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected("Download not found")
        val queue = when (snap.request.priority) {
            DownloadPriority.HIGH -> pendingHigh
            DownloadPriority.NORMAL -> pendingNormal
            DownloadPriority.LOW -> pendingLow
        }
        if (!queue.contains(id)) return CommandResult.Rejected("Download is not pending")
        if (newPosition !in queue.indices) return CommandResult.Rejected("Invalid queue position")
        queue.remove(id)
        queue.add(newPosition, id)
        return CommandResult.Accepted(id)
    }

    private fun handleWorkerEvent(event: InternalEvent) {
        when (event) {
            is InternalEvent.Started -> {
                val snap = snapshots[event.id] ?: return
                snapshots[event.id] = snap.copy(state = DownloadState.Starting, attempts = snap.attempts + 1)
            }
            is InternalEvent.Progress -> {
                val snap = snapshots[event.id] ?: return
                snapshots[event.id] = snap.copy(state = DownloadState.Running(event.progress))
            }
            is InternalEvent.MetadataLearned -> {
                serverMetadata[event.id] = event.meta
            }
            is InternalEvent.Succeeded -> {
                active.remove(event.id)
                resumeAfterPause.remove(event.id)
                serverMetadata.remove(event.id)
                val snap = snapshots[event.id] ?: return
                snapshots[event.id] = snap.copy(state = DownloadState.Succeeded(event.success), queuePosition = null)
                completeWaiters(event.id, DownloadResult.Success(event.success))
                markTerminal(event.id)
            }
            is InternalEvent.Failed -> {
                active.remove(event.id)
                resumeAfterPause.remove(event.id)
                val snap = snapshots[event.id] ?: return
                val canRetry = !draining &&
                    snap.attempts < snap.request.retryPolicy.maxAttempts &&
                    shouldRetry(event.failure, snap.request.retryPolicy)
                if (canRetry) {
                    snapshots[event.id] = snap.copy(state = DownloadState.Queued)
                    scheduleRetry(event.id, snap.attempts, snap.request.retryPolicy, event.failure)
                } else {
                    serverMetadata.remove(event.id)
                    snapshots[event.id] = snap.copy(state = DownloadState.Failed(event.failure), queuePosition = null)
                    completeWaiters(event.id, DownloadResult.Failure(event.failure))
                    markTerminal(event.id)
                }
            }
            is InternalEvent.Paused -> {
                active.remove(event.id)
                val snap = snapshots[event.id] ?: return
                if (resumeAfterPause.remove(event.id)) {
                    snapshots[event.id] = snap.copy(state = DownloadState.Queued)
                    enqueueIdByPriority(event.id, snap.request.priority)
                } else {
                    snapshots[event.id] = snap.copy(state = DownloadState.Paused, queuePosition = null)
                }
            }
            is InternalEvent.Cancelled -> {
                active.remove(event.id)
                resumeAfterPause.remove(event.id)
                serverMetadata.remove(event.id)
                val snap = snapshots[event.id] ?: return
                snapshots[event.id] = snap.copy(state = DownloadState.Cancelled, queuePosition = null)
                completeWaiters(event.id, DownloadResult.Cancelled)
                markTerminal(event.id)
            }
        }
    }

    private fun handleRetryReady(id: DownloadId) {
        if (draining) return
        val snap = snapshots[id] ?: return
        if (snap.state is DownloadState.Queued && id !in active && id !in currentPendingList()) {
            enqueueIdByPriority(id, snap.request.priority)
        }
    }

    private fun scheduleRetry(id: DownloadId, failedAttempt: Int, policy: RetryPolicy, failure: DownloadFailure) {
        val retryDelay = (failure as? DownloadFailure.HttpStatus)?.retryAfter
            ?: computeBackoffDelay(failedAttempt, policy)
        internalScope.launch {
            delay(retryDelay)
            inbox.trySend(InternalMessage.RetryReady(id))
        }
    }

    private fun dispatchIfPossible() {
        while (active.size < maxConcurrentDownloads) {
            val next = dequeueNext() ?: break
            val snapshot = snapshots[next] ?: continue
            if (snapshot.state !is DownloadState.Queued) continue
            val workerConfig = WorkerConfig(
                bufferSizeBytes = runtimeConfig.bufferSizeBytes,
                deletePartialOnFailure = runtimeConfig.deletePartialOnFailure,
                deletePartialOnCancel = runtimeConfig.deletePartialOnCancel,
                idleTimeout = runtimeConfig.idleTimeout,
            )
            val knownMetadata = serverMetadata[next]
            val workerJob = internalScope.launch(ioDispatcher) {
                runWorker(
                    next,
                    snapshot.request,
                    snapshot.resolvedDestination
                        ?: error("Reserved destination missing for ${next.value}"),
                    workerConfig,
                    knownMetadata,
                )
            }
            active[next] = WorkerHandle(next, snapshot.request, workerJob)
        }
    }

    private suspend fun runWorker(
        id: DownloadId,
        request: DownloadRequest,
        destinationPath: Path,
        config: WorkerConfig,
        knownMetadata: ServerMetadata?,
    ) {
        var finalPath = destinationPath
        var partialPath = partialPathFor(finalPath, id)

        try {
            sendEvent(InternalEvent.Started(id, request))
            partialPath = partialPathFor(finalPath, id)
            ensureParentDirectory(finalPath)
            val inlineValidated = writeDownloadToPath(
                id = id,
                request = request,
                config = config,
                partialPath = partialPath,
                knownMetadata = knownMetadata,
            )
            request.expectedChecksum?.let { checksum ->
                if (!inlineValidated) validateChecksum(partialPath, checksum)
            }
            val size = fileSystem.metadata(partialPath).size ?: 0L
            request.expectedSizeBytes?.let {
                if (it != size) throw DownloadIntegrityException(it, size)
            }
            finalizePartialFile(partialPath, finalPath, request.effectiveOverwritePolicy(), id)
            sendEvent(
                InternalEvent.Succeeded(
                    id,
                    DownloadSuccess(finalPath, size, request.expectedSizeBytes ?: size)
                )
            )
        } catch (t: Throwable) {
            when (t) {
                is PauseCancellationException -> {
                    sendEvent(InternalEvent.Paused(id))
                }
                is CancellationException -> {
                    cleanupPartialIfNeeded(partialPath, onCancel = true, config = config)
                    sendEvent(InternalEvent.Cancelled(id))
                }
                is Error -> throw t
                else -> {
                    cleanupPartialIfNeeded(partialPath, onCancel = false, config = config)
                    sendEvent(InternalEvent.Failed(id, mapThrowable(t, finalPath)))
                }
            }
        }
    }

    /** Returns true when the checksum was validated on the fly (whole file hashed as
     *  it streamed), so the caller can skip a redundant second full-file pass. */
    private suspend fun writeDownloadToPath(
        id: DownloadId,
        request: DownloadRequest,
        config: WorkerConfig,
        partialPath: Path,
        knownMetadata: ServerMetadata?,
    ): Boolean {
        val exists = fileSystem.exists(partialPath)
        val downloadedBytes = if (exists) (fileSystem.metadata(partialPath).size ?: 0L) else 0L
        if (request.segmentation !is DownloadSegmentation.Single &&
            downloadedBytes == 0L &&
            writeSegmentedDownloadToPath(id, request, config, partialPath)
        ) {
            return false
        }

        val statement = httpClient.prepareRequest(request.url) {
            this.method = HttpMethod.Get
            disableTotalRequestTimeout()
            headers {
                request.headers.entries().forEach { (name, values) ->
                    values.forEach { value -> append(name, value) }
                }
                if (downloadedBytes > 0L) {
                    append(HttpHeaders.Range, "bytes=$downloadedBytes-")
                    if (knownMetadata != null) {
                        knownMetadata.eTag?.let { append(HttpHeaders.IfRange, it) }
                            ?: knownMetadata.lastModified?.let { append(HttpHeaders.IfRange, it) }
                    }
                }
            }
        }

        return statement.executeWithResponseTimeout(config.idleTimeout) { response ->
            val responseETag = response.headers[HttpHeaders.ETag]?.trim()
            val responseLastModified = response.headers[HttpHeaders.LastModified]?.trim()

            // 1. Handle HTTP Status Code
            if (response.status == HttpStatusCode.PartialContent) {
                if (knownMetadata != null) {
                    if (knownMetadata.eTag != null && responseETag != null && knownMetadata.eTag != responseETag) {
                        throw ServerFileChangedException("Remote file ETag changed mid-download")
                    }
                    if (knownMetadata.lastModified != null && responseLastModified != null &&
                        knownMetadata.lastModified != responseLastModified
                    ) {
                        throw ServerFileChangedException("Remote file Last-Modified changed mid-download")
                    }
                }
            } else if (response.status == HttpStatusCode.OK) {
                if (downloadedBytes > 0L) {
                    fileSystem.delete(partialPath, mustExist = false)
                }
                if (responseETag != null || responseLastModified != null) {
                    sendEvent(InternalEvent.MetadataLearned(id, ServerMetadata(responseETag, responseLastModified)))
                }
            } else {
                throw DownloadHttpException(
                    code = response.status.value,
                    message = response.status.description,
                    retryAfter = response.headers.retryAfter(),
                )
            }

            // 2. Pre-flight Disk Space Validation
            val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (totalBytes != null && totalBytes > 0L) {
                val usableSpace = PlatformSpaceChecker.getUsableSpace(partialPath)
                if (usableSpace != -1L) {
                    val safetyMargin = 10L * 1024L * 1024L
                    if (usableSpace < totalBytes + safetyMargin) {
                        throw DiskNoSpaceException(totalBytes, usableSpace)
                    }
                }
            }

            // 3. Setup progressive hashing ONLY when downloaded from byte 0
            val isAppend = response.status == HttpStatusCode.PartialContent
            val actualStartOffset = if (isAppend) downloadedBytes else 0L

            val rawSink = if (isAppend) fileSystem.appendingSink(partialPath) else fileSystem.sink(partialPath)
            val hashingSink = if (request.expectedChecksum != null && actualStartOffset == 0L) {
                when (request.expectedChecksum.algorithm) {
                    ChecksumAlgorithm.MD5 -> HashingSink.md5(rawSink)
                    ChecksumAlgorithm.SHA_256 -> HashingSink.sha256(rawSink)
                }
            } else null

            val sink: BufferedSink = (hashingSink ?: rawSink).buffer()
            val channel = response.bodyAsChannel()

            var inlineValidated = false
            try {
                val buffer = ByteArray(config.bufferSizeBytes)
                var downloaded = actualStartOffset
                val totalLength = if (isAppend && totalBytes != null) (downloadedBytes + totalBytes) else totalBytes

                var lastEmitTime = 0L
                var lastEmitBytes = 0L
                var lastSampleTime = 0L
                var lastSampleBytes = 0L

                while (!channel.isClosedForRead) {
                    val read = readWithIdleTimeout(channel, buffer, config.idleTimeout)
                    if (read < 0) break
                    if (read == 0) continue
                    sink.write(buffer, 0, read)
                    downloaded += read

                    val now = Clock.System.now().toEpochMilliseconds()
                    val timeDelta = now - lastEmitTime
                    val byteDelta = downloaded - lastEmitBytes
                    val shouldEmit = lastEmitTime == 0L ||
                        byteDelta >= request.progressGranularity.minBytesDelta ||
                        timeDelta >= request.progressGranularity.minInterval.inWholeMilliseconds

                    if (shouldEmit) {
                        val sampleTimeDelta = max(1L, now - lastSampleTime)
                        val sampleByteDelta = downloaded - lastSampleBytes
                        val speed = if (lastSampleTime == 0L) null else ((sampleByteDelta * 1000L) / sampleTimeDelta).coerceAtLeast(0L)
                        val eta = if (speed != null && speed > 0L && totalLength != null) {
                            (((totalLength - downloaded).coerceAtLeast(0L) * 1000L) / speed).milliseconds
                        } else null

                        sendEvent(
                            InternalEvent.Progress(
                                id,
                                DownloadProgress(downloaded, totalLength, speed, eta)
                            )
                        )
                        lastEmitTime = now
                        lastEmitBytes = downloaded
                        if (lastSampleTime == 0L || timeDelta >= 500L) {
                            lastSampleTime = now
                            lastSampleBytes = downloaded
                        }
                    }
                }

                if (totalLength != null && downloaded != totalLength) {
                    throw DownloadIntegrityException(totalLength, downloaded)
                }

                sink.flush()

                // 4. Validate progressive checksum on-the-fly (if downloaded from scratch)
                if (hashingSink != null && request.expectedChecksum != null) {
                    val actualHex = hashingSink.hash.hex()
                    if (!actualHex.equals(request.expectedChecksum.expectedHex, ignoreCase = true)) {
                        throw ChecksumMismatchException(request.expectedChecksum.expectedHex, actualHex)
                    }
                    inlineValidated = true
                }
            } finally {
                sink.close()
            }
            inlineValidated
        }
    }

    private suspend fun writeSegmentedDownloadToPath(
        id: DownloadId,
        request: DownloadRequest,
        config: WorkerConfig,
        partialPath: Path,
    ): Boolean {
        val metadata = fetchSegmentedMetadata(id, request, config.idleTimeout) ?: return false
        val origin = originKey(request.url)
        var desiredSegments = min(resolveSegmentCount(request.segmentation, metadata.totalBytes).toLong(), metadata.totalBytes)
            .toInt()
            .coerceAtLeast(1)

        while (desiredSegments > 1) {
            val segmentCount = recommendedSegmentCount(origin, desiredSegments)
            if (segmentCount <= 1) return false

            val usableSpace = PlatformSpaceChecker.getUsableSpace(partialPath)
            if (usableSpace != -1L) {
                val safetyMargin = 10L * 1024L * 1024L
                if (usableSpace < metadata.totalBytes + safetyMargin) {
                    throw DiskNoSpaceException(metadata.totalBytes, usableSpace)
                }
            }

            val ranges = buildSegmentRanges(metadata.totalBytes, segmentCount)
            val segmentPaths = ranges.indices.map { index -> "$partialPath.seg$index".toPath() }
            segmentPaths.forEach { fileSystem.delete(it, mustExist = false) }

            try {
                val progressMutex = Mutex()
                val segmentProgress = LongArray(ranges.size)
                var lastEmitTime = 0L
                var lastEmitBytes = 0L
                coroutineScope {
                    ranges.mapIndexed { index, range ->
                        async {
                            downloadSegment(
                                request = request,
                                config = config,
                                metadata = metadata,
                                range = range,
                                segmentPath = segmentPaths[index],
                                onSegmentProgress = { bytesInSegment ->
                                    val progress = progressMutex.withLock {
                                        segmentProgress[index] = bytesInSegment
                                        val totalDownloaded = segmentProgress.sum()
                                        val now = Clock.System.now().toEpochMilliseconds()
                                        val shouldEmit = lastEmitTime == 0L ||
                                            totalDownloaded - lastEmitBytes >= request.progressGranularity.minBytesDelta ||
                                            now - lastEmitTime >= request.progressGranularity.minInterval.inWholeMilliseconds ||
                                            totalDownloaded == metadata.totalBytes
                                        if (!shouldEmit) return@withLock null
                                        lastEmitTime = now
                                        lastEmitBytes = totalDownloaded
                                        DownloadProgress(
                                            bytesDownloaded = totalDownloaded,
                                            totalBytes = metadata.totalBytes,
                                            speedBytesPerSecond = null,
                                            eta = null,
                                        )
                                    }
                                    progress?.let { sendEvent(InternalEvent.Progress(id, it)) }
                                }
                            )
                        }
                    }.awaitAll()
                }

                fileSystem.delete(partialPath, mustExist = false)
                fileSystem.sink(partialPath).buffer().use { sink ->
                    segmentPaths.forEach { segmentPath ->
                        fileSystem.source(segmentPath).use { source ->
                            sink.writeAll(source)
                        }
                    }
                }
                segmentPaths.forEach { fileSystem.delete(it, mustExist = false) }
                val actualSize = fileSystem.metadata(partialPath).size ?: 0L
                if (actualSize != metadata.totalBytes) {
                    throw DownloadIntegrityException(metadata.totalBytes, actualSize)
                }
                recordOriginSuccess(origin, segmentCount)
                return true
            } catch (t: Throwable) {
                val shouldDelete = if (t is CancellationException) {
                    config.deletePartialOnCancel
                } else {
                    config.deletePartialOnFailure
                }
                if (shouldDelete) {
                    segmentPaths.forEach { fileSystem.delete(it, mustExist = false) }
                    fileSystem.delete(partialPath, mustExist = false)
                }
                val httpFailure = t.findDownloadHttpException()
                if (httpFailure?.code == 429 && segmentCount > 1) {
                    val retryDelay = httpFailure.retryAfter
                        ?: computeAdaptiveRateLimitDelay(segmentCount)
                    recordOriginRateLimit(origin, segmentCount, retryDelay)
                    delay(retryDelay)
                    desiredSegments = (segmentCount / 2).coerceAtLeast(1)
                    if (desiredSegments <= 1) return false
                    continue
                } else {
                    throw t
                }
            }
        }
        return false
    }

    private fun Throwable.findDownloadHttpException(): DownloadHttpException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is DownloadHttpException) return current
            current = current.cause
        }
        return null
    }

    private fun computeAdaptiveRateLimitDelay(segmentCount: Int): Duration {
        val base = 750L * segmentCount
        val jitter = random.nextLong(0L, 750L)
        return (base + jitter).coerceAtMost(15_000L).milliseconds
    }

    private suspend fun acquireOriginPermit(origin: String): OriginPermit {
        while (true) {
            val wait = originMutex.withLock {
                val now = timeSource.markNow()
                val profile = originProfiles.getOrPut(origin) { OriginProfile() }
                val penalty = profile.penaltyUntil
                when {
                    penalty != null && penalty > now -> penalty - now
                    profile.activeRequests < profile.maxParallelRequests -> {
                        profile.activeRequests += 1
                        return OriginPermit(origin)
                    }
                    else -> 50.milliseconds
                }
            }
            delay(wait.coerceAtLeast(1.milliseconds))
        }
    }

    private suspend fun recommendedSegmentCount(origin: String, desiredSegments: Int): Int =
        originMutex.withLock {
            val now = timeSource.markNow()
            val profile = originProfiles.getOrPut(origin) { OriginProfile() }
            val rememberedCap = profile.lastGoodSegmentCount ?: desiredSegments
            val underPenalty = profile.penaltyUntil?.let { it > now } ?: false
            val pressureCap = if (underPenalty) 1 else profile.maxParallelRequests
            min(desiredSegments, min(rememberedCap, pressureCap)).coerceAtLeast(1)
        }

    private suspend fun recordOriginRateLimit(origin: String, segmentCount: Int, retryDelay: Duration) {
        originMutex.withLock {
            val profile = originProfiles.getOrPut(origin) { OriginProfile() }
            profile.recentRateLimits += 1
            profile.maxParallelRequests = (min(profile.maxParallelRequests, segmentCount) / 2).coerceAtLeast(1)
            profile.lastGoodSegmentCount = min(profile.lastGoodSegmentCount ?: profile.maxParallelRequests, profile.maxParallelRequests)
            val candidate = timeSource.markNow() + retryDelay
            val current = profile.penaltyUntil
            profile.penaltyUntil = if (current == null || candidate > current) candidate else current
        }
    }

    private suspend fun recordOriginSuccess(origin: String, segmentCount: Int) {
        originMutex.withLock {
            val profile = originProfiles.getOrPut(origin) { OriginProfile() }
            profile.recentRateLimits = 0
            profile.lastGoodSegmentCount = segmentCount
            if (profile.maxParallelRequests < segmentCount) {
                profile.maxParallelRequests = segmentCount
            }
        }
    }

    private data class SegmentedMetadata(
        val totalBytes: Long,
        val eTag: String?,
        val lastModified: String?,
    )

    private data class SegmentRange(
        val start: Long,
        val endInclusive: Long,
    ) {
        val length: Long = endInclusive - start + 1L
    }

    private suspend fun fetchSegmentedMetadata(
        id: DownloadId,
        request: DownloadRequest,
        responseTimeout: Duration,
    ): SegmentedMetadata? {
        val statement = httpClient.prepareRequest(request.url) {
            method = HttpMethod.Head
            headers {
                appendCallerHeaders(request)
                appendIdentityEncodingIfAbsent(request)
            }
        }
        return try {
            statement.executeWithResponseTimeout(responseTimeout) { response ->
                when {
                    response.status == HttpStatusCode.MethodNotAllowed || response.status == HttpStatusCode.NotImplemented -> {
                        fetchRangeProbeMetadata(id, request, responseTimeout)
                    }
                    response.status.value !in 200..299 -> {
                        fetchRangeProbeMetadata(id, request, responseTimeout)
                            ?: if (request.segmentation is DownloadSegmentation.MaxSegments) {
                                throw DownloadHttpException(
                                    code = response.status.value,
                                    message = response.status.description,
                                    retryAfter = response.headers.retryAfter(),
                                )
                            } else {
                                null
                            }
                    }
                    response.headers[HttpHeaders.AcceptRanges]
                        ?.split(',')
                        ?.any { it.trim().equals("bytes", ignoreCase = true) } != true -> {
                        fetchRangeProbeMetadata(id, request, responseTimeout)
                    }
                    else -> {
                        val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 0L }
                            ?: return@executeWithResponseTimeout fetchRangeProbeMetadata(id, request, responseTimeout)
                        val eTag = response.headers[HttpHeaders.ETag]?.trim()
                        val lastModified = response.headers[HttpHeaders.LastModified]?.trim()
                        publishSegmentedMetadata(id, eTag, lastModified)
                        SegmentedMetadata(totalBytes, eTag, lastModified)
                    }
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            fetchRangeProbeMetadata(id, request, responseTimeout)
                ?: if (request.segmentation is DownloadSegmentation.MaxSegments) throw t else null
        }
    }

    private suspend fun fetchRangeProbeMetadata(
        id: DownloadId,
        request: DownloadRequest,
        responseTimeout: Duration,
    ): SegmentedMetadata? {
        val statement = httpClient.prepareRequest(request.url) {
            method = HttpMethod.Get
            headers {
                appendCallerHeaders(request)
                appendIdentityEncodingIfAbsent(request)
                append(HttpHeaders.Range, "bytes=0-0")
            }
        }
        return try {
            statement.executeWithResponseTimeout(responseTimeout) { response ->
                if (response.status != HttpStatusCode.PartialContent) return@executeWithResponseTimeout null
                val totalBytes = response.headers[HttpHeaders.ContentRange]
                    ?.let { parseTotalBytesFromContentRange(it) }
                    ?: response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 1L }
                    ?: return@executeWithResponseTimeout null
                val eTag = response.headers[HttpHeaders.ETag]?.trim()
                val lastModified = response.headers[HttpHeaders.LastModified]?.trim()
                publishSegmentedMetadata(id, eTag, lastModified)
                SegmentedMetadata(totalBytes, eTag, lastModified)
            }
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            null
        }
    }

    private fun publishSegmentedMetadata(id: DownloadId, eTag: String?, lastModified: String?) {
        if (eTag != null || lastModified != null) {
            sendEvent(InternalEvent.MetadataLearned(id, ServerMetadata(eTag, lastModified)))
        }
    }

    private fun parseTotalBytesFromContentRange(contentRange: String): Long? =
        contentRange.substringAfter('/', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() && it != "*" }
            ?.toLongOrNull()

    private suspend fun downloadSegment(
        request: DownloadRequest,
        config: WorkerConfig,
        metadata: SegmentedMetadata,
        range: SegmentRange,
        segmentPath: Path,
        onSegmentProgress: suspend (Long) -> Unit,
    ) {
        val statement = httpClient.prepareRequest(request.url) {
            method = HttpMethod.Get
            disableTotalRequestTimeout()
            headers {
                appendCallerHeaders(request)
                appendIdentityEncodingIfAbsent(request)
                append(HttpHeaders.Range, "bytes=${range.start}-${range.endInclusive}")
                metadata.eTag?.let { append(HttpHeaders.IfRange, it) }
                    ?: metadata.lastModified?.let { append(HttpHeaders.IfRange, it) }
            }
        }
        val permit = acquireOriginPermit(originKey(request.url))
        try {
            statement.executeWithResponseTimeout(config.idleTimeout) { response ->
                if (response.status != HttpStatusCode.PartialContent) {
                    throw DownloadHttpException(
                        code = response.status.value,
                        message = response.status.description,
                        retryAfter = response.headers.retryAfter(),
                    )
                }
                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength != null && contentLength != range.length) {
                    throw DownloadIntegrityException(range.length, contentLength)
                }
                response.headers[HttpHeaders.ContentRange]?.let { contentRange ->
                    validateContentRange(contentRange, range, metadata.totalBytes)
                }

                val channel = response.bodyAsChannel()
                fileSystem.sink(segmentPath).buffer().use { sink ->
                    val buffer = ByteArray(config.bufferSizeBytes)
                    var downloaded = 0L
                    while (!channel.isClosedForRead) {
                        val read = readWithIdleTimeout(channel, buffer, config.idleTimeout)
                        if (read < 0) break
                        if (read == 0) continue
                        sink.write(buffer, 0, read)
                        downloaded += read
                        onSegmentProgress(downloaded)
                    }
                    sink.flush()
                    if (downloaded != range.length) {
                        throw DownloadIntegrityException(range.length, downloaded)
                    }
                }
            }
        } finally {
            permit.release()
        }
    }

    /**
     * A download body may legitimately stream for hours, so a total request timeout
     * (CIO defaults to 15 s even without the HttpTimeout plugin) must never cut it off.
     * Setting the per-request timeout capability makes every Ktor engine, and an installed
     * HttpTimeout plugin, treat this request as unbounded; liveness is governed instead by
     * [executeWithResponseTimeout] and [readWithIdleTimeout]. Connect and socket timeouts
     * stay whatever the client configured.
     */
    private fun HttpRequestBuilder.disableTotalRequestTimeout() {
        if (HttpTimeoutCapability in httpClient.engine.supportedCapabilities) {
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }
    }

    /**
     * Executes this statement but fails with [ResponseHeadTimeoutException] when the response
     * head does not arrive within [timeout]. Once [block] runs, body liveness belongs to
     * [readWithIdleTimeout]. A non-positive [timeout] disables the guard.
     */
    private suspend fun <T> HttpStatement.executeWithResponseTimeout(
        timeout: Duration,
        block: suspend (HttpResponse) -> T,
    ): T {
        if (timeout <= Duration.ZERO) return execute { block(it) }
        return coroutineScope {
            val watchdog = launch {
                delay(timeout)
                throw ResponseHeadTimeoutException(timeout)
            }
            try {
                execute { response ->
                    watchdog.cancel()
                    block(response)
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private suspend fun readWithIdleTimeout(channel: ByteReadChannel, buffer: ByteArray, idleTimeout: Duration): Int {
        return if (idleTimeout > Duration.ZERO) {
            withTimeoutOrNull(idleTimeout) { channel.readAvailable(buffer, 0, buffer.size) }
                ?: throw DownloadStallException()
        } else {
            channel.readAvailable(buffer, 0, buffer.size)
        }
    }

    private fun buildSegmentRanges(totalBytes: Long, segmentCount: Int): List<SegmentRange> {
        val baseSize = totalBytes / segmentCount
        val remainder = totalBytes % segmentCount
        var start = 0L
        return (0 until segmentCount).map { index ->
            val length = baseSize + if (index < remainder) 1L else 0L
            val end = start + length - 1L
            SegmentRange(start, end).also {
                start = end + 1L
            }
        }
    }

    private fun HeadersBuilder.appendCallerHeaders(request: DownloadRequest) {
        request.headers.entries().forEach { (name, values) ->
            values.forEach { value -> append(name, value) }
        }
    }

    private fun HeadersBuilder.appendIdentityEncodingIfAbsent(request: DownloadRequest) {
        if (request.headers[HttpHeaders.AcceptEncoding] == null) {
            append(HttpHeaders.AcceptEncoding, "identity")
        }
    }

    private fun validateContentRange(contentRange: String, range: SegmentRange, totalBytes: Long) {
        val normalized = contentRange.trim()
        if (!normalized.startsWith("bytes ", ignoreCase = true)) {
            throw DownloadIntegrityException(range.length, 0L)
        }
        val rangeAndTotal = normalized.substringAfter(' ').split('/')
        if (rangeAndTotal.size != 2) throw DownloadIntegrityException(range.length, 0L)
        val startAndEnd = rangeAndTotal[0].split('-')
        if (startAndEnd.size != 2) throw DownloadIntegrityException(range.length, 0L)
        val actualStart = startAndEnd[0].toLongOrNull()
        val actualEnd = startAndEnd[1].toLongOrNull()
        val actualTotal = rangeAndTotal[1].toLongOrNull()
        if (actualStart != range.start || actualEnd != range.endInclusive || actualTotal != totalBytes) {
            throw DownloadIntegrityException(range.length, (actualEnd ?: -1L) - (actualStart ?: 0L) + 1L)
        }
    }

    private fun validateChecksum(path: Path, checksum: ChecksumSpec) {
        val actualHex = fileSystem.source(path).use { source ->
            val hashSource = when (checksum.algorithm) {
                ChecksumAlgorithm.MD5 -> HashingSource.md5(source)
                ChecksumAlgorithm.SHA_256 -> HashingSource.sha256(source)
            }
            hashSource.buffer().use { bufferedSource ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = bufferedSource.read(buffer)
                    if (read < 0) break
                }
            }
            hashSource.hash.hex()
        }
        if (!actualHex.equals(checksum.expectedHex, ignoreCase = true)) {
            throw ChecksumMismatchException(checksum.expectedHex, actualHex)
        }
    }

    private fun reserveDestination(
        request: DownloadRequest,
        id: DownloadId,
        discardStalePartial: Boolean = false,
    ): Path {
        resolvedDestinations.remove(id)?.let { previous ->
            if (destinationReservations[previous] == id) destinationReservations.remove(previous)
        }
        val basePath = destinationPathFor(request, id)
        val resolved = resolveDestinationPath(basePath, request.effectiveOverwritePolicy(), id)
        destinationReservations[resolved] = id
        resolvedDestinations[id] = resolved
        try {
            if (discardStalePartial) {
                fileSystem.delete(partialPathFor(resolved, id), mustExist = false)
            }
        } catch (t: Throwable) {
            releaseDestination(id)
            throw t
        }
        return resolved
    }

    private fun resolveDestinationPath(basePath: Path, overwritePolicy: OverwritePolicy, id: DownloadId): Path {
        val reservedByOther = destinationReservations[basePath]?.takeIf { it != id }
        if (overwritePolicy == OverwritePolicy.REPLACE) {
            if (reservedByOther != null) throw DownloadFileExistsException(basePath)
            return basePath
        }
        if (overwritePolicy == OverwritePolicy.FAIL_IF_EXISTS) {
            if (fileSystem.exists(basePath) || reservedByOther != null) throw DownloadFileExistsException(basePath)
            return basePath
        }
        if (!fileSystem.exists(basePath) && reservedByOther == null) return basePath
        val parent = basePath.parent ?: ".".toPath()
        val name = basePath.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "${parent.toString().trimEnd('/')}/$stem($index)$ext".toPath()
            if (!fileSystem.exists(candidate) && destinationReservations[candidate] == null) return candidate
            index++
        }
    }

    private fun ensureParentDirectory(path: Path) {
        val parent = path.parent ?: return
        fileSystem.createDirectories(parent)
    }

    private fun finalizePartialFile(
        partialPath: Path,
        finalPath: Path,
        overwritePolicy: OverwritePolicy,
        id: DownloadId,
    ) {
        if (overwritePolicy != OverwritePolicy.REPLACE && fileSystem.exists(finalPath)) {
            throw DownloadFileExistsException(finalPath)
        }
        if (overwritePolicy != OverwritePolicy.REPLACE || !fileSystem.exists(finalPath)) {
            fileSystem.atomicMove(partialPath, finalPath)
            return
        }

        val backupPath = "$finalPath.yukitoki-${id.value}.backup".toPath()
        fileSystem.delete(backupPath, mustExist = false)
        fileSystem.atomicMove(finalPath, backupPath)
        try {
            fileSystem.atomicMove(partialPath, finalPath)
        } catch (t: Throwable) {
            if (!fileSystem.exists(finalPath) && fileSystem.exists(backupPath)) {
                fileSystem.atomicMove(backupPath, finalPath)
            }
            throw t
        }
        // The replacement is already committed. A best-effort cleanup failure must not
        // turn a successful download into a reported failure while the new file is present.
        runCatching { fileSystem.delete(backupPath, mustExist = false) }
    }

    private fun cleanupPartialIfNeeded(partialPath: Path, onCancel: Boolean, config: WorkerConfig) {
        val shouldDelete = if (onCancel) config.deletePartialOnCancel else config.deletePartialOnFailure
        if (shouldDelete) fileSystem.delete(partialPath, mustExist = false)
    }

    private fun shouldRetry(failure: DownloadFailure, policy: RetryPolicy): Boolean = when (failure) {
        is DownloadFailure.Network -> true
        is DownloadFailure.Timeout -> true
        is DownloadFailure.HttpStatus -> when {
            failure.code >= 500 -> policy.retryOnHttp5xx
            failure.code == 408 -> policy.retryOnHttp408
            failure.code == 429 -> policy.retryOnHttp429
            else -> false
        }
        else -> false
    }

    private fun computeBackoffDelay(attempt: Int, policy: RetryPolicy): Duration {
        val exp = policy.initialDelay.inWholeMilliseconds * policy.multiplier.pow((attempt - 1).toDouble())
        val bounded = exp.toLong().coerceAtMost(policy.maxDelay.inWholeMilliseconds)
        val jitterSpan = (bounded * policy.jitterRatio).toLong()
        if (jitterSpan <= 0) return bounded.milliseconds
        val jitter = random.nextLong(from = -jitterSpan, until = jitterSpan + 1)
        return (bounded + jitter).coerceAtLeast(0L).milliseconds
    }

    private fun mapThrowable(throwable: Throwable, path: Path): DownloadFailure = when (throwable) {
        is DownloadHttpException -> DownloadFailure.HttpStatus(
            code = throwable.code,
            message = throwable.message,
            retryAfter = throwable.retryAfter,
        )
        is HttpRequestTimeoutException -> DownloadFailure.Timeout(throwable.message ?: "Request timeout")
        is DownloadStallException -> DownloadFailure.Timeout(throwable.message ?: "Download stalled")
        is ResponseHeadTimeoutException -> DownloadFailure.Timeout(throwable.message ?: "Response timed out")
        is DownloadIntegrityException -> DownloadFailure.IntegrityMismatch(throwable.expected, throwable.actual)
        is DownloadFileExistsException -> DownloadFailure.AlreadyExists(throwable.path, "Target file already exists: ${throwable.path}")
        is DiskNoSpaceException -> DownloadFailure.NoSpace(path, throwable.message)
        is ChecksumMismatchException -> DownloadFailure.ChecksumMismatch(throwable.expected, throwable.actual)
        is ServerFileChangedException -> DownloadFailure.ServerChanged(throwable.message ?: "Server file changed mid-download")
        is IllegalStateException -> DownloadFailure.FileSystem(throwable.message ?: "Invalid download state")
        is IOException -> {
            val msg = throwable.message.orEmpty()
            when {
                msg.contains("permission", ignoreCase = true) -> DownloadFailure.PermissionDenied(path, msg)
                msg.contains("space", ignoreCase = true) || msg.contains("disk full", ignoreCase = true) -> DownloadFailure.NoSpace(path, msg)
                msg.looksLikeNetworkIoFailure() -> DownloadFailure.Network(msg.ifBlank { "Network IO error" })
                else -> DownloadFailure.FileSystem(msg.ifBlank { "File system IO error at $path" })
            }
        }
        else -> DownloadFailure.Unexpected(throwable.message ?: throwable::class.simpleName ?: "Unexpected error")
    }

    private fun String.looksLikeNetworkIoFailure(): Boolean {
        val networkTerms = listOf(
            "connect",
            "connection",
            "socket",
            "stream",
            "host",
            "dns",
            "tls",
            "ssl",
            "reset",
            "refused",
            "route",
            "protocol",
            "unexpected end",
        )
        return networkTerms.any { contains(it, ignoreCase = true) }
    }

    private fun destinationPathFor(request: DownloadRequest, id: DownloadId): Path =
        when (val destination = request.destination) {
            DownloadDestination.Default -> joinPath(
                DownloadLocations.downloads,
                inferFileNameFromUrl(request.url) ?: "download-${id.value}.bin",
            )
            is DownloadDestination.Directory -> joinPath(
                destination.path,
                destination.fileName ?: inferFileNameFromUrl(request.url) ?: "download-${id.value}.bin",
            )
            is DownloadDestination.File -> destination.path
        }

    private fun DownloadRequest.effectiveOverwritePolicy(): OverwritePolicy =
        when (overwritePolicy) {
            OverwritePolicy.DEFAULT -> when (destination) {
                DownloadDestination.Default, is DownloadDestination.Directory -> OverwritePolicy.RENAME_UNIQUE
                is DownloadDestination.File -> OverwritePolicy.FAIL_IF_EXISTS
            }
            else -> overwritePolicy
        }

    private fun joinPath(directory: Path, fileName: String): Path =
        "${directory.toString().trimEnd('/')}/$fileName".toPath()

    private fun partialPathFor(finalPath: Path, id: DownloadId): Path =
        "$finalPath.yukitoki-${id.value}.part".toPath()

    private fun inferFileNameFromUrl(url: String): String? {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val segment = noQuery.substringAfterLast('/', "")
        if (segment.isBlank()) return null
        return segment.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.ifBlank { null }
    }

    private fun enqueueIdByPriority(id: DownloadId, priority: DownloadPriority) {
        when (priority) {
            DownloadPriority.HIGH -> pendingHigh.addLast(id)
            DownloadPriority.NORMAL -> pendingNormal.addLast(id)
            DownloadPriority.LOW -> pendingLow.addLast(id)
        }
    }

    private fun dequeueNext(): DownloadId? {
        return when {
            pendingHigh.isNotEmpty() -> pendingHigh.removeFirst()
            pendingNormal.isNotEmpty() -> pendingNormal.removeFirst()
            pendingLow.isNotEmpty() -> pendingLow.removeFirst()
            else -> null
        }
    }

    private fun removeFromPending(id: DownloadId): Boolean =
        pendingHigh.remove(id) || pendingNormal.remove(id) || pendingLow.remove(id)

    private fun currentPendingList(): List<DownloadId> = buildList {
        addAll(pendingHigh)
        addAll(pendingNormal)
        addAll(pendingLow)
    }

    private fun terminalResultFromSnapshot(snapshot: DownloadSnapshot?): DownloadResult? {
        return when (val state = snapshot?.state) {
            is DownloadState.Succeeded -> DownloadResult.Success(state.result)
            is DownloadState.Failed -> DownloadResult.Failure(state.failure)
            is DownloadState.Cancelled -> DownloadResult.Cancelled
            else -> null
        }
    }

    private fun markTerminal(id: DownloadId) {
        releaseDestination(id)
        terminalOrder.remove(id)
        terminalOrder.addLast(id)
        while (terminalOrder.size > runtimeConfig.maxRetainedFinished) {
            forgetDownload(terminalOrder.removeFirst())
        }
    }

    private fun releaseDestination(id: DownloadId) {
        resolvedDestinations.remove(id)?.let { path ->
            if (destinationReservations[path] == id) destinationReservations.remove(path)
        }
    }

    private fun forgetDownload(id: DownloadId) {
        removeFromPending(id)
        terminalOrder.remove(id)
        serverMetadata.remove(id)
        releaseDestination(id)
        snapshots.remove(id)
        terminalWaiters.remove(id)?.forEach { waiter ->
            if (!waiter.isCompleted) waiter.complete(null)
        }
    }

    private fun DownloadState.isTerminal(): Boolean =
        this is DownloadState.Succeeded || this is DownloadState.Failed || this is DownloadState.Cancelled

    private fun sendEvent(event: InternalEvent) {
        // The inbox is UNLIMITED, so trySend never blocks and never fails for a live
        // channel — this stays safe to call from a cancelled worker (no suspension).
        inbox.trySend(InternalMessage.WorkerEvent(event))
    }

    private fun completeWaiters(id: DownloadId, result: DownloadResult) {
        terminalWaiters.remove(id)?.forEach { waiter ->
            if (!waiter.isCompleted) waiter.complete(result)
        }
    }

    private fun publishState() {
        val pending = currentPendingList()
        val pendingIndex = HashMap<DownloadId, Int>(pending.size)
        pending.forEachIndexed { index, id -> pendingIndex[id] = index }
        // Only re-copy snapshots whose queue position actually changed, so a flood of
        // progress events doesn't reallocate every snapshot on every tick.
        snapshots.keys.toList().forEach { id ->
            val snap = snapshots[id] ?: return@forEach
            val newPosition = pendingIndex[id]
            if (snap.queuePosition != newPosition) {
                snapshots[id] = snap.copy(queuePosition = newPosition)
            }
        }
        _state.value = DownloadManagerState(
            snapshots = snapshots.toMap(),
            pending = pending,
            active = active.keys.toSet(),
            maxConcurrentDownloads = maxConcurrentDownloads,
        )
    }

    private fun DownloadRequest.unsupportedByDefaultManagerReason(): String? {
        if (Url(url).protocol.name !in filesystemBackedSchemes) {
            return "DefaultDownloadManager supports http and https URLs only."
        }
        if (headers[HttpHeaders.Range] != null) {
            return "Range is managed internally for resume support and cannot be supplied by callers."
        }
        return null
    }

    private fun resolveConcurrentDownloads(concurrency: DownloadConcurrency): Int =
        when (concurrency) {
            DownloadConcurrency.Default -> 3
            is DownloadConcurrency.Fixed -> concurrency.downloads
        }

    private fun resolveSegmentCount(segmentation: DownloadSegmentation, totalBytes: Long): Int =
        when (segmentation) {
            DownloadSegmentation.Auto -> autoSegmentCount(totalBytes)
            DownloadSegmentation.Single -> 1
            is DownloadSegmentation.MaxSegments -> segmentation.segments
        }

    private fun autoSegmentCount(totalBytes: Long): Int {
        val mib = 1024L * 1024L
        return when {
            totalBytes < 16L * mib -> 1
            totalBytes < 128L * mib -> 2
            totalBytes < 512L * mib -> 4
            else -> 6
        }
    }

    private fun originKey(url: String): String {
        return try {
            val parsed = Url(url)
            val port = parsed.port.takeIf { it > 0 } ?: parsed.protocol.defaultPort
            "${parsed.protocol.name}://${parsed.host}:$port"
        } catch (_: Throwable) {
            url.substringBefore('?').substringBefore('#').substringBefore('/', missingDelimiterValue = url)
        }
    }

    private fun Headers.retryAfter(): Duration? {
        val raw = this[HttpHeaders.RetryAfter]?.trim() ?: return null
        return raw.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?.seconds
    }
}

private data object PauseCancellationException : CancellationException("Paused by user")

private class DownloadHttpException(
    val code: Int,
    val retryAfter: Duration? = null,
    message: String?,
) : RuntimeException(message)

private class DownloadIntegrityException(
    val expected: Long,
    val actual: Long,
) : RuntimeException("Integrity mismatch: expected=$expected actual=$actual")

private class DownloadFileExistsException(
    val path: Path,
) : RuntimeException("File already exists: $path")

private class DiskNoSpaceException(
    required: Long,
    available: Long,
) : RuntimeException("Inadequate disk space: required=$required available=$available")

private class ChecksumMismatchException(
    val expected: String,
    val actual: String,
) : RuntimeException("Checksum mismatch: expected=$expected actual=$actual")

private class ServerFileChangedException(
    message: String,
) : RuntimeException(message)

private class DownloadStallException : RuntimeException("Download stalled: no bytes received within the idle timeout")

private class ResponseHeadTimeoutException(
    timeout: Duration,
) : RuntimeException("No response from server within the idle timeout ($timeout)")

private val filesystemBackedSchemes = setOf("http", "https")
