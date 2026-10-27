package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.engine.CollectionItem
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.CollectionKind
import io.github.rudolfhk.audiosaver.core.link.VideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One song of a batch and the [TrackContext] (album/track number) it is saved with. */
data class BatchEntry(val item: CollectionItem, val context: TrackContext)

data class BatchRequest(val title: String, val kind: CollectionKind, val entries: List<BatchEntry>) {
    companion object {
        /**
         * Builds the batch for the songs the user ticked. For albums the track number is the song's
         * position in the *whole* album (not in the selection), so tagging 3 of 10 songs still gives
         * "track 7/10"; everything lands in a folder named after the album.
         */
        fun forSelection(
            title: String,
            kind: CollectionKind,
            uploader: String?,
            allItems: List<CollectionItem>,
            selected: Set<VideoId>,
            albumTotal: Int? = null,
        ): BatchRequest {
            val albumArtist = if (kind == CollectionKind.ALBUM) albumArtistOf(uploader, allItems) else null
            val total = albumTotal ?: allItems.size
            val entries = allItems.mapIndexedNotNull { index, item ->
                if (item.videoId !in selected || !item.available) return@mapIndexedNotNull null
                val context = if (kind == CollectionKind.ALBUM) {
                    TrackContext(
                        album = title,
                        albumArtist = albumArtist,
                        trackNumber = index + 1,
                        trackTotal = total,
                        subfolder = title,
                    )
                } else {
                    TrackContext.NONE
                }
                BatchEntry(item, context)
            }
            return BatchRequest(title, kind, entries)
        }

        /** The playlist's channel if it has a real name, otherwise the artist most of its songs are by. */
        private fun albumArtistOf(uploader: String?, items: List<CollectionItem>): String? =
            uploader?.removeSuffix(" - Topic")?.trim()?.takeIf { it.isNotEmpty() }
                ?: items.mapNotNull { it.uploader?.removeSuffix(" - Topic")?.trim()?.takeIf(String::isNotEmpty) }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}

sealed interface ItemStatus {
    data object Queued : ItemStatus
    data class Active(val progress: DownloadState) : ItemStatus
    data class Saved(val saved: SavedAudio, val warning: String?) : ItemStatus
    data class Failed(val reason: FailureReason, val detail: String) : ItemStatus
    data object Cancelled : ItemStatus
}

data class BatchItemState(val entry: BatchEntry, val status: ItemStatus)

data class BatchState(val title: String, val kind: CollectionKind, val items: List<BatchItemState>, val finished: Boolean) {
    val total: Int get() = items.size
    val savedCount: Int get() = items.count { it.status is ItemStatus.Saved }
    val failedCount: Int get() = items.count { it.status is ItemStatus.Failed }
    val cancelledCount: Int get() = items.count { it.status is ItemStatus.Cancelled }
    val activeIndex: Int get() = items.indexOfFirst { it.status is ItemStatus.Active }
    val isActive: Boolean get() = !finished

    /** Number of songs no longer waiting, for the "3 of 12" counter. */
    val processedCount: Int get() = items.count { it.status !is ItemStatus.Queued && it.status !is ItemStatus.Active }

    val failedEntries: List<BatchEntry> get() = items.filter { it.status is ItemStatus.Failed }.map { it.entry }
}

/**
 * Downloads a batch one song at a time through the normal single-song pipeline (so every file is
 * resolved, tagged and verified exactly like a single download). A failing song doesn't stop the
 * batch - except when YouTube starts rate-limiting or bot-checking, where carrying on would only
 * fail every remaining song and make the block worse.
 */
class BatchDownloader(
    private val pipeline: AudioDownloadPipeline,
    private val pauseBetweenItemsMs: Long = 1_000,
) {

    suspend fun run(request: BatchRequest, onState: (BatchState) -> Unit) {
        val items = request.entries.map { BatchItemState(it, ItemStatus.Queued) }.toMutableList()
        fun emit(finished: Boolean = false) = onState(BatchState(request.title, request.kind, items.toList(), finished))

        emit()
        var stopReason: Pair<FailureReason, String>? = null
        try {
            for (index in items.indices) {
                stopReason?.let { (reason, detail) ->
                    items[index] = items[index].copy(status = ItemStatus.Failed(reason, "Not attempted: $detail"))
                    emit()
                    continue
                }
                if (index > 0) delay(pauseBetweenItemsMs)

                val entry = items[index].entry
                var last: DownloadState = DownloadState.Idle
                pipeline.run(entry.item.videoId, entry.context) { state ->
                    last = state
                    items[index] = items[index].copy(status = ItemStatus.Active(state))
                    emit()
                }
                items[index] = items[index].copy(status = finalStatus(last))
                val failure = last as? DownloadState.Failed
                if (failure != null && failure.reason in STOP_BATCH_REASONS) {
                    stopReason = failure.reason to "${failure.reason.userTitle} - stopped the rest of the batch."
                }
                emit()
            }
            emit(finished = true)
        } catch (e: CancellationException) {
            for (i in items.indices) {
                val status = items[i].status
                if (status is ItemStatus.Queued || status is ItemStatus.Active) {
                    items[i] = items[i].copy(status = ItemStatus.Cancelled)
                }
            }
            emit(finished = true)
            throw e
        }
    }

    private fun finalStatus(state: DownloadState): ItemStatus = when (state) {
        is DownloadState.Done -> ItemStatus.Saved(state.saved, state.taggingWarning)
        is DownloadState.Failed -> ItemStatus.Failed(state.reason, state.detail)
        is DownloadState.Cancelled -> ItemStatus.Cancelled
        else -> ItemStatus.Failed(FailureReason.UNKNOWN, "Download ended without a result (last state: $state)")
    }

    companion object {
        val STOP_BATCH_REASONS = setOf(FailureReason.RATE_LIMITED, FailureReason.BOT_CHECK)
    }
}
