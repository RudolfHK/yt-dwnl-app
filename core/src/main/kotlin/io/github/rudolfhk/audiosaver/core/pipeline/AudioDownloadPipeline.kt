package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.engine.VideoStreamSource
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.tag.CoverArt
import io.github.rudolfhk.audiosaver.core.tag.CoverGeometry
import io.github.rudolfhk.audiosaver.core.tag.Mp4Tagger
import io.github.rudolfhk.audiosaver.core.tag.ParsedTitle
import io.github.rudolfhk.audiosaver.core.tag.TitleParser
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import io.github.rudolfhk.audiosaver.core.video.DashMp4Muxer
import io.github.rudolfhk.audiosaver.core.video.MuxException
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * What to save for a single video: the audio file (M4A), the video file (MP4, at [video] quality),
 * or both. With video only, the audio stream is still downloaded - it is the MP4's soundtrack - but
 * no audio file is saved. Batches from mixes/albums always use [AUDIO_ONLY].
 */
data class DownloadOptions(
    val saveAudio: Boolean = true,
    val video: VideoQuality? = null,
    val processing: Processing = Processing(),
) {
    init {
        require(saveAudio || video != null) { "Nothing to download" }
    }

    val saveVideo: Boolean get() = video != null

    companion object {
        val AUDIO_ONLY = DownloadOptions()
        fun videoOnly(quality: VideoQuality) = DownloadOptions(saveAudio = false, video = quality)
    }
}

