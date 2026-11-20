package io.github.rudolfhk.audiosaver.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rudolfhk.audiosaver.core.collection.CollectionListing
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.history.HistoryEntry
import io.github.rudolfhk.audiosaver.core.history.retryOptions
import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.LinkParseResult
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.link.YouTubeLinkParser
import io.github.rudolfhk.audiosaver.core.pipeline.BatchRequest
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadOptions
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import io.github.rudolfhk.audiosaver.core.audio.TimeCodes
import io.github.rudolfhk.audiosaver.core.audio.TimeRange
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import io.github.rudolfhk.audiosaver.core.update.UpdateStatus
import io.github.rudolfhk.audiosaver.download.DownloadController
import io.github.rudolfhk.audiosaver.download.PendingReview
import io.github.rudolfhk.audiosaver.settings.AdvancedSettings
import io.github.rudolfhk.audiosaver.settings.Settings
import io.github.rudolfhk.audiosaver.update.UpdateManager
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
    private val formats: FormatPreferences,
    private val advanced: AdvancedSettings,
    private val updates: UpdateManager,
) : ViewModel() {

    val settings: StateFlow<Settings> = advanced.settings

    fun changeSettings(change: (Settings) -> Settings) {
        val before = advanced.current
        advanced.update(change)
        val after = advanced.current
        if (before.updateCheck && !after.updateCheck) updates.clear()
        if (!before.updateCheck && after.updateCheck) updates.checkIfDue(true)
    }

    fun resetSettings() = changeSettings { Settings() }

    // ---------------------------------------------------------------- trim fields (single links)

    private val _trimStart = MutableStateFlow("")
    val trimStart: StateFlow<String> = _trimStart.asStateFlow()
    private val _trimEnd = MutableStateFlow("")
    val trimEnd: StateFlow<String> = _trimEnd.asStateFlow()

    fun setTrimStart(text: String) { _trimStart.value = text }
    fun setTrimEnd(text: String) { _trimEnd.value = text }

    // ---------------------------------------------------------------- update check

    /** Shown as a card unless dismissed; null when there's nothing new. */
    val update: StateFlow<UpdateStatus?> = combine(updates.status, updates.dismissedKey, advanced.settings) { status, dismissed, s ->
        status?.takeIf { s.updateCheck && it.anything && UpdateManager.keyOf(it) != dismissed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The hint for EXTRACTOR_BROKEN errors - shown even if the card was dismissed. */
    val updateHint: StateFlow<String?> = combine(updates.status, advanced.settings) { status, s ->
        status?.takeIf { s.updateCheck }?.brokenExtractorHint()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun updateSummary(status: UpdateStatus): String? = status.summary(updates.build)

    fun dismissUpdate(status: UpdateStatus) = updates.dismiss(status)

    /** Called when the screen is shown; does nothing unless a day has passed. */
    fun checkForUpdates() = updates.checkIfDue(advanced.current.updateCheck)

    val appVersion: String get() = updates.build.appVersion

    val buildDescription: String get() = "${updates.build.appVersion} · NewPipeExtractor ${updates.build.extractorVersion}"

    // ---------------------------------------------------------------- tag review

    val pendingReview: StateFlow<PendingReview?> = controller.reviews.pending

    fun answerReview(review: PendingReview, metadata: TrackMetadata?) {
        controller.reviews.answer(review, metadata)
    }

    // What a single link produces: audio, video or both. Mixes/albums are always saved as audio.
    private val _saveAudio = MutableStateFlow(formats.audio)
    val saveAudio: StateFlow<Boolean> = _saveAudio.asStateFlow()

    private val _saveVideo = MutableStateFlow(formats.video)
    val saveVideo: StateFlow<Boolean> = _saveVideo.asStateFlow()

    private val _videoQuality = MutableStateFlow(formats.quality)
    val videoQuality: StateFlow<VideoQuality> = _videoQuality.asStateFlow()

    fun setSaveAudio(value: Boolean) {
        _saveAudio.value = value
        formats.audio = value
    }

    fun setSaveVideo(value: Boolean) {
        _saveVideo.value = value
        formats.video = value
    }

    fun setVideoQuality(value: VideoQuality) {
        _videoQuality.value = value
        formats.quality = value
    }

    /** Null when neither format is selected or the cut is invalid - the download button is disabled then. */
    private fun singleOptions(): DownloadOptions? {
        val video = _videoQuality.value.takeIf { _saveVideo.value }
        if (!_saveAudio.value && video == null) return null
        val s = advanced.current
        // The cut fields are only shown (and only matter) when the audio file is saved; a leftover
        // invalid value must not block a video-only download.
        val trim = if (s.manualTrim && _saveAudio.value) {
            try {
                TimeCodes.range(_trimStart.value, _trimEnd.value)
            } catch (_: IllegalArgumentException) {
                return null
            }
        } else {
            TimeRange.ALL
        }
        return DownloadOptions(saveAudio = _saveAudio.value, video = video, processing = s.processing(trim))
    }

    /** For retries that don't repeat a specific earlier request: today's settings, no cut, no dialog. */
    private fun currentProcessing() = advanced.current.processing().copy(trim = TimeRange.ALL, reviewTags = false)

    val history: StateFlow<List<HistoryEntry>> = controller.history.entries

    /** The pasted video was already saved before: shown as a hint, never blocks a new download. */
    val alreadySaved: StateFlow<HistoryEntry?> by lazy {
        combine(target, history) { t, _ -> (t as? Target.Single)?.let { controller.history.lastSaved(it.videoId.value) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

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
        // A cut belongs to one video.
        _trimStart.value = ""
        _trimEnd.value = ""
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
                val page = controller.engine.listCollection(ref)
                // The history loads in the background at app start; make sure it's there before
                // deciding which songs are new (a link shared into a cold app would otherwise tick all).
                controller.history.load()
                val saved = if (advanced.current.onlyNewSongs) controller.history.savedAudioIds().mapNotNullTo(HashSet()) { id -> runCatching { VideoId.of(id) }.getOrNull() } else emptySet()
                CollectionUi.Loaded(CollectionListing.of(page, saved))
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
            is Target.Single -> singleOptions()?.let { controller.start(context, t.videoId, it) }
            is Target.Collection -> {
                val listing = (_collection.value as? CollectionUi.Loaded)?.listing ?: return
                controller.startBatch(context, listing.toBatchRequest(), advanced.current.processing())
            }
            Target.None -> Unit
        }
    }

    /** Repeats exactly what the failed download asked for - not whatever the toggles say now. */
    fun retry(context: Context, videoId: VideoId) {
        controller.dismissResult()
        controller.start(context, videoId, controller.lastOptions)
    }

    /** Audio and video were asked for, the audio was saved, the video wasn't: try only the video again. */
    fun retryVideo(context: Context, videoId: VideoId) {
        controller.dismissResult()
        val quality = controller.lastOptions.video ?: _videoQuality.value
        controller.start(context, videoId, DownloadOptions.videoOnly(quality).copy(processing = controller.lastOptions.processing))
    }

    /**
     * "Retry" in the history: put the link back and ask again for what *failed* - for a partly saved
     * entry (audio saved, video failed) that's just the video, so the audio isn't saved twice.
     * Songs from a mix/album come back as single audio downloads (album numbering isn't kept).
     */
    fun retryFromHistory(context: Context, entry: HistoryEntry) {
        onInputChanged(entry.watchUrl)
        controller.start(context, VideoId.of(entry.videoId), entry.retryOptions().copy(processing = currentProcessing()))
    }

    fun clearHistory() {
        viewModelScope.launch { controller.history.clear() }
    }

    fun retryFailed(context: Context) {
        val finished = controller.batch.value?.takeIf { it.finished } ?: return
        val failed = finished.failedEntries.ifEmpty { return }
        controller.startBatch(context, BatchRequest(finished.title, finished.kind, failed), controller.lastBatchProcessing)
    }

    /** Back from the batch summary to the song list (e.g. to pick more songs). */
    fun dismissBatch() = controller.dismissBatch()

    fun cancel() = controller.cancel()
}
