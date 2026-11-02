package io.github.rudolfhk.audiosaver.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.history.HistoryEntry
import io.github.rudolfhk.audiosaver.core.history.HistoryLog
import io.github.rudolfhk.audiosaver.core.history.HistoryRecorder
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.AudioDownloadPipeline
import io.github.rudolfhk.audiosaver.core.pipeline.BatchDownloader
import io.github.rudolfhk.audiosaver.core.pipeline.BatchItemState
import io.github.rudolfhk.audiosaver.core.pipeline.BatchRequest
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadOptions
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.ItemStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of "the" download: either one song or one batch of songs from a mix/album,
 * never both and never more than one batch (there is deliberately no general queue).
 * The UI observes [state] / [batch]; [DownloadService] does the work and reports back here.
 */
class DownloadController(
    val engine: ExtractionEngine,
    val pipeline: AudioDownloadPipeline,
    val batchDownloader: BatchDownloader,
    val history: HistoryLog,
    /** Outlives screens and the service: history writes must finish even if both go away. */
    private val appScope: CoroutineScope,
) {
    private val recorder = HistoryRecorder()
    private var batchRun = 0L

    /** What the last single download asked for, so "Retry" repeats exactly that. */
    @Volatile var lastOptions: DownloadOptions = DownloadOptions.AUDIO_ONLY
        private set

    @Volatile private var runningOptions: DownloadOptions = DownloadOptions.AUDIO_ONLY

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var pendingBatch: BatchRequest? = null

    val isBusy: Boolean get() = _state.value.isActive || _batch.value?.isActive == true

    /** Returns false if something is already downloading. */
    fun start(context: Context, videoId: VideoId, options: DownloadOptions = DownloadOptions.AUDIO_ONLY): Boolean {
        synchronized(this) {
            if (isBusy) return false
            lastOptions = options
            runningOptions = options
            _batch.value = null
            // Flip state before the service spins up so the UI reacts on the same frame.
            _state.value = DownloadState.Resolving(videoId)
        }
        return startService(context, DownloadService.startIntent(context, videoId, options)) { message ->
            publish(DownloadState.Failed(videoId, FailureReason.UNKNOWN, message))
        }
    }

    fun startBatch(context: Context, request: BatchRequest): Boolean {
        if (request.entries.isEmpty()) return false
        synchronized(this) {
            if (isBusy) return false
            _state.value = DownloadState.Idle
            batchRun++
            pendingBatch = request
            _batch.value = BatchState(
                request.title,
                request.kind,
                request.entries.map { BatchItemState(it, ItemStatus.Queued) },
                finished = false,
            )
        }
        return startService(context, DownloadService.startBatchIntent(context)) { message ->
            pendingBatch = null
            _batch.value = _batch.value?.let { batch ->
                batch.copy(
                    items = batch.items.map { it.copy(status = ItemStatus.Failed(FailureReason.UNKNOWN, message)) },
                    finished = true,
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished single-song result, e.g. when the user starts editing a new link. */
    fun dismissResult() {
        synchronized(this) {
            if (!_state.value.isActive) _state.value = DownloadState.Idle
        }
    }

    /** Clears a finished batch summary ("back to the list"). */
    fun dismissBatch() {
        synchronized(this) {
            if (_batch.value?.isActive != true) _batch.value = null
        }
    }

    private inline fun startService(context: Context, intent: Intent, onFailure: (String) -> Unit): Boolean =
        try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: IllegalStateException) {
            // e.g. ForegroundServiceStartNotAllowedException if somehow started from the background
            onFailure("Couldn't start the download service: ${e.message}")
            false
        }

    internal fun takePendingBatch(): BatchRequest? = synchronized(this) { pendingBatch.also { pendingBatch = null } }

    internal fun attach(job: Job) {
        this.job = job
    }

    internal fun publish(state: DownloadState) {
        _state.value = state
        if (!state.isActive) job = null
        videoIdOf(state)?.let { id -> recorder.onSingleState(id, runningOptions, state) }?.let(::remember)
    }

    internal fun publishBatch(state: BatchState) {
        _batch.value = state
        if (state.finished) job = null
        recorder.onBatchState(batchRun, state).forEach(::remember)
    }

    private fun remember(entry: HistoryEntry) {
        appScope.launch { history.add(entry) }
    }

    private fun videoIdOf(state: DownloadState): VideoId? = when (state) {
        DownloadState.Idle -> null
        is DownloadState.Resolving -> state.videoId
        is DownloadState.Downloading -> state.videoId
        is DownloadState.Saving -> state.videoId
        is DownloadState.Merging -> state.videoId
        is DownloadState.Done -> state.videoId
        is DownloadState.Failed -> state.videoId
        is DownloadState.Cancelled -> state.videoId
    }
}
