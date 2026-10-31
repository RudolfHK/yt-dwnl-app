package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.engine.VideoStreamSource
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.tag.Mp4Tagger
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import io.github.rudolfhk.audiosaver.core.video.DashMp4Muxer
import io.github.rudolfhk.audiosaver.core.video.MuxException
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class MediaKind { AUDIO, VIDEO }

/** Where finished files go. On Android this is MediaStore (Music/ or Movies/); in tests a plain directory. */
interface AudioSink {
    /**
     * Copies [file] into permanent storage and returns what actually landed there, read back from
     * the storage itself (not just echoed from the request).
     */
    suspend fun save(
        file: File,
        displayName: String,
        mimeType: String,
        metadata: TrackMetadata,
        subfolder: String? = null,
        kind: MediaKind = MediaKind.AUDIO,
    ): SavedAudio
}

/**
 * What to save for a single video. The audio file is the app's main job; the MP4 is an optional
 * extra. [saveAudio] = false is "retry just the video" after the audio was already saved.
 */
data class DownloadOptions(val saveAudio: Boolean = true, val video: VideoQuality? = null) {
    init {
        require(saveAudio || video != null) { "Nothing to download" }
    }

    companion object {
        val AUDIO_ONLY = DownloadOptions()
    }
}

/** How the optional video part ended. The audio's outcome is reported separately. */
sealed interface VideoResult {
    data class Saved(val saved: SavedAudio, val width: Int, val height: Int, val fps: Int, val warning: String?) : VideoResult
    data class Failed(val reason: FailureReason, val detail: String) : VideoResult
}

/**
 * Where a track sits when it's downloaded as part of a collection. Only albums carry album fields;
 * a mix or playlist is not an album, so its tracks are tagged exactly like single downloads.
 */
data class TrackContext(
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    /** Sub-folder below the app's music folder, e.g. the album name. Sanitised by the pipeline. */
    val subfolder: String? = null,
) {
    companion object {
        val NONE = TrackContext()
    }
}

