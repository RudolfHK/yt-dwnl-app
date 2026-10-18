package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.tag.Mp4Tagger
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where finished files go. On Android this is MediaStore; in tests a plain directory. */
interface AudioSink {
    /**
     * Copies [file] into permanent storage and returns what actually landed there, read back from
     * the storage itself (not just echoed from the request).
     */
    suspend fun save(file: File, displayName: String, mimeType: String, metadata: TrackMetadata): SavedAudio
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

    /** [totalBytes] == null means size unknown: show an indeterminate bar, never a guessed percentage. */
    data class Downloading(val videoId: VideoId, val title: String, val bytesDownloaded: Long, val totalBytes: Long?) : DownloadState {
        val fraction: Float? get() = totalBytes?.let { (bytesDownloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }

    data class Saving(val videoId: VideoId, val title: String) : DownloadState
    data class Done(val videoId: VideoId, val saved: SavedAudio, val taggingWarning: String?) : DownloadState
    data class Failed(val videoId: VideoId, val reason: FailureReason, val detail: String) : DownloadState
    data class Cancelled(val videoId: VideoId) : DownloadState

    val isActive: Boolean get() = this is Resolving || this is Downloading || this is Saving
}

/**
 * The whole "link in, audio file out" job: resolve -> download to a temp file -> tag -> save -> verify.
 * Phases are reported through [onState]; any failure ends in [DownloadState.Failed] with the most
 * specific reason available, cancellation in [DownloadState.Cancelled].
 */
class AudioDownloadPipeline(
    private val engine: ExtractionEngine,
    private val downloader: HttpAudioDownloader,
    private val sink: AudioSink,
    private val workDir: File,
) {

    suspend fun run(videoId: VideoId, onState: (DownloadState) -> Unit) {
        val raw = File(workDir, "$videoId.part")
        val tagged = File(workDir, "$videoId.tagged")
        try {
            onState(DownloadState.Resolving(videoId))
            val audio = engine.resolve(videoId)

            val progress = ThrottledProgress { done, total ->
                onState(DownloadState.Downloading(videoId, audio.title, done, total))
            }
            workDir.mkdirs()
            downloader.download(audio.stream, raw, progress::report)
            progress.flush()

            onState(DownloadState.Saving(videoId, audio.title))
            val metadata = metadataFor(audio)
            val (fileToSave, taggingWarning) = withContext(Dispatchers.IO) { tagIfPossible(audio, raw, tagged, metadata) }
            val fileName = FileNames.forTrack(metadata.artist, metadata.title, audio.stream.container.extension)
            val saved = try {
                sink.save(fileToSave, fileName, audio.stream.container.mimeType, metadata)
            } catch (e: IOException) {
                throw AudioSaveException(FailureReason.STORAGE, e.message ?: e::class.java.simpleName, e)
            } catch (e: SecurityException) {
                throw AudioSaveException(FailureReason.STORAGE, e.message ?: "Permission denied", e)
            }
            verify(saved, expectedSize = fileToSave.length())
            onState(DownloadState.Done(videoId, saved, taggingWarning))
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
            raw.delete()
            tagged.delete()
        }
    }

    private fun metadataFor(audio: ResolvedAudio) = TrackMetadata(
        title = audio.title,
        artist = audio.uploader,
        year = audio.uploadYear,
        comment = audio.videoId.watchUrl,
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

internal object FileNames {
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private const val MAX_BASE_LENGTH = 120

    fun forTrack(artist: String?, title: String, extension: String): String {
        val base = listOfNotNull(artist?.takeIf { it.isNotBlank() && !title.contains(it, ignoreCase = true) }, title)
            .joinToString(" - ")
        val clean = ILLEGAL.replace(base, "_").replace(Regex("\\s+"), " ").trim().trim('.').ifEmpty { "audio" }
        return clean.take(MAX_BASE_LENGTH).trimEnd() + "." + extension
    }
}
