package io.github.rudolfhk.audiosaver.core.history

import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadOptions
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.ItemStatus
import io.github.rudolfhk.audiosaver.core.pipeline.VideoResult

/**
 * Turns the states a download reports into history entries - exactly one per finished single
 * download and one per finished song of a batch, however many progress updates arrive.
 *
 * Only finished outcomes are history: saved, partly saved, failed. A download the user cancelled
 * is not "failed", and songs a cancelled batch never reached were never tried, so neither is
 * recorded. Songs a batch skipped after YouTube started rate-limiting *are* recorded as failed
 * ("Not attempted: ..."), because those are exactly the ones worth retrying.
 */
class HistoryRecorder(private val clock: () -> Long = System::currentTimeMillis) {

    private val titles = HashMap<VideoId, Pair<String, String?>>()
    private var batchRun: Long? = null
    private val recordedBatchItems = HashSet<Int>()

    /** Feed every state of a single download; returns the entry to store when it has finished. */
    @Synchronized
    fun onSingleState(videoId: VideoId, options: DownloadOptions, state: DownloadState): HistoryEntry? {
        when (state) {
            is DownloadState.Downloading -> titles[videoId] = state.title to state.uploader
            is DownloadState.Saving -> titles.putIfAbsent(videoId, state.title to null)
            is DownloadState.Merging -> titles.putIfAbsent(videoId, state.title to null)
            is DownloadState.Done -> return singleEntry(videoId, options, state).also { titles.remove(videoId) }
            is DownloadState.Failed -> return failedEntry(videoId, options, state).also { titles.remove(videoId) }
            is DownloadState.Cancelled -> titles.remove(videoId)
            DownloadState.Idle, is DownloadState.Resolving -> Unit
        }
        return null
    }

    /**
     * Feed every state of a batch run; returns the entries for songs that finished since the last
     * call. [runId] distinguishes runs (a "Retry failed" run reuses the same songs).
     */
    @Synchronized
    fun onBatchState(runId: Long, state: BatchState): List<HistoryEntry> {
        if (batchRun != runId) {
            batchRun = runId
            recordedBatchItems.clear()
        }
        return state.items.mapIndexedNotNull { index, item ->
            val outcome = when (val status = item.status) {
                is ItemStatus.Saved -> FileOutcome.saved(status.saved)
                is ItemStatus.Failed -> FileOutcome.failed(status.reason, status.detail)
                else -> return@mapIndexedNotNull null
            }
            if (!recordedBatchItems.add(index)) return@mapIndexedNotNull null
            HistoryEntry(
                timeMillis = clock(),
                videoId = item.entry.item.videoId.value,
                title = item.entry.item.title,
                artist = item.entry.item.uploader,
                collection = state.title,
                audio = outcome,
            )
        }
    }

    private fun singleEntry(videoId: VideoId, options: DownloadOptions, done: DownloadState.Done): HistoryEntry {
        val audio = if (options.saveAudio) {
            done.saved?.let { FileOutcome.saved(it) } ?: FileOutcome.failed(FailureReason.UNKNOWN, "Finished without an audio file")
        } else {
            null
        }
        val video = if (options.saveVideo) {
            when (val v = done.video) {
                is VideoResult.Saved -> FileOutcome.saved(v.saved, "${v.height}p")
                is VideoResult.Failed -> FileOutcome.failed(v.reason, v.detail)
                null -> FileOutcome.failed(FailureReason.UNKNOWN, "Finished without a video result")
            }
        } else {
            null
        }
        return entry(videoId, options, audio, video)
    }

    private fun failedEntry(videoId: VideoId, options: DownloadOptions, failed: DownloadState.Failed): HistoryEntry {
        val outcome = FileOutcome.failed(failed.reason, failed.detail)
        return entry(videoId, options, outcome.takeIf { options.saveAudio }, outcome.takeIf { options.saveVideo })
    }

    private fun entry(videoId: VideoId, options: DownloadOptions, audio: FileOutcome?, video: FileOutcome?): HistoryEntry {
        val (title, artist) = titles[videoId] ?: (videoId.historyTitleFallback() to null)
        return HistoryEntry(
            timeMillis = clock(),
            videoId = videoId.value,
            title = title,
            artist = artist,
            audio = audio,
            video = video,
            videoQuality = options.video?.name,
        )
    }
}
