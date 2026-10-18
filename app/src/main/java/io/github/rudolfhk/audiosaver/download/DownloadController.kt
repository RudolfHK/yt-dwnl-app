package io.github.rudolfhk.audiosaver.download

import android.content.Context
import androidx.core.content.ContextCompat
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.AudioDownloadPipeline
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of "the" download (one at a time - there is deliberately no queue).
 * The UI observes [state]; [DownloadService] does the work and reports back here.
 */
class DownloadController(val pipeline: AudioDownloadPipeline) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    @Volatile private var job: Job? = null

    /** Returns false if a download is already running. */
    fun start(context: Context, videoId: VideoId): Boolean {
        synchronized(this) {
            if (_state.value.isActive) return false
            // Flip state before the service spins up so the UI reacts on the same frame.
            _state.value = DownloadState.Resolving(videoId)
        }
        try {
            ContextCompat.startForegroundService(context, DownloadService.startIntent(context, videoId))
        } catch (e: IllegalStateException) {
            // e.g. ForegroundServiceStartNotAllowedException if somehow started from the background
            publish(DownloadState.Failed(videoId, FailureReason.UNKNOWN, "Couldn't start the download service: ${e.message}"))
            return false
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished/failed result, e.g. when the user starts editing a new link. */
    fun dismissResult() {
        synchronized(this) {
            if (!_state.value.isActive) _state.value = DownloadState.Idle
        }
    }

    internal fun attach(job: Job) {
        this.job = job
    }

    internal fun publish(state: DownloadState) {
        _state.value = state
        if (!state.isActive) job = null
    }
}