data class SavedAudio(
    val displayName: String,
    /** Human-readable location, e.g. "Music/AudioSaver". */
    val location: String,
    /** content:// URI on Android, file path elsewhere. */
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Resolving(val videoId: VideoId) : DownloadState

    /**
     * [totalBytes] == null means size unknown: show an indeterminate bar, never a guessed percentage.
     * With a video, the counts cover audio + video together so one bar spans both downloads.
     */
    data class Downloading(
        val videoId: VideoId,
        val title: String,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val withVideo: Boolean = false,
    ) : DownloadState {
        val fraction: Float? get() = totalBytes?.let { (bytesDownloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }

    data class Saving(val videoId: VideoId, val title: String) : DownloadState

    /** Combining the video and audio streams into one MP4, tagging and saving it. */
    data class Merging(val videoId: VideoId, val title: String) : DownloadState

    /**
     * [saved] is the audio file (null only when the audio wasn't requested, i.e. "retry video");
     * [video] is null when no video was requested.
     */
    data class Done(val videoId: VideoId, val saved: SavedAudio?, val taggingWarning: String?, val video: VideoResult? = null) : DownloadState
    data class Failed(val videoId: VideoId, val reason: FailureReason, val detail: String) : DownloadState
    data class Cancelled(val videoId: VideoId) : DownloadState

    val isActive: Boolean get() = this is Resolving || this is Downloading || this is Saving || this is Merging
}

/**
 * The whole "link in, audio file out" job: resolve -> download to a temp file -> tag -> save -> verify,
 * optionally followed by the video: download -> merge with the audio -> tag -> save -> verify.
 * Phases are reported through [onState]; any failure ends in [DownloadState.Failed] with the most
 * specific reason available, cancellation in [DownloadState.Cancelled].
 */
class AudioDownloadPipeline(
    private val engine: ExtractionEngine,
    private val downloader: HttpAudioDownloader,
    private val sink: AudioSink,
    private val workDir: File,
) {

    suspend fun run(
        videoId: VideoId,
        context: TrackContext = TrackContext.NONE,
        options: DownloadOptions = DownloadOptions.AUDIO_ONLY,
        onState: (DownloadState) -> Unit,
    ) {
        val raw = File(workDir, "$videoId.part")
        val tagged = File(workDir, "$videoId.tagged")
        val rawVideo = File(workDir, "$videoId.video.part")
        val merged = File(workDir, "$videoId.merged.mp4")
        val taggedVideo = File(workDir, "$videoId.tagged.mp4")
        try {
            onState(DownloadState.Resolving(videoId))
            val audio = engine.resolve(videoId)
            workDir.mkdirs()

            // Decide the video part up front, so a missing stream or a full disk is reported
            // before anything big is downloaded - and never costs the audio.
            var videoResult: VideoResult? = null
            var videoStream: VideoStreamSource? = null
            options.video?.let { quality ->
                val (stream, problem) = videoStreamFor(audio, quality)
                videoResult = problem ?: stream?.let { checkSpace(audio, it) }
                if (videoResult == null) videoStream = stream
            }
            if (!options.saveAudio && videoStream == null) {
                onState(DownloadState.Done(videoId, null, null, videoResult))
                return
            }

            val audioLength = audio.stream.contentLength
            val combinedTotal = if (videoStream == null) audioLength else audioLength?.let { a -> videoStream.contentLength?.let { a + it } }
            val audioProgress = ThrottledProgress { done, total ->
                // Audio only: whatever the server says (Content-Length) beats nothing. With a video,
                // only a total covering both files is honest, otherwise the bar is indeterminate.
                val shownTotal = if (videoStream == null) total ?: combinedTotal else combinedTotal
                onState(DownloadState.Downloading(videoId, audio.title, done, shownTotal, withVideo = videoStream != null))
            }
            downloader.download(audio.stream, raw, audioProgress::report)
            audioProgress.flush()

            var saved: SavedAudio? = null
            var taggingWarning: String? = null
            if (options.saveAudio) {
                onState(DownloadState.Saving(videoId, audio.title))
                val metadata = metadataFor(audio, context)
                val (fileToSave, warning) = withContext(Dispatchers.IO) { tagIfPossible(audio, raw, tagged, metadata) }
                taggingWarning = warning
                val fileName = FileNames.forTrack(metadata.artist, metadata.title, audio.stream.container.extension, context.trackNumber)
                saved = saveVerified(fileToSave, fileName, audio.stream.container.mimeType, metadata, context.subfolder?.let(FileNames::forFolder), MediaKind.AUDIO)
                tagged.delete()
            }

            if (videoStream != null) {
                videoResult = try {
                    val offset = raw.length()
                    val videoProgress = ThrottledProgress { done, _ ->
                        onState(DownloadState.Downloading(videoId, audio.title, offset + done, combinedTotal, withVideo = true))
                    }
                    downloader.download(videoStream, rawVideo, videoProgress::report)
                    videoProgress.flush()
                    onState(DownloadState.Merging(videoId, audio.title))
                    saveVideo(audio, videoStream, raw, rawVideo, merged, taggedVideo)
                } catch (e: Exception) {
                    if (e is CancellationException || !currentCoroutineContext().isActive) throw e
                    // The audio is already saved: report the video's own failure next to it.
                    VideoResult.Failed(
                        (e as? AudioSaveException)?.reason ?: FailureReason.UNKNOWN,
                        (e as? AudioSaveException)?.detail ?: "${e::class.java.simpleName}: ${e.message}",
                    )
                }
            }
            onState(DownloadState.Done(videoId, saved, taggingWarning, videoResult))
        } catch (e: Exception) {
            // Blocking I/O aborted by cancellation surfaces as an IOException, not a CancellationException.
            if (e is CancellationException || !currentCoroutineContext().isActive) {
                onState(DownloadState.Cancelled(videoId))
                throw e as? CancellationException ?: CancellationException("Cancelled").apply { initCause(e) }
            }
            onState(
                if (e is AudioSaveException) {
                    DownloadState.Failed(videoId, e.reason, e.detail)
                } else {
                    DownloadState.Failed(videoId, FailureReason.UNKNOWN, "${e::class.java.simpleName}: ${e.message}")
                },
            )
        } finally {
            listOf(raw, tagged, rawVideo, merged, taggedVideo).forEach(File::delete)
        }
    }

    private fun videoStreamFor(audio: ResolvedAudio, quality: VideoQuality): Pair<VideoStreamSource?, VideoResult.Failed?> {
        if (audio.stream.container != AudioContainer.M4A) {
            return null to VideoResult.Failed(
                FailureReason.NO_AUDIO_STREAM,
                "An MP4 needs the AAC (M4A) audio stream, and this video only offers ${audio.stream.container.extension}.",
            )
        }
        val stream = quality.pick(audio.videoStreams)
            ?: return null to VideoResult.Failed(
                FailureReason.NO_VIDEO_STREAM,
                "YouTube offered ${audio.videoStreams.size} video stream(s), none of them H.264 in MP4.",
            )
        return stream to null
    }

    /**
     * Peak use in the work folder: raw audio + raw video + merged file, then merged + tagged copy.
     * The video's size is known before downloading, so a full disk is reported before wasting data.
     */
    private fun checkSpace(audio: ResolvedAudio, video: VideoStreamSource): VideoResult.Failed? {
        val videoBytes = video.contentLength ?: return null
        val needed = (2 * videoBytes + 2 * (audio.stream.contentLength ?: 0)) * 11 / 10 + 20L * 1024 * 1024
        val free = workDir.usableSpace
        if (free <= 0 || free >= needed) return null
        return VideoResult.Failed(
            FailureReason.STORAGE,
            "Not enough free space for the ${video.height}p video: it needs about ${needed / 1_000_000} MB while working, " +
                "${free / 1_000_000} MB are free. The audio was still saved.",
        )
    }

    private suspend fun saveVideo(
        audio: ResolvedAudio,
        stream: VideoStreamSource,
        rawAudio: File,
        rawVideo: File,
        merged: File,
        tagged: File,
    ): VideoResult.Saved {
        withContext(Dispatchers.IO) {
            try {
                DashMp4Muxer.mux(rawVideo, rawAudio, merged)
            } catch (e: MuxException) {
                throw AudioSaveException(FailureReason.DOWNLOAD_FAILED, "Couldn't merge video and audio: ${e.message}", e)
            }
            rawVideo.delete()
        }
        val metadata = metadataFor(audio, TrackContext.NONE)
        val warning = withContext(Dispatchers.IO) {
            try {
                Mp4Tagger.tag(merged, tagged, metadata)
                merged.delete()
                null
            } catch (e: IOException) {
                "Saved without embedded tags: ${e.message}"
            }
        }
        val file = if (tagged.exists() && warning == null) tagged else merged
        val name = FileNames.forTrack(metadata.artist, metadata.title, "mp4")
        val saved = saveVerified(file, name, VIDEO_MIME, metadata, null, MediaKind.VIDEO)
        return VideoResult.Saved(saved, stream.width, stream.height, stream.fps, warning)
    }

    private suspend fun saveVerified(
        file: File,
        displayName: String,
        mimeType: String,
        metadata: TrackMetadata,
        subfolder: String?,
        kind: MediaKind,
    ): SavedAudio {
        val saved = try {
            sink.save(file, displayName, mimeType, metadata, subfolder, kind)
        } catch (e: IOException) {
            throw AudioSaveException(FailureReason.STORAGE, e.message ?: e::class.java.simpleName, e)
        } catch (e: SecurityException) {
            throw AudioSaveException(FailureReason.STORAGE, e.message ?: "Permission denied", e)
        }
        verify(saved, expectedSize = file.length())
        return saved
    }

    private fun metadataFor(audio: ResolvedAudio, context: TrackContext) = TrackMetadata(
        title = audio.title,
        artist = audio.uploader,
        year = audio.uploadYear,
        comment = audio.videoId.watchUrl,
        album = context.album,
        albumArtist = context.albumArtist,
        trackNumber = context.trackNumber,
        trackTotal = context.trackTotal,
    )

    private fun tagIfPossible(audio: ResolvedAudio, raw: File, tagged: File, metadata: TrackMetadata): Pair<File, String?> {
        if (!audio.stream.container.supportsTagging) {
            return raw to "Saved as ${audio.stream.container.extension.uppercase()} without embedded tags " +
                "(no M4A stream was offered for this video)."
        }
        return try {
            Mp4Tagger.tag(raw, tagged, metadata)
            tagged to null
        } catch (e: IOException) {
            // The audio itself is fine - keep it rather than failing the whole job, but say so.
            raw to "Saved without embedded tags: ${e.message}"
        }
    }

    /** Never report success on the strength of "no exception was thrown". */
    private fun verify(saved: SavedAudio, expectedSize: Long) {
        if (saved.sizeBytes <= 0L) {
            throw AudioSaveException(FailureReason.STORAGE, "The saved file is empty (${saved.uri})")
        }
        if (saved.sizeBytes != expectedSize) {
            throw AudioSaveException(
                FailureReason.STORAGE,
                "The saved file has ${saved.sizeBytes} bytes, expected $expectedSize (${saved.uri})",
            )
        }
    }

    companion object {
        const val VIDEO_MIME = "video/mp4"
    }

    /** Reports at most every 1% / 200 ms so the UI and notification aren't flooded. */
    private class ThrottledProgress(private val emit: (Long, Long?) -> Unit) {
        private var lastEmitAt = 0L
        private var lastPermille = -1L
        private var last: Pair<Long, Long?> = 0L to null

        fun report(done: Long, total: Long?) {
            last = done to total
            val now = System.nanoTime()
            val permille = total?.let { if (it > 0) done * 1000 / it else 0 }
            val stepReached = permille != null && permille / 10 != lastPermille / 10
            if (stepReached || now - lastEmitAt > 200_000_000L || done == total) {
                lastEmitAt = now
                lastPermille = permille ?: lastPermille
                emit(done, total)
            }
        }

        fun flush() = emit(last.first, last.second)
    }
}

object FileNames {
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private const val MAX_BASE_LENGTH = 120

    /** "Artist - Title.m4a", or "03 - Title.m4a" inside an album folder. */
    fun forTrack(artist: String?, title: String, extension: String, trackNumber: Int? = null): String {
        val base = if (trackNumber != null) {
            "%02d - %s".format(trackNumber, title)
        } else {
            listOfNotNull(artist?.takeIf { it.isNotBlank() && !title.contains(it, ignoreCase = true) }, title)
                .joinToString(" - ")
        }
        return clean(base, "audio") + "." + extension
    }

    fun forFolder(name: String): String = clean(name, "Untitled")

    private fun clean(base: String, fallback: String): String =
        ILLEGAL.replace(base, "_").replace(Regex("\\s+"), " ").trim().trim('.').ifEmpty { fallback }
            .take(MAX_BASE_LENGTH).trimEnd()
}
