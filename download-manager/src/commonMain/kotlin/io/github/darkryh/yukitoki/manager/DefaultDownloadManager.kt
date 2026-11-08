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
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
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

@OptIn(DelicateCoroutinesApi::class)
internal class DefaultDownloadManager(
    private val httpClient: HttpClient,
    scope: CoroutineScope,
    private val fileSystem: FileSystem = PlatformDownloadFileSystem.default,
    private val config: DownloadManagerConfig = DownloadManagerConfig(),
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher,
    // Time source for origin-pacing decisions. Defaults to the monotonic clock (immune to
    // NTP steps); tests inject the coroutine test scheduler's time source so pacing delays
    // advance in lockstep with delay() under virtual time instead of racing the wall clock.
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    // Jitter source for adaptive rate-limit back-off; injectable so tests are reproducible.
    private val random: Random = Random.Default,
) : DownloadManager {
    private data class ServerMetadata(
        val eTag: String?,
        val lastModified: String?,
    )

    private data class OriginProfile(
        var activeRequests: Int = 0,
        var maxParallelRequests: Int = MAX_PARALLEL_REQUESTS_PER_ORIGIN,
        var penaltyUntil: ComparableTimeMark? = null,
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
        data class Progress(val id: DownloadId, val progress: DownloadProgress) : InternalEvent
        data class MetadataLearned(val id: DownloadId, val meta: ServerMetadata) : InternalEvent
        data class Succeeded(val id: DownloadId, val file: DownloadedFile) : InternalEvent
        /** [transient]: worth retrying even though [failure] alone would not say so. */
        data class Failed(
            val id: DownloadId,
            val failure: DownloadFailure,
            val transient: Boolean = false,
        ) : InternalEvent
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

    private var maxConcurrentDownloads = config.maxConcurrentDownloads

    /** Pending queues in dispatch order: [DownloadPriority] declares HIGH first. */
    private val pendingByPriority = DownloadPriority.entries.associateWith { ArrayDeque<DownloadId>() }
    private val active = linkedMapOf<DownloadId, Job>()
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

    // Coordinator-confined: completions held back until the batch's state is published.
    private val repliesAfterPublish = ArrayList<() -> Unit>()
    private var finishedSincePublish = 0

    private val _state = MutableStateFlow(
        DownloadManagerState(
            snapshots = emptyMap(),
            pending = emptyList(),
            active = emptySet(),
            maxConcurrentDownloads = maxConcurrentDownloads,
        )
    )

    init {
        // Reserving destinations and removing files touch the disk, so the coordinator must stay
        // off a borrowed UI scope's dispatcher. One thread at a time keeps its state confined.
        internalScope.launch(ioDispatcher.limitedParallelism(1)) {
            try {
                coordinatorLoop()
            } finally {
                // Every way out comes through here: shutdown, close(), or a cancelled scope.
                withContext(NonCancellable) { settleAfterTeardown() }
            }
        }
    }

    internal suspend fun submitCommand(command: DownloadCommand): CommandResult {
        if (closed) return CommandResult.Rejected.ManagerClosed
        val reply = CompletableDeferred<CommandResult>()
        if (!inbox.trySend(InternalMessage.UserCommand(command, reply)).isSuccess) {
            return CommandResult.Rejected.ManagerClosed
        }
        return awaitUnlessClosed(reply) ?: CommandResult.Rejected.ManagerClosed
    }

    /** Waits for [reply], or returns null once the manager is gone and nobody will answer. */
    private suspend fun <T : Any> awaitUnlessClosed(reply: Deferred<T?>): T? = select {
        reply.onAwait { it }
        rootJob.onJoin { null }
    }

    override val state: StateFlow<DownloadManagerState> = _state.asStateFlow()

    override fun observe(downloadId: DownloadId): Flow<DownloadSnapshot?> =
        _state.map { it.snapshots[downloadId] }.distinctUntilChanged()

    override suspend fun await(downloadId: DownloadId): DownloadResult? {
        if (!closed) {
            val reply = CompletableDeferred<DownloadResult?>()
            if (inbox.trySend(InternalMessage.AwaitTerminal(downloadId, reply)).isSuccess) {
                awaitUnlessClosed(reply)?.let { return it }
                if (!closed && rootJob.isActive) return null
            }
        }
        // Teardown settles every download in state before rootJob completes.
        rootJob.join()
        return terminalResultFromSnapshot(_state.value.snapshots[downloadId])
    }

    override suspend fun enqueue(request: DownloadRequest): DownloadTask {
        val id = DownloadId.new()
        return when (val ack = submitCommand(DownloadCommand.Enqueue(request, id))) {
            CommandResult.Accepted -> ManagedDownloadTask(id)
            is CommandResult.Rejected -> throw DownloadRejectedException(ack.toFailure())
        }
    }

    override suspend fun download(request: DownloadRequest): DownloadResult {
        // Registered with the enqueue itself, so a download that finishes (and is pruned) before
        // this caller resumes still delivers its result.
        val outcome = CompletableDeferred<DownloadResult?>()
        val ack = submitCommand(DownloadCommand.Enqueue(request, DownloadId.new(), outcome))
        if (ack is CommandResult.Rejected) return DownloadResult.Failure(ack.toFailure())
        return awaitUnlessClosed(outcome) ?: DownloadResult.Failure(DownloadFailure.ManagerClosed)
    }

    private fun CommandResult.Rejected.toFailure(): DownloadFailure = when (this) {
        is CommandResult.Rejected.Failed -> failure
        CommandResult.Rejected.ManagerClosed -> DownloadFailure.ManagerClosed
        else -> DownloadFailure.Unexpected(toString())
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

    override suspend fun setMaxConcurrentDownloads(max: Int): CommandResult {
        require(max >= 1) { "max must be >= 1" }
        return submitCommand(DownloadCommand.SetMaxConcurrentDownloads(max))
    }

    override suspend fun remove(downloadId: DownloadId, deleteFile: Boolean): CommandResult =
        submitCommand(DownloadCommand.Remove(downloadId, deleteFile))

    override suspend fun clearFinished(): CommandResult =
        submitCommand(DownloadCommand.ClearFinished)

    override suspend fun shutdown(cancelActive: Boolean) {
        closed = true
        val reply = CompletableDeferred<Unit>()
        if (inbox.trySend(InternalMessage.Shutdown(cancelActive, reply)).isSuccess) {
            // A concurrent shutdown() or close() may end the coordinator before it answers.
            select {
                reply.onAwait {}
                rootJob.onJoin {}
            }
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
                ?: DownloadResult.Failure(DownloadFailure.ManagerClosed)
        override suspend fun pause(): CommandResult = this@DefaultDownloadManager.pause(id)
        override suspend fun resume(): CommandResult = this@DefaultDownloadManager.resume(id)
        override suspend fun cancel(): CommandResult = this@DefaultDownloadManager.cancel(id)
        override suspend fun retry(): CommandResult = this@DefaultDownloadManager.retry(id)
    }

    private suspend fun coordinatorLoop() {
        for (first in inbox) {
            // Publishing costs O(tracked downloads), so one state covers everything already
            // queued. The cap keeps state fresh and acks prompt under a sustained event flood.
            var message: InternalMessage? = first
            var handled = 0
            while (message != null) {
                // A finished download must reach state before anything can forget it.
                if (finishedSincePublish > 0 && message.forgetsFinished()) publishAndReply()
                handleMessage(message)
                if (!draining) dispatchIfPossible()
                if (draining && active.isEmpty()) break
                val retentionFull = config.maxRetainedFinished in 1..finishedSincePublish
                message = if (!retentionFull && ++handled < MAX_MESSAGES_PER_PUBLISH) {
                    inbox.tryReceive().getOrNull()
                } else {
                    null
                }
            }
            publishAndReply()
            if (draining && active.isEmpty()) return
        }
    }

    private fun publishAndReply() {
        publishState()
        finishedSincePublish = 0
        // Only now: whoever receives a reply must already see its effect in state.value.
        completeRepliesAfterPublish()
    }

    private fun InternalMessage.forgetsFinished(): Boolean =
        this is InternalMessage.UserCommand &&
            (command is DownloadCommand.Remove || command is DownloadCommand.ClearFinished)

    private fun handleMessage(message: InternalMessage) {
        when (message) {
            is InternalMessage.UserCommand -> handleUserCommand(message)
            is InternalMessage.WorkerEvent -> handleWorkerEvent(message.event)
            is InternalMessage.AwaitTerminal -> handleAwaitTerminal(message)
            is InternalMessage.RetryReady -> handleRetryReady(message.id)
            is InternalMessage.Shutdown -> beginShutdown(message)
        }
    }

    private fun <T> replyAfterPublish(reply: CompletableDeferred<T>, value: T) {
        repliesAfterPublish.add { reply.complete(value) }
    }

    private fun completeRepliesAfterPublish() {
        repliesAfterPublish.forEach { it() }
        repliesAfterPublish.clear()
    }

    private fun handleAwaitTerminal(message: InternalMessage.AwaitTerminal) {
        val snap = snapshots[message.id]
        if (snap == null) {
            // Unknown id: never enqueued (or already pruned). Don't register a waiter
            // that could hang forever — report "no such download".
            replyAfterPublish(message.reply, null)
            return
        }
        val terminal = terminalResultFromSnapshot(snap)
        if (terminal != null) {
            replyAfterPublish(message.reply, terminal)
            return
        }
        terminalWaiters.getOrPut(message.id) { mutableListOf() }.add(message.reply)
    }

    private fun beginShutdown(message: InternalMessage.Shutdown) {
        draining = true
        shutdownReply = message.reply
        pendingByPriority.values.forEach { it.clear() }
        snapshots.values
            .filter { it.id !in active && !it.state.isTerminal() }
            .map { it.id }
            .forEach { id ->
                val snapshot = snapshots[id] ?: return@forEach
                deletePartialAfterCancel(id, snapshot)
                snapshots[id] = snapshot.copy(state = DownloadState.Cancelled, queuePosition = null)
                completeWaiters(id, DownloadResult.Cancelled)
                markTerminal(id)
            }
        if (message.cancelActive) {
            active.values.forEach { it.cancel(CancellationException("shutdown")) }
        }
    }

    /**
     * Leaves nothing behind: each download ends in a terminal state, published, with no partial
     * left unless the config keeps it. rootJob can't complete until this returns, so anyone who
     * joins it sees the final state.
     */
    private suspend fun settleAfterTeardown() {
        draining = true
        active.values.toList().joinAll()
        // The workers have reported by now. A file that was saved must count as saved.
        while (true) {
            handleMessage(inbox.tryReceive().getOrNull() ?: break)
        }
        active.clear()
        resumeAfterPause.clear()
        pendingByPriority.values.forEach { it.clear() }
        unfinishedIds().forEach { id ->
            val snapshot = snapshots[id] ?: return@forEach
            deletePartialAfterCancel(id, snapshot)
            finishCancelled(id, snapshot)
        }
        terminalWaiters.values.flatten().forEach { replyAfterPublish(it, null) }
        terminalWaiters.clear()
        shutdownReply?.let { replyAfterPublish(it, Unit) }
        shutdownReply = null
        publishAndReply()
    }

    private fun handleUserCommand(message: InternalMessage.UserCommand) {
        if (draining) {
            replyAfterPublish(message.reply, CommandResult.Rejected.ManagerClosed)
            return
        }
        val ack = when (val cmd = message.command) {
            is DownloadCommand.Enqueue -> enqueueDownload(cmd)
            is DownloadCommand.Pause -> pauseDownload(cmd.id)
            is DownloadCommand.Resume -> resumeDownload(cmd.id)
            is DownloadCommand.Cancel -> cancelDownload(cmd.id)
            DownloadCommand.PauseAll -> {
                unfinishedIds().forEach(::pauseDownload)
                CommandResult.Accepted
            }
            DownloadCommand.ResumeAll -> {
                unfinishedIds().forEach(::resumeDownload)
                CommandResult.Accepted
            }
            DownloadCommand.CancelAll -> {
                unfinishedIds().forEach(::cancelDownload)
                CommandResult.Accepted
            }
            is DownloadCommand.Retry -> retryDownload(cmd.id)
            DownloadCommand.RetryAllFailed -> {
                snapshots.values.filter { it.state is DownloadState.Failed }
                    .map { it.id }
                    .forEach(::retryDownload)
                CommandResult.Accepted
            }
            is DownloadCommand.MoveWithinPriority -> moveWithinPriorityDownload(cmd.id, cmd.newPosition)
            is DownloadCommand.SetMaxConcurrentDownloads -> {
                maxConcurrentDownloads = cmd.max
                CommandResult.Accepted
            }
            is DownloadCommand.Remove -> removeDownload(cmd.id, cmd.deleteFile)
            DownloadCommand.ClearFinished -> clearFinishedDownloads()
        }
        replyAfterPublish(message.reply, ack)
    }

    private fun enqueueDownload(cmd: DownloadCommand.Enqueue): CommandResult {
        if (cmd.id in snapshots) {
            return CommandResult.Rejected.Failed(DownloadFailure.Unexpected("Duplicate download id: ${cmd.id.value}"))
        }
        val resolvedDestination = try {
            reserveDestination(cmd.request, cmd.id, discardStalePartial = true)
        } catch (t: Throwable) {
            return CommandResult.Rejected.Failed(t.toReservationFailure())
        }
        snapshots[cmd.id] = DownloadSnapshot(
            id = cmd.id,
            request = cmd.request,
            state = DownloadState.Queued,
            attempts = 0,
            queuePosition = null,
            resolvedDestination = resolvedDestination,
        )
        enqueueIdByPriority(cmd.id, cmd.request.priority)
        cmd.outcome?.let { terminalWaiters.getOrPut(cmd.id) { mutableListOf() }.add(it) }
        return CommandResult.Accepted
    }

    private fun Throwable.toReservationFailure(): DownloadFailure {
        val reason = message ?: "Unable to reserve the download destination"
        return when (this) {
            is DownloadFileExistsException -> DownloadFailure.AlreadyExists(path)
            is IOException, is IllegalStateException -> DownloadFailure.FileSystem(reason)
            else -> DownloadFailure.Unexpected(reason)
        }
    }

    private fun unfinishedIds(): List<DownloadId> =
        snapshots.values.filter { !it.state.isTerminal() }.map { it.id }

    private fun retryDownload(id: DownloadId): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected.NotFound
        if (snap.state !is DownloadState.Failed && snap.state !is DownloadState.Cancelled) {
            return CommandResult.Rejected.InvalidState(snap.state)
        }
        val resolved = try {
            reserveDestination(snap.request, id)
        } catch (t: Throwable) {
            return CommandResult.Rejected.Failed(t.toReservationFailure())
        }
        snapshots[id] = snap.copy(
            state = DownloadState.Queued,
            attempts = 0,
            resolvedDestination = resolved,
        )
        terminalOrder.remove(id)
        enqueueIdByPriority(id, snap.request.priority)
        return CommandResult.Accepted
    }

    private fun pauseDownload(id: DownloadId): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected.NotFound
        when (snap.state) {
            is DownloadState.Queued -> {
                removeFromPending(id)
                snapshots[id] = snap.copy(state = DownloadState.Paused)
                return CommandResult.Accepted
            }
            is DownloadState.Starting, is DownloadState.Running -> {
                snapshots[id] = snap.copy(state = DownloadState.Pausing)
                active[id]?.cancel(PauseCancellationException)
                return CommandResult.Accepted
            }
            is DownloadState.Paused, is DownloadState.Pausing -> return CommandResult.Accepted
            else -> return CommandResult.Rejected.InvalidState(snap.state)
        }
    }

    private fun resumeDownload(id: DownloadId): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected.NotFound
        return when (snap.state) {
            is DownloadState.Paused -> {
                snapshots[id] = snap.copy(state = DownloadState.Queued)
                enqueueIdByPriority(id, snap.request.priority)
                CommandResult.Accepted
            }
            is DownloadState.Pausing -> {
                resumeAfterPause.add(id)
                CommandResult.Accepted
            }
            is DownloadState.Queued, is DownloadState.Starting, is DownloadState.Running -> CommandResult.Accepted
            else -> CommandResult.Rejected.InvalidState(snap.state)
        }
    }

    private fun cancelDownload(id: DownloadId): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected.NotFound
        return when (snap.state) {
            is DownloadState.Queued, is DownloadState.Paused -> {
                removeFromPending(id)
                // No worker is left to clean up what a pause or failed attempt kept on disk.
                deletePartialAfterCancel(id, snap)
                snapshots[id] = snap.copy(state = DownloadState.Cancelled)
                serverMetadata.remove(id)
                completeWaiters(id, DownloadResult.Cancelled)
                markTerminal(id)
                CommandResult.Accepted
            }
            is DownloadState.Starting, is DownloadState.Running, is DownloadState.Pausing -> {
                snapshots[id] = snap.copy(state = DownloadState.Cancelling)
                resumeAfterPause.remove(id)
                active[id]?.cancel(CancellationException("cancel"))
                CommandResult.Accepted
            }
            is DownloadState.Cancelled, is DownloadState.Failed, is DownloadState.Succeeded -> CommandResult.Accepted
            is DownloadState.Cancelling -> CommandResult.Accepted
        }
    }

    private fun removeDownload(id: DownloadId, deleteFile: Boolean): CommandResult {
        val snapshot = snapshots[id] ?: return CommandResult.Rejected.NotFound
        if (!snapshot.state.isTerminal()) {
            return CommandResult.Rejected.InvalidState(snapshot.state)
        }
        if (deleteFile) {
            val path = (snapshot.state as? DownloadState.Succeeded)?.file?.path
            if (path != null) {
                try {
                    fileSystem.delete(path, mustExist = false)
                } catch (t: Throwable) {
                    return CommandResult.Rejected.Failed(
                        DownloadFailure.FileSystem(t.message ?: "Unable to delete completed file")
                    )
                }
            }
        }
        forgetDownload(id)
        return CommandResult.Accepted
    }

    private fun clearFinishedDownloads(): CommandResult {
        snapshots.values.filter { it.state.isTerminal() }.map { it.id }.forEach(::forgetDownload)
        return CommandResult.Accepted
    }

    /** Reorders [id] within its own priority tier; priority is preserved. */
    private fun moveWithinPriorityDownload(id: DownloadId, newPosition: Int): CommandResult {
        val snap = snapshots[id] ?: return CommandResult.Rejected.NotFound
        val queue = pendingByPriority.getValue(snap.request.priority)
        if (!queue.contains(id)) return CommandResult.Rejected.InvalidState(snap.state)
        if (newPosition !in queue.indices) return CommandResult.Rejected.InvalidPosition(newPosition)
        queue.remove(id)
        queue.add(newPosition, id)
        return CommandResult.Accepted
    }

    private fun handleWorkerEvent(event: InternalEvent) {
        when (event) {
            is InternalEvent.Progress -> {
                val snap = snapshots[event.id] ?: return
                // Progress already in the inbox must not overwrite a pause or cancel in flight.
                if (snap.state is DownloadState.Starting || snap.state is DownloadState.Running) {
                    snapshots[event.id] = snap.copy(state = DownloadState.Running(event.progress))
                }
            }
            is InternalEvent.MetadataLearned -> {
                serverMetadata[event.id] = event.meta
            }
            is InternalEvent.Succeeded -> {
                active.remove(event.id)
                resumeAfterPause.remove(event.id)
                serverMetadata.remove(event.id)
                val snap = snapshots[event.id] ?: return
                snapshots[event.id] = snap.copy(state = DownloadState.Succeeded(event.file), queuePosition = null)
                completeWaiters(event.id, DownloadResult.Success(event.file))
                markTerminal(event.id)
            }
            is InternalEvent.Failed -> {
                active.remove(event.id)
                val snap = snapshots[event.id] ?: return
                // The worker failed before it could see a pause or cancel: the user's intent wins.
                when (snap.state) {
                    is DownloadState.Cancelling -> {
                        resumeAfterPause.remove(event.id)
                        deletePartialAfterCancel(event.id, snap)
                        finishCancelled(event.id, snap)
                        return
                    }
                    is DownloadState.Pausing -> {
                        settlePaused(event.id, snap)
                        return
                    }
                    else -> resumeAfterPause.remove(event.id)
                }
                val canRetry = !draining &&
                    snap.attempts < snap.request.retryPolicy.maxAttempts &&
                    (event.transient || shouldRetry(event.failure, snap.request.retryPolicy))
                if (canRetry) {
                    snapshots[event.id] = snap.copy(state = DownloadState.Queued)
                    scheduleRetry(event.id, snap.attempts, snap.request.retryPolicy, event.failure)
                } else {
                    // Only now: a retry resumes from what this attempt already wrote.
                    deletePartialAfterFailure(event.id, snap)
                    serverMetadata.remove(event.id)
                    snapshots[event.id] = snap.copy(state = DownloadState.Failed(event.failure), queuePosition = null)
                    completeWaiters(event.id, DownloadResult.Failure(event.failure))
                    markTerminal(event.id)
                }
            }
            is InternalEvent.Paused -> {
                active.remove(event.id)
                val snap = snapshots[event.id] ?: return
                // A job keeps its first cancellation cause, so a cancel issued after a pause still
                // reports here as paused. Cancelling is the later intent and takes priority.
                if (snap.state is DownloadState.Cancelling) {
                    resumeAfterPause.remove(event.id)
                    deletePartialAfterCancel(event.id, snap)
                    finishCancelled(event.id, snap)
                } else {
                    settlePaused(event.id, snap)
                }
            }
            is InternalEvent.Cancelled -> {
                active.remove(event.id)
                resumeAfterPause.remove(event.id)
                val snap = snapshots[event.id] ?: return
                finishCancelled(event.id, snap)
            }
        }
    }

    private fun settlePaused(id: DownloadId, snap: DownloadSnapshot) {
        // A pause interrupted this attempt, so it doesn't count against the retry budget.
        val attempts = (snap.attempts - 1).coerceAtLeast(0)
        if (resumeAfterPause.remove(id)) {
            snapshots[id] = snap.copy(state = DownloadState.Queued, attempts = attempts)
            enqueueIdByPriority(id, snap.request.priority)
        } else {
            snapshots[id] = snap.copy(state = DownloadState.Paused, attempts = attempts, queuePosition = null)
        }
    }

    private fun finishCancelled(id: DownloadId, snap: DownloadSnapshot) {
        serverMetadata.remove(id)
        snapshots[id] = snap.copy(state = DownloadState.Cancelled, queuePosition = null)
        completeWaiters(id, DownloadResult.Cancelled)
        markTerminal(id)
    }

    private fun deletePartialAfterCancel(id: DownloadId, snap: DownloadSnapshot) {
        val destination = snap.resolvedDestination ?: return
        if (config.deletePartialOnCancel) {
            runCatching { fileSystem.delete(partialPathFor(destination, id), mustExist = false) }
        }
    }

    private fun deletePartialAfterFailure(id: DownloadId, snap: DownloadSnapshot) {
        val destination = snap.resolvedDestination ?: return
        if (config.deletePartialOnFailure) {
            runCatching { fileSystem.delete(partialPathFor(destination, id), mustExist = false) }
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
            val knownMetadata = serverMetadata[next]
            // Starting is set here, not by the worker, so a pause or cancel issued before the
            // worker first runs already sees a cancellable job.
            snapshots[next] = snapshot.copy(state = DownloadState.Starting, attempts = snapshot.attempts + 1)
            // ATOMIC: the body always runs, even if cancelled first, so the worker always reports
            // a terminal event and `active` drains. Otherwise shutdown could wait forever.
            val workerJob = internalScope.launch(ioDispatcher, start = CoroutineStart.ATOMIC) {
                runWorker(
                    next,
                    snapshot.request,
                    snapshot.resolvedDestination
                        ?: error("Reserved destination missing for ${next.value}"),
                    knownMetadata,
                )
            }
            active[next] = workerJob
        }
    }

    private suspend fun runWorker(
        id: DownloadId,
        request: DownloadRequest,
        finalPath: Path,
        knownMetadata: ServerMetadata?,
    ) {
        val partialPath = partialPathFor(finalPath, id)

        try {
            currentCoroutineContext().ensureActive()
            localFile { ensureParentDirectory(finalPath) }
            val inlineValidated = writeDownloadToPath(
                id = id,
                request = request,
                partialPath = partialPath,
                knownMetadata = knownMetadata,
            )
            request.expectedChecksum?.let { checksum ->
                if (!inlineValidated) localFile { validateChecksum(partialPath, checksum) }
            }
            val size = localFile { fileSystem.metadata(partialPath).size ?: 0L }
            request.expectedBytes?.let {
                if (it != size) throw SizeMismatchException(it, size)
            }
            localFile { finalizePartialFile(partialPath, finalPath, request.effectiveOverwritePolicy(), id) }
            recordOriginSuccess(originKey(request.url))
            sendEvent(
                InternalEvent.Succeeded(id, DownloadedFile(finalPath, size))
            )
        } catch (t: Throwable) {
            when (t) {
                is PauseCancellationException -> {
                    sendEvent(InternalEvent.Paused(id))
                }
                is CancellationException -> {
                    cleanupPartial(listOf(partialPath), onCancel = true)
                    sendEvent(InternalEvent.Cancelled(id))
                }
                is Error -> {
                    // Fatal, but the coordinator must not wait forever for this worker.
                    sendEvent(InternalEvent.Failed(id, DownloadFailure.Unexpected(t.toString())))
                    throw t
                }
                else -> {
                    // The coordinator removes the partial once it knows no retry will use it.
                    sendEvent(InternalEvent.Failed(id, mapThrowable(t, finalPath), transient = t is TruncatedBodyException))
                }
            }
        }
    }

    /** Returns true when the checksum was already verified while streaming, so the caller can
     *  skip a second full-file pass. */
    private suspend fun writeDownloadToPath(
        id: DownloadId,
        request: DownloadRequest,
        partialPath: Path,
        knownMetadata: ServerMetadata?,
    ): Boolean {
        var downloadedBytes = localFile {
            if (fileSystem.exists(partialPath)) fileSystem.metadata(partialPath).size ?: 0L else 0L
        }
        // Without a validator nothing can prove the file is unchanged, so appending could splice
        // two versions together. A restart costs bytes; a corrupt file costs far more.
        if (downloadedBytes > 0L && knownMetadata?.eTag == null && knownMetadata?.lastModified == null) {
            localFile { fileSystem.delete(partialPath, mustExist = false) }
            downloadedBytes = 0L
        }
        if (request.segmentation !is DownloadSegmentation.Single &&
            downloadedBytes == 0L &&
            writeSegmentedDownloadToPath(id, request, partialPath)
        ) {
            return false
        }

        return try {
            streamToPartial(id, request, partialPath, knownMetadata, downloadedBytes)
        } catch (_: ResumeRefusedException) {
            localFile { fileSystem.delete(partialPath, mustExist = false) }
            streamToPartial(id, request, partialPath, knownMetadata, downloadedBytes = 0L)
        }
    }

    private suspend fun streamToPartial(
        id: DownloadId,
        request: DownloadRequest,
        partialPath: Path,
        knownMetadata: ServerMetadata?,
        downloadedBytes: Long,
    ): Boolean {
        awaitOriginPenalty(originKey(request.url))
        val statement = httpClient.prepareRequest(request.url) {
            this.method = HttpMethod.Get
            disableTotalRequestTimeout()
            headers {
                appendCallerHeaders(request)
                appendIdentityEncodingIfAbsent(request)
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
                    localFile { fileSystem.delete(partialPath, mustExist = false) }
                }
                if (responseETag != null || responseLastModified != null) {
                    sendEvent(InternalEvent.MetadataLearned(id, ServerMetadata(responseETag, responseLastModified)))
                }
            } else if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable && downloadedBytes > 0L) {
                throw ResumeRefusedException()
            } else {
                throw DownloadHttpException(
                    code = response.status.value,
                    message = response.status.description,
                    retryAfter = response.headers.retryAfter(),
                )
            }

            // 2. Pre-flight Disk Space Validation
            // An engine that decompressed the body leaves Content-Length at the compressed size.
            val contentEncoding = response.headers[HttpHeaders.ContentEncoding]?.trim()
            val decodedByEngine = contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)
            val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeUnless { decodedByEngine }
            if (totalBytes != null && totalBytes > 0L) requireDiskSpace(partialPath, totalBytes)

            // 3. Setup progressive hashing ONLY when downloaded from byte 0
            val isAppend = response.status == HttpStatusCode.PartialContent
            val actualStartOffset = if (isAppend) downloadedBytes else 0L

            // Bytes reach the file through positional writes, one per read: a BufferedSink would
            // issue one write per 8 KiB Okio segment. The hasher only ever sees a copy.
            val fileHandle = localFile { fileSystem.openReadWrite(partialPath) }
            val hashingSink = try {
                if (!isAppend) localFile { fileHandle.resize(0L) }
                if (request.expectedChecksum != null && actualStartOffset == 0L) {
                    when (request.expectedChecksum.algorithm) {
                        ChecksumAlgorithm.MD5 -> HashingSink.md5(blackholeSink())
                        ChecksumAlgorithm.SHA_256 -> HashingSink.sha256(blackholeSink())
                    }
                } else null
            } catch (t: Throwable) {
                fileHandle.close()
                throw t
            }
            val hashBuffer: BufferedSink? = hashingSink?.buffer()

            var inlineValidated = false
            try {
                var fileOffset = fileHandle.size()
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(config.bufferSizeBytes)
                var downloaded = actualStartOffset
                val totalLength = if (isAppend && totalBytes != null) (downloadedBytes + totalBytes) else totalBytes

                val reporter = ProgressReporter(request.progressGranularity, totalLength)

                while (!channel.isClosedForRead) {
                    val read = readWithIdleTimeout(channel, buffer, config.idleTimeout)
                    if (read < 0) break
                    if (read == 0) continue
                    localFile { fileHandle.write(fileOffset, buffer, 0, read) }
                    fileOffset += read
                    hashBuffer?.write(buffer, 0, read)
                    downloaded += read
                    reporter.sample(downloaded)?.let { sendEvent(InternalEvent.Progress(id, it)) }
                }

                if (totalLength != null && downloaded < totalLength) {
                    throw TruncatedBodyException(totalLength, downloaded)
                }
                if (totalLength != null && downloaded != totalLength) {
                    throw SizeMismatchException(totalLength, downloaded)
                }

                hashBuffer?.flush()

                // 4. Validate progressive checksum on-the-fly (if downloaded from scratch)
                if (hashingSink != null && request.expectedChecksum != null) {
                    val actualHex = hashingSink.hash.hex()
                    if (!actualHex.equals(request.expectedChecksum.expectedHex, ignoreCase = true)) {
                        throw ChecksumMismatchException(request.expectedChecksum.expectedHex, actualHex)
                    }
                    inlineValidated = true
                }
            } finally {
                hashBuffer?.close()
                fileHandle.close()
            }
            inlineValidated
        }
    }

    private suspend fun writeSegmentedDownloadToPath(
        id: DownloadId,
        request: DownloadRequest,
        partialPath: Path,
    ): Boolean {
        val knownSmall = request.segmentation is DownloadSegmentation.Auto &&
            request.expectedBytes?.let { autoSegmentCount(it) <= 1 } == true
        if (knownSmall) return false
        val metadata = fetchSegmentedMetadata(id, request, config.idleTimeout) ?: return false
        val origin = originKey(request.url)
        var desiredSegments = min(resolveSegmentCount(request.segmentation, metadata.totalBytes).toLong(), metadata.totalBytes)
            .toInt()
            .coerceAtLeast(1)

        while (desiredSegments > 1) {
            val segmentCount = recommendedSegmentCount(origin, desiredSegments)
            if (segmentCount <= 1) return false

            requireDiskSpace(partialPath, metadata.totalBytes)

            val ranges = buildSegmentRanges(metadata.totalBytes, segmentCount)
            // Segments land in their own file, not partialPath: an unfinished one has holes, and a
            // partial file that survives (crash, retained on failure) is resumed from its size.
            val segmentedPath = "$partialPath.seg".toPath()
            localFile { fileSystem.delete(segmentedPath, mustExist = false) }

            try {
                val progressMutex = Mutex()
                val segmentProgress = LongArray(ranges.size)
                val reporter = ProgressReporter(request.progressGranularity, metadata.totalBytes)
                // Never resize() ahead: on the JVM okio allocates the whole delta as one ByteArray.
                localFile { fileSystem.openReadWrite(segmentedPath) }.use { fileHandle ->
                    coroutineScope {
                        ranges.mapIndexed { index, range ->
                            async {
                                downloadSegment(
                                    request = request,
                                    metadata = metadata,
                                    range = range,
                                    fileHandle = fileHandle,
                                    onSegmentProgress = { bytesInSegment ->
                                        val progress = progressMutex.withLock {
                                            segmentProgress[index] = bytesInSegment
                                            reporter.sample(segmentProgress.sum())
                                        }
                                        progress?.let { sendEvent(InternalEvent.Progress(id, it)) }
                                    }
                                )
                            }
                        }.awaitAll()
                    }
                }

                val actualSize = localFile {
                    fileSystem.delete(partialPath, mustExist = false)
                    fileSystem.atomicMove(segmentedPath, partialPath)
                    fileSystem.metadata(partialPath).size ?: 0L
                }
                if (actualSize != metadata.totalBytes) {
                    throw SizeMismatchException(metadata.totalBytes, actualSize)
                }
                return true
            } catch (_: RangesRefusedException) {
                // The range support the probe saw doesn't hold, so use the single stream.
                listOf(segmentedPath, partialPath).forEach { path ->
                    runCatching { fileSystem.delete(path, mustExist = false) }
                }
                return false
            } catch (t: Throwable) {
                // Segments are not resumable (the next attempt starts over), so a pause cleans
                // up like a cancel instead of leaving unusable slices on disk.
                cleanupPartial(listOf(segmentedPath, partialPath), onCancel = t is CancellationException)
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
            val underPenalty = profile.penaltyUntil?.let { it > now } ?: false
            val pressureCap = if (underPenalty) 1 else profile.maxParallelRequests
            min(desiredSegments, pressureCap).coerceAtLeast(1)
        }

    private suspend fun recordOriginRateLimit(origin: String, segmentCount: Int, retryDelay: Duration) {
        originMutex.withLock {
            val profile = originProfiles.getOrPut(origin) { OriginProfile() }
            profile.maxParallelRequests = (min(profile.maxParallelRequests, segmentCount) / 2).coerceAtLeast(1)
            val candidate = timeSource.markNow() + retryDelay
            val current = profile.penaltyUntil
            profile.penaltyUntil = if (current == null || candidate > current) candidate else current
        }
    }

    /** Each finished download earns back one parallel request lost to rate limiting. */
    private suspend fun recordOriginSuccess(origin: String) {
        originMutex.withLock {
            val profile = originProfiles[origin] ?: return@withLock
            profile.maxParallelRequests = min(profile.maxParallelRequests + 1, MAX_PARALLEL_REQUESTS_PER_ORIGIN)
        }
    }

    /** Waits out a rate-limit penalty. Whole-file requests don't take a parallelism permit. */
    private suspend fun awaitOriginPenalty(origin: String) {
        while (true) {
            val remaining = originMutex.withLock {
                val now = timeSource.markNow()
                originProfiles[origin]?.penaltyUntil?.takeIf { it > now }?.let { it - now }
            } ?: return
            delay(remaining.coerceAtLeast(1.milliseconds))
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
                    response.prefersSingleStream(request) -> null
                    response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone -> {
                        if (request.segmentation is DownloadSegmentation.MaxSegments) {
                            throw DownloadHttpException(
                                code = response.status.value,
                                message = response.status.description,
                                retryAfter = response.headers.retryAfter(),
                            )
                        }
                        null
                    }
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
                if (response.status != HttpStatusCode.PartialContent || response.prefersSingleStream(request)) {
                    return@executeWithResponseTimeout null
                }
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

    /**
     * HTTP/2 and later carry every range over one connection, under one flow-control window, so
     * segments add temp files and a merge without adding bandwidth. An explicit
     * [DownloadSegmentation.MaxSegments] is still honored.
     */
    private fun HttpResponse.prefersSingleStream(request: DownloadRequest): Boolean =
        request.segmentation is DownloadSegmentation.Auto && version != HttpProtocolVersion.HTTP_1_1 &&
            version != HttpProtocolVersion.HTTP_1_0

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
        metadata: SegmentedMetadata,
        range: SegmentRange,
        fileHandle: FileHandle,
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
                if (response.status == HttpStatusCode.OK) throw RangesRefusedException()
                if (response.status != HttpStatusCode.PartialContent) {
                    throw DownloadHttpException(
                        code = response.status.value,
                        message = response.status.description,
                        retryAfter = response.headers.retryAfter(),
                    )
                }
                val responseETag = response.headers[HttpHeaders.ETag]?.trim()
                if (metadata.eTag != null && responseETag != null && metadata.eTag != responseETag) {
                    throw ServerFileChangedException("Remote file ETag changed mid-download")
                }
                val responseLastModified = response.headers[HttpHeaders.LastModified]?.trim()
                if (metadata.lastModified != null && responseLastModified != null &&
                    metadata.lastModified != responseLastModified
                ) {
                    throw ServerFileChangedException("Remote file Last-Modified changed mid-download")
                }
                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength != null && contentLength != range.length) {
                    throw SizeMismatchException(range.length, contentLength)
                }
                response.headers[HttpHeaders.ContentRange]?.let { contentRange ->
                    validateContentRange(contentRange, range, metadata.totalBytes)
                }

                val channel = response.bodyAsChannel()
                val buffer = ByteArray(config.bufferSizeBytes)
                var downloaded = 0L
                while (!channel.isClosedForRead) {
                    val read = readWithIdleTimeout(channel, buffer, config.idleTimeout)
                    if (read < 0) break
                    if (read == 0) continue
                    if (downloaded + read > range.length) {
                        throw SizeMismatchException(range.length, downloaded + read)
                    }
                    localFile { fileHandle.write(range.start + downloaded, buffer, 0, read) }
                    downloaded += read
                    onSegmentProgress(downloaded)
                }
                if (downloaded != range.length) {
                    throw TruncatedBodyException(range.length, downloaded)
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
        val first = if (idleTimeout > Duration.ZERO) {
            withTimeoutOrNull(idleTimeout) { channel.readAvailable(buffer, 0, buffer.size) }
                ?: throw DownloadStallException()
        } else {
            channel.readAvailable(buffer, 0, buffer.size)
        }
        if (first <= 0) return first
        // readAvailable returns at most one 8 KiB segment whatever the array size. Take what the
        // channel already holds so bufferSizeBytes decides the size of a write. This cannot stall.
        var filled = first
        while (filled < buffer.size && channel.availableForRead > 0) {
            val read = channel.readAvailable(buffer, filled, buffer.size - filled)
            if (read <= 0) break
            filled += read
        }
        return filled
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
            throw SizeMismatchException(range.length, 0L)
        }
        val rangeAndTotal = normalized.substringAfter(' ').split('/')
        if (rangeAndTotal.size != 2) throw SizeMismatchException(range.length, 0L)
        val startAndEnd = rangeAndTotal[0].split('-')
        if (startAndEnd.size != 2) throw SizeMismatchException(range.length, 0L)
        val actualStart = startAndEnd[0].toLongOrNull()
        val actualEnd = startAndEnd[1].toLongOrNull()
        val actualTotal = rangeAndTotal[1].toLongOrNull()
        if (actualStart != range.start || actualEnd != range.endInclusive || actualTotal != totalBytes) {
            throw SizeMismatchException(range.length, (actualEnd ?: -1L) - (actualStart ?: 0L) + 1L)
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

    private fun cleanupPartial(paths: List<Path>, onCancel: Boolean) {
        val shouldDelete = if (onCancel) config.deletePartialOnCancel else config.deletePartialOnFailure
        // Best effort: a failed delete must not stop the worker from reporting how it ended.
        if (shouldDelete) paths.forEach { path -> runCatching { fileSystem.delete(path, mustExist = false) } }
    }

    private fun requireDiskSpace(path: Path, requiredBytes: Long) {
        val usable = PlatformSpaceChecker.getUsableSpace(path)
        if (usable != -1L && usable < requiredBytes + DISK_SPACE_SAFETY_MARGIN_BYTES) {
            throw DiskNoSpaceException(requiredBytes, usable)
        }
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
        is ConnectTimeoutException -> DownloadFailure.Timeout(throwable.message ?: "Connection timed out")
        is SocketTimeoutException -> DownloadFailure.Timeout(throwable.message ?: "Socket timed out")
        is SizeMismatchException -> DownloadFailure.SizeMismatch(throwable.expected, throwable.actual)
        is DownloadFileExistsException -> DownloadFailure.AlreadyExists(throwable.path)
        is DiskNoSpaceException -> DownloadFailure.NoSpace(path, throwable.message ?: "Not enough disk space")
        is ChecksumMismatchException -> DownloadFailure.ChecksumMismatch(throwable.expected, throwable.actual)
        is ServerFileChangedException -> DownloadFailure.ServerChanged(throwable.message ?: "Server file changed mid-download")
        is LocalFileException -> {
            val msg = throwable.message.orEmpty()
            when {
                msg.contains("permission", ignoreCase = true) -> DownloadFailure.PermissionDenied(path, msg)
                msg.contains("space", ignoreCase = true) || msg.contains("disk full", ignoreCase = true) -> DownloadFailure.NoSpace(path, msg)
                else -> DownloadFailure.FileSystem(msg.ifBlank { "File system IO error at $path" })
            }
        }
        // Everything that touches the disk goes through localFile, so these came from the network.
        // Ktor's engines throw kotlinx.io's IOException, a different class from Okio's on native.
        is IOException, is kotlinx.io.IOException -> DownloadFailure.Network(
            throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.simpleName ?: "Network IO error"
        )
        else -> DownloadFailure.Unexpected(throwable.message ?: throwable::class.simpleName ?: "Unexpected error")
    }

    private fun destinationPathFor(request: DownloadRequest, id: DownloadId): Path =
        when (val destination = request.destination) {
            DownloadDestination.Default -> joinPath(
                DownloadLocations.downloads,
                fileNameFromUrl(request.url) ?: "download-${id.value}.bin",
            )
            is DownloadDestination.Directory -> joinPath(
                destination.path,
                destination.fileName ?: fileNameFromUrl(request.url) ?: "download-${id.value}.bin",
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

    private fun enqueueIdByPriority(id: DownloadId, priority: DownloadPriority) {
        pendingByPriority.getValue(priority).addLast(id)
    }

    private fun dequeueNext(): DownloadId? =
        pendingByPriority.values.firstOrNull { it.isNotEmpty() }?.removeFirst()

    private fun removeFromPending(id: DownloadId): Boolean =
        pendingByPriority.values.any { it.remove(id) }

    private fun currentPendingList(): List<DownloadId> = pendingByPriority.values.flatten()

    private fun terminalResultFromSnapshot(snapshot: DownloadSnapshot?): DownloadResult? {
        return when (val state = snapshot?.state) {
            is DownloadState.Succeeded -> DownloadResult.Success(state.file)
            is DownloadState.Failed -> DownloadResult.Failure(state.failure)
            is DownloadState.Cancelled -> DownloadResult.Cancelled
            else -> null
        }
    }

    private fun markTerminal(id: DownloadId) {
        finishedSincePublish++
        releaseDestination(id)
        terminalOrder.remove(id)
        terminalOrder.addLast(id)
        while (terminalOrder.size > config.maxRetainedFinished) {
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
        terminalWaiters.remove(id)?.forEach { waiter -> replyAfterPublish(waiter, null) }
    }

    private fun DownloadState.isTerminal(): Boolean =
        this is DownloadState.Succeeded || this is DownloadState.Failed || this is DownloadState.Cancelled

    private fun sendEvent(event: InternalEvent) {
        // The inbox is UNLIMITED, so trySend never blocks and never fails for a live
        // channel — this stays safe to call from a cancelled worker (no suspension).
        inbox.trySend(InternalMessage.WorkerEvent(event))
    }

    private fun completeWaiters(id: DownloadId, result: DownloadResult) {
        terminalWaiters.remove(id)?.forEach { waiter -> replyAfterPublish(waiter, result) }
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

    private fun resolveSegmentCount(segmentation: DownloadSegmentation, totalBytes: Long): Int =
        when (segmentation) {
            DownloadSegmentation.Auto -> autoSegmentCount(totalBytes)
            DownloadSegmentation.Single -> 1
            is DownloadSegmentation.MaxSegments -> segmentation.count
        }

    private fun autoSegmentCount(totalBytes: Long): Int {
        val mib = 1024L * 1024L
        return when {
            totalBytes < 16L * mib -> 1
            totalBytes < 128L * mib -> 2
            else -> MAX_PARALLEL_REQUESTS_PER_ORIGIN
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

/**
 * Applies [granularity] to a growing byte count, always reporting the first and final byte, and
 * measures speed over the most recent window of at least [SPEED_WINDOW_MILLIS].
 */
internal class ProgressReporter(
    private val granularity: ProgressGranularity,
    private val totalBytes: Long?,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var lastEmitTime = 0L
    private var lastEmitBytes = 0L
    private var windowStartTime = 0L
    private var windowStartBytes = 0L

    fun sample(downloaded: Long): DownloadProgress? {
        val now = nowMillis()
        val shouldEmit = lastEmitTime == 0L ||
            downloaded == totalBytes ||
            (downloaded - lastEmitBytes >= granularity.minBytesDelta &&
                now - lastEmitTime >= granularity.minInterval.inWholeMilliseconds)
        if (!shouldEmit) return null

        val speed = if (windowStartTime == 0L) {
            null
        } else {
            ((downloaded - windowStartBytes) * 1000L / max(1L, now - windowStartTime)).coerceAtLeast(0L)
        }
        val eta = if (speed != null && speed > 0L && totalBytes != null) {
            ((totalBytes - downloaded).coerceAtLeast(0L) * 1000L / speed).milliseconds
        } else {
            null
        }
        lastEmitTime = now
        lastEmitBytes = downloaded
        if (windowStartTime == 0L || now - windowStartTime >= SPEED_WINDOW_MILLIS) {
            windowStartTime = now
            windowStartBytes = downloaded
        }
        return DownloadProgress(downloaded, totalBytes, speed, eta)
    }
}

private const val SPEED_WINDOW_MILLIS: Long = 500

private const val MAX_PARALLEL_REQUESTS_PER_ORIGIN: Int = 4

private const val DISK_SPACE_SAFETY_MARGIN_BYTES: Long = 10L * 1024L * 1024L

private const val MAX_MESSAGES_PER_PUBLISH: Int = 256

private data object PauseCancellationException : CancellationException("Paused by user")

private class DownloadHttpException(
    val code: Int,
    val retryAfter: Duration? = null,
    message: String?,
) : RuntimeException(message)

private open class SizeMismatchException(
    val expected: Long,
    val actual: Long,
) : RuntimeException("Size mismatch: expected=$expected actual=$actual")

/** The connection ended before the promised bytes arrived. Another attempt can succeed. */
private class TruncatedBodyException(expected: Long, actual: Long) : SizeMismatchException(expected, actual)

/** The server has no bytes at the offset a resume asked for, so the partial is no use. */
private class ResumeRefusedException : RuntimeException("Server refused the resume range")

/** A slice got the whole file: the range support the probe saw doesn't hold. */
private class RangesRefusedException : RuntimeException("Server ignored a range request")

/** An I/O error from the disk, as opposed to one from the network. */
private class LocalFileException(cause: IOException) : IOException(cause.message, cause)

private inline fun <T> localFile(block: () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        throw LocalFileException(e)
    }

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
