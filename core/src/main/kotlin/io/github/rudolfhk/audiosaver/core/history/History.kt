package io.github.rudolfhk.audiosaver.core.history

import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadOptions
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import kotlinx.serialization.Serializable

enum class HistoryStatus {
    /** Everything that was asked for was saved. */
    SAVED,

    /** Audio and video were asked for; the audio was saved, the video failed. */
    PARTIAL,
    FAILED,
}

/** One finished download - a single link, or one song of a mix/album batch. */
@Serializable
data class HistoryEntry(
    val timeMillis: Long,
    val videoId: String,
    val title: String,
    val artist: String? = null,
    /** The mix/album/playlist the song was downloaded from, if any. */
    val collection: String? = null,
    /** Null when no audio file was requested. */
    val audio: FileOutcome? = null,
    /** Null when no video file was requested. */
    val video: FileOutcome? = null,
    /** The video quality that was asked for, so "Retry" can ask for the same again. */
    val videoQuality: String? = null,
) {
    val status: HistoryStatus
        get() {
            val outcomes = listOfNotNull(audio, video)
            return when {
                outcomes.isNotEmpty() && outcomes.all { it.saved != null } -> HistoryStatus.SAVED
                outcomes.any { it.saved != null } -> HistoryStatus.PARTIAL
                else -> HistoryStatus.FAILED
            }
        }

    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
    val requestedQuality: VideoQuality? get() = videoQuality?.let(VideoQuality::fromName)
}

/** What happened to one requested file: saved (with where) or failed (with why). */
@Serializable
data class FileOutcome(
    val saved: SavedFile? = null,
    /** [FailureReason] name, stored as text so renaming a reason never breaks old entries. */
    val failureReason: String? = null,
    val failureDetail: String? = null,
) {
    val reason: FailureReason?
        get() = failureReason?.let { name -> FailureReason.entries.firstOrNull { it.name == name } ?: FailureReason.UNKNOWN }

    companion object {
        fun saved(saved: SavedAudio, quality: String? = null) = FileOutcome(saved = SavedFile.of(saved, quality))
        fun failed(reason: FailureReason, detail: String) = FileOutcome(failureReason = reason.name, failureDetail = detail)
    }
}

@Serializable
data class SavedFile(
    val name: String,
    val location: String,
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** e.g. "720p" for videos. */
    val quality: String? = null,
) {
    fun toSavedAudio() = SavedAudio(name, location, uri, mimeType, sizeBytes)

    companion object {
        fun of(saved: SavedAudio, quality: String?) =
            SavedFile(saved.displayName, saved.location, saved.uri, saved.mimeType, saved.sizeBytes, quality)
    }
}

internal fun VideoId.historyTitleFallback() = "Video $value"

/**
 * What "Retry" asks for: the formats that failed - for a partly saved entry (audio saved, video
 * failed) only the video, so the audio isn't saved a second time. Everything requested otherwise.
 */
fun HistoryEntry.retryOptions(): DownloadOptions {
    val audioFailed = audio?.let { it.saved == null } == true
    val videoFailed = video?.let { it.saved == null } == true
    val quality = requestedQuality ?: VideoQuality.BEST
    return if (audioFailed || videoFailed) {
        DownloadOptions(saveAudio = audioFailed, video = quality.takeIf { videoFailed })
    } else {
        DownloadOptions(saveAudio = audio != null || video == null, video = quality.takeIf { video != null })
    }
}
