package io.github.rudolfhk.audiosaver.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.AudioDownloadPipeline
import io.github.rudolfhk.audiosaver.core.pipeline.BatchDownloader
import io.github.rudolfhk.audiosaver.core.pipeline.BatchItemState
import io.github.rudolfhk.audiosaver.core.pipeline.BatchRequest
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.ItemStatus
import kotlinx.coroutines.Job
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
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var pendingBatch: BatchRequest? = null

    val isBusy: Boolean get() = _state.value.isActive || _batch.value?.isActive == true

    /** Returns false if something is already downloading. */
    fun start(context: Context, videoId: VideoId): Boolean {
        synchronized(this) {
            if (isBusy) return false
            _batch.value = null
            // Flip state before the service spins up so the UI reacts on the same frame.
            _state.value = DownloadState.Resolving(videoId)
        }
        return startService(context, DownloadService.startIntent(context, videoId)) { message ->
            publish(DownloadState.Failed(videoId, FailureReason.UNKNOWN, message))
        }
    }

    fun startBatch(context: Context, request: BatchRequest): Boolean {
        if (request.entries.isEmpty()) return false
        synchronized(this) {
            if (isBusy) return false
            _state.value = DownloadState.Idle
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
    }

    internal fun publishBatch(state: BatchState) {
        _batch.value = state
        if (state.finished) job = null
    }
}
