package io.github.rudolfhk.audiosaver.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rudolfhk.audiosaver.core.collection.CollectionListing
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.LinkParseResult
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.link.YouTubeLinkParser
import io.github.rudolfhk.audiosaver.core.pipeline.BatchRequest
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadOptions
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import io.github.rudolfhk.audiosaver.download.DownloadController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the current link will download. */
sealed interface Target {
    data object None : Target
    data class Single(val videoId: VideoId) : Target
    data class Collection(val ref: CollectionRef) : Target
}

/** The pick-your-songs list for a mix/album/playlist link. */
sealed interface CollectionUi {
    data object Hidden : CollectionUi
    data class Loading(val ref: CollectionRef) : CollectionUi
    data class Loaded(val listing: CollectionListing, val loadingMore: Boolean = false, val loadMoreError: String? = null) : CollectionUi
    data class Failed(val ref: CollectionRef, val reason: FailureReason, val detail: String) : CollectionUi
}

class MainViewModel(
    private val controller: DownloadController,
    private val videoPrefs: VideoPreferences,
) : ViewModel() {

    /** Optional MP4 next to the audio; single videos only (never offered for mixes/albums). */
    private val _saveVideo = MutableStateFlow(videoPrefs.enabled)
    val saveVideo: StateFlow<Boolean> = _saveVideo.asStateFlow()

    private val _videoQuality = MutableStateFlow(videoPrefs.quality)
    val videoQuality: StateFlow<VideoQuality> = _videoQuality.asStateFlow()

    fun setSaveVideo(value: Boolean) {
        _saveVideo.value = value
        videoPrefs.enabled = value
    }

    fun setVideoQuality(value: VideoQuality) {
        _videoQuality.value = value
        videoPrefs.quality = value
    }

    private fun singleOptions() =
        if (_saveVideo.value) DownloadOptions(video = _videoQuality.value) else DownloadOptions.AUDIO_ONLY

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /** Validated locally on every change - instant feedback, no network. */
    val parsed: StateFlow<LinkParseResult> = _input
        .map(YouTubeLinkParser::parse)
        .stateIn(viewModelScope, SharingStarted.Eagerly, YouTubeLinkParser.parse(""))

    /** For a video link inside a mix/album: the user chose "just this song" instead of the list. */
    private val _justThisSong = MutableStateFlow(false)
    val justThisSong: StateFlow<Boolean> = _justThisSong.asStateFlow()

    val target: StateFlow<Target> = combine(parsed, _justThisSong) { result, single ->
        when (result) {
            is LinkParseResult.Valid -> Target.Single(result.videoId)
            is LinkParseResult.Collection ->
                result.ref.seedVideoId?.takeIf { single }?.let { Target.Single(it) } ?: Target.Collection(result.ref)
            is LinkParseResult.Invalid -> Target.None
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Target.None)

    private val _collection = MutableStateFlow<CollectionUi>(CollectionUi.Hidden)
    val collection: StateFlow<CollectionUi> = _collection.asStateFlow()

    val download: StateFlow<DownloadState> = controller.state
    val batch: StateFlow<BatchState?> = controller.batch

    private var loadJob: Job? = null

    init {
        // Fetch the song list as soon as a mix/album link is pasted - after a short pause, so typing
        // or editing a link by hand doesn't fire a request per keystroke.
        viewModelScope.launch {
            target.collect { target ->
                val ref = (target as? Target.Collection)?.ref
                if (ref == null) {
                    // "Just this song" keeps the list around for switching back; any other link drops it.
                    if (parsed.value !is LinkParseResult.Collection) cancelLoading(CollectionUi.Hidden)
                    return@collect
                }
                if (currentRef() == ref) return@collect
                loadFirstPage(ref, debounceMs = 400)
            }
        }
    }

    fun onInputChanged(text: String) {
        if (text == _input.value) return
        _input.value = text
        _justThisSong.value = false
        controller.dismissResult()
        if (controller.batch.value?.isActive != true) controller.dismissBatch()
    }

    /** Text shared from another app (ACTION_SEND). Shared texts often wrap the link in a title. */
    fun onSharedText(text: String) = onInputChanged(text)

    fun setJustThisSong(value: Boolean) {
        _justThisSong.value = value
    }

    // ---------------------------------------------------------------- list

    fun reloadCollection() {
        (target.value as? Target.Collection)?.let { loadFirstPage(it.ref, debounceMs = 0) }
    }

    fun loadMore() {
        val loaded = _collection.value as? CollectionUi.Loaded ?: return
        val token = loaded.listing.next ?: return
        if (loaded.loadingMore) return
        _collection.value = loaded.copy(loadingMore = true, loadMoreError = null)
        loadJob = viewModelScope.launch {
            try {
                val page = controller.engine.listCollection(loaded.listing.ref, token)
                updateLoaded { it.copy(listing = it.listing.appending(page), loadingMore = false) }
            } catch (e: AudioSaveException) {
                updateLoaded { it.copy(loadingMore = false, loadMoreError = "${e.reason.userTitle}: ${e.detail}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateLoaded { it.copy(loadingMore = false, loadMoreError = "${e::class.java.simpleName}: ${e.message}") }
            }
        }
    }

    fun toggle(id: VideoId) = updateLoaded { it.copy(listing = it.listing.toggle(id)) }

    fun selectAll(all: Boolean) = updateLoaded {
        it.copy(listing = if (all) it.listing.selectAll() else it.listing.selectNone())
    }

    private fun loadFirstPage(ref: CollectionRef, debounceMs: Long) {
        loadJob?.cancel()
        _collection.value = CollectionUi.Loading(ref)
        loadJob = viewModelScope.launch {
            delay(debounceMs)
            _collection.value = try {
                CollectionUi.Loaded(CollectionListing.of(controller.engine.listCollection(ref)))
            } catch (e: AudioSaveException) {
                CollectionUi.Failed(ref, e.reason, e.detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CollectionUi.Failed(ref, FailureReason.UNKNOWN, "${e::class.java.simpleName}: ${e.message}")
            }
        }
    }

    private fun cancelLoading(newState: CollectionUi) {
        loadJob?.cancel()
        loadJob = null
        _collection.value = newState
    }

    private fun currentRef(): CollectionRef? = when (val c = _collection.value) {
        is CollectionUi.Loading -> c.ref
        is CollectionUi.Loaded -> c.listing.ref
        is CollectionUi.Failed -> c.ref
        CollectionUi.Hidden -> null
    }

    private inline fun updateLoaded(crossinline change: (CollectionUi.Loaded) -> CollectionUi.Loaded) {
        _collection.update { if (it is CollectionUi.Loaded) change(it) else it }
    }

    // ---------------------------------------------------------------- downloads

    fun startDownload(context: Context) {
        when (val t = target.value) {
            is Target.Single -> controller.start(context, t.videoId, singleOptions())
            is Target.Collection -> {
                val listing = (_collection.value as? CollectionUi.Loaded)?.listing ?: return
                controller.startBatch(context, listing.toBatchRequest())
            }
            Target.None -> Unit
        }
    }

    fun retry(context: Context, videoId: VideoId) {
        controller.dismissResult()
        controller.start(context, videoId, singleOptions())
    }

    /** The audio was saved but the video wasn't: try only the video again, without a second audio copy. */
    fun retryVideo(context: Context, videoId: VideoId) {
        controller.dismissResult()
        controller.start(context, videoId, DownloadOptions(saveAudio = false, video = _videoQuality.value))
    }

    fun retryFailed(context: Context) {
        val finished = controller.batch.value?.takeIf { it.finished } ?: return
        val failed = finished.failedEntries.ifEmpty { return }
        controller.startBatch(context, BatchRequest(finished.title, finished.kind, failed))
    }

    /** Back from the batch summary to the song list (e.g. to pick more songs). */
    fun dismissBatch() = controller.dismissBatch()

    fun cancel() = controller.cancel()
}