/**
 * How the video part ended. When both files were requested, this sits next to the audio's
 * outcome (a failed video never costs the saved audio). For a video-only job a failure is
 * reported as [DownloadState.Failed] instead, like any other failed download.
 */
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
        /** Video-only job: the bytes include the soundtrack, but only an MP4 will be saved. */
        val videoOnly: Boolean = false,
        val uploader: String? = null,
    ) : DownloadState {
        val fraction: Float? get() = totalBytes?.let { (bytesDownloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }

    data class Saving(val videoId: VideoId, val title: String) : DownloadState

    /** Combining the video and audio streams into one MP4, tagging and saving it. */
    data class Merging(val videoId: VideoId, val title: String) : DownloadState

    /** A slower step after the download: analysis, MP3 encoding, Opus remuxing, or waiting for the user's tags. */
    data class Processing(val videoId: VideoId, val title: String, val step: ProcessingStep) : DownloadState

    /**
     * [saved] is the audio file (null when only the video was requested);
     * [video] is null when no video was requested.
     */
    data class Done(val videoId: VideoId, val saved: SavedAudio?, val taggingWarning: String?, val video: VideoResult? = null) : DownloadState
    data class Failed(val videoId: VideoId, val reason: FailureReason, val detail: String) : DownloadState
    data class Cancelled(val videoId: VideoId) : DownloadState

    val isActive: Boolean get() = this is Resolving || this is Downloading || this is Saving || this is Merging || this is Processing
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
    private val toolkit: Toolkit = Toolkit(),
) {
    private val finisher = AudioFinisher(toolkit, workDir)

    suspend fun run(
        videoId: VideoId,
        context: TrackContext = TrackContext.NONE,
        options: DownloadOptions = DownloadOptions.AUDIO_ONLY,
        onState: (DownloadState) -> Unit,
    ) {
        val raw = File(workDir, "$videoId.part")
        val muxAudio = File(workDir, "$videoId.mux.part")
        val rawVideo = File(workDir, "$videoId.video.part")
        val merged = File(workDir, "$videoId.merged.mp4")
        val taggedVideo = File(workDir, "$videoId.tagged.mp4")
        val processing = options.processing
        try {
            coroutineScope {
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
                    // Video only and it can't be made: that's simply this download failing.
                    throw (videoResult as VideoResult.Failed).let { AudioSaveException(it.reason, it.detail) }
                }

                // The audio file comes from the stream matching the chosen format; the video is
                // always merged with the AAC stream (an MP4 can't hold YouTube's WebM Opus).
                val fileSource = if (options.saveAudio && processing.audioFormat == AudioFormat.OPUS) audio.opusStream ?: audio.stream else audio.stream
                val separateMuxAudio = videoStream != null && fileSource !== audio.stream
                // One bar over everything downloaded - known only if every part's size is known.
                val parts = listOfNotNull(fileSource, audio.stream.takeIf { separateMuxAudio }, videoStream).map { it.contentLength }
                val combinedTotal = if (parts.all { it != null }) parts.sumOf { it!! } else null

                // Tags, cover art and the user's review run alongside the download.
                val proposed = metadataFor(audio, context, processing)
                val cover = if (processing.coverArt && toolkit.cropper != null) async { fetchCover(audio) } else null
                val reviewer = toolkit.reviewer?.takeIf { processing.reviewTags }
                val review = reviewer?.let { r -> async { r.review(TagProposal(audio.title, proposed)) } }

                val withVideo = videoStream != null
                fun progress(offset: Long) = ThrottledProgress { done, total ->
                    // A single file: whatever the server says (Content-Length) beats nothing. With
                    // several, only a total covering all of them is honest, else indeterminate.
                    val shownTotal = if (!withVideo && !separateMuxAudio) total ?: combinedTotal else combinedTotal
                    onState(
                        DownloadState.Downloading(
                            videoId, audio.title, offset + done, shownTotal,
                            withVideo = withVideo, videoOnly = !options.saveAudio, uploader = proposed.artist,
                        ),
                    )
                }
                val first = progress(0)
                downloader.download(fileSource, raw, first::report)
                first.flush()
                var downloaded = raw.length()

                // Wait for the user's tags (if asked) and the cover before writing any tags.
                var metadata = proposed
                if (review != null) {
                    if (!review.isCompleted) onState(DownloadState.Processing(videoId, proposed.title, ProcessingStep.WAITING_FOR_TAGS))
                    metadata = review.await() ?: throw CancellationException("Cancelled while confirming the tags")
                }
                val coverArt = cover?.await()
                metadata = metadata.copy(cover = coverArt?.first)
                val coverWarning = coverArt?.second

                var saved: SavedAudio? = null
                var warnings: List<String> = listOfNotNull(coverWarning)
                if (options.saveAudio) {
                    val finished = finisher.finish(raw, fileSource.container, metadata, processing) { step ->
                        onState(DownloadState.Processing(videoId, metadata.title, step))
                    }
                    onState(DownloadState.Saving(videoId, metadata.title))
                    val fileName = FileNames.forTrack(metadata.artist, metadata.title, finished.extension, context.trackNumber)
                    saved = saveVerified(finished.file, fileName, finished.mimeType, finished.metadata, context.subfolder?.let(FileNames::forFolder), MediaKind.AUDIO)
                    warnings = warnings + finished.warnings
                }

                val video = videoStream
                if (video != null) {
                    videoResult = try {
                        val audioForVideo = if (separateMuxAudio) {
                            val p = progress(downloaded)
                            downloader.download(audio.stream, muxAudio, p::report)
                            p.flush()
                            downloaded += muxAudio.length()
                            muxAudio
                        } else {
                            raw
                        }
                        val videoProgress = progress(downloaded)
                        downloader.download(video, rawVideo, videoProgress::report)
                        videoProgress.flush()
                        onState(DownloadState.Merging(videoId, metadata.title))
                        saveVideo(metadata.copy(replayGain = null), video, audioForVideo, rawVideo, merged, taggedVideo)
                    } catch (e: Exception) {
                        if (e is CancellationException || !currentCoroutineContext().isActive) throw e
                        // The audio is already saved: report the video's own failure next to it.
                        VideoResult.Failed(
                            (e as? AudioSaveException)?.reason ?: FailureReason.UNKNOWN,
                            (e as? AudioSaveException)?.detail ?: "${e::class.java.simpleName}: ${e.message}",
                        )
                    }
                }
                val result = videoResult
                if (!options.saveAudio && result is VideoResult.Failed) throw AudioSaveException(result.reason, result.detail)
                onState(DownloadState.Done(videoId, saved, warnings.joinToString("\n").ifEmpty { null }, result))
            }
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
            listOf(raw, muxAudio, rawVideo, merged, taggedVideo).forEach(File::delete)
            workDir.listFiles { f -> f.name.startsWith("$videoId.") }?.forEach(File::delete)
        }
    }

    /** The cover, or a warning saying why there's none. Never fails the download. */
    private suspend fun fetchCover(audio: ResolvedAudio): Pair<CoverArt?, String?> {
        val thumbnail = CoverGeometry.best(audio.thumbnails) ?: return null to null
        return try {
            val bytes = downloader.fetchBytes(thumbnail.url)
            val cover = withContext(Dispatchers.Default) { toolkit.cropper?.cropSquare(bytes, COVER_SIZE) }
            cover to (if (cover == null) "No cover art: the thumbnail couldn't be decoded." else null)
        } catch (e: AudioSaveException) {
            null to "No cover art: ${e.detail}"
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
        metadata: TrackMetadata,
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

    private fun metadataFor(audio: ResolvedAudio, context: TrackContext, processing: Processing): TrackMetadata {
        val (artist, title) = if (processing.smartTitles) {
            TitleParser.parse(audio.title, audio.channel ?: audio.uploader)
        } else {
            ParsedTitle(audio.uploader, audio.title)
        }
        return TrackMetadata(
            title = title,
            artist = artist,
            year = audio.uploadYear,
            comment = audio.videoId.watchUrl,
            album = context.album,
            albumArtist = context.albumArtist,
            trackNumber = context.trackNumber,
            trackTotal = context.trackTotal,
        )
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
        private const val COVER_SIZE = 720
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
