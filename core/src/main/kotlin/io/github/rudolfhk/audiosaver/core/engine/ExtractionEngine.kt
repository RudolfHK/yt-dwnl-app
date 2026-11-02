package io.github.rudolfhk.audiosaver.core.engine

import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.VideoId

/**
 * The single seam between the app and whatever library currently knows how to talk to YouTube.
 *
 * YouTube breaks extractors on purpose and regularly, so this is the part that WILL need maintenance.
 * Everything else in the app depends only on this interface; replacing the engine means writing one new
 * implementation and changing the one line in the app's dependency wiring that constructs it.
 *
 * Implementations must:
 *  - be safe to call from any background thread,
 *  - honour coroutine cancellation (e.g. via `runInterruptible`),
 *  - throw [AudioSaveException] with the most specific [FailureReason] they can determine and the
 *    library's own message as [AudioSaveException.detail] - never a generic "failed".
 */
interface ExtractionEngine {
    /** Human-readable engine name + version, shown in error details so a breakage can be traced. */
    val description: String

    suspend fun resolve(videoId: VideoId): ResolvedAudio

    /**
     * Lists one page of a mix/album/playlist. Pass the previous page's [CollectionPage.next] to
     * continue; null starts at the beginning. Mixes are endless - callers decide when to stop.
     */
    suspend fun listCollection(ref: CollectionRef, page: PageToken? = null): CollectionPage
}

/** Engine-specific continuation, opaque to everything outside the engine. */
class PageToken(val value: Any)

data class CollectionPage(
    val ref: CollectionRef,
    /** Display name, already cleaned (e.g. YouTube's "Album - " prefix removed for albums). */
    val title: String,
    val uploader: String?,
    val items: List<CollectionItem>,
    /** Total size if YouTube says so; null for mixes (endless) or when unknown. */
    val totalCount: Long?,
    val next: PageToken?,
)

data class CollectionItem(
    val videoId: VideoId,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long?,
    /** False for "[Deleted video]" / "[Private video]" placeholders: listed, but not selectable. */
    val available: Boolean = true,
)

data class ResolvedAudio(
    val videoId: VideoId,
    val title: String,
    /** Channel name, already cleaned of YouTube's auto-generated " - Topic" suffix. */
    val uploader: String?,
    val uploadYear: Int?,
    val durationSeconds: Long?,
    val stream: AudioStreamSource,
    /**
     * Video-only streams that can be merged with [stream] into an MP4 (H.264 in MP4, directly
     * downloadable). Empty when the engine found none; only used when the user asks for the video.
     */
    val videoStreams: List<VideoStreamSource> = emptyList(),
)

data class VideoStreamSource(
    override val url: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    /** e.g. "avc1.640028" */
    val codec: String?,
    val averageBitrate: Int?,
    override val contentLength: Long?,
    override val requestHeaders: Map<String, String> = emptyMap(),
) : RemoteFile

/** Something the downloader can fetch. */
interface RemoteFile {
    val url: String

    /** Byte size if the extractor knows it up front; null means progress must be indeterminate. */
    val contentLength: Long?

    /** Extra headers the stream host expects (e.g. a matching User-Agent). */
    val requestHeaders: Map<String, String>
}

data class AudioStreamSource(
    override val url: String,
    val container: AudioContainer,
    val codec: String?,
    val averageBitrate: Int?,
    override val contentLength: Long?,
    override val requestHeaders: Map<String, String> = emptyMap(),
) : RemoteFile

enum class AudioContainer(val extension: String, val mimeType: String, val supportsTagging: Boolean) {
    /** AAC in MP4. Plays everywhere; tagged with iTunes-style atoms. */
    M4A("m4a", "audio/mp4", supportsTagging = true),

    /** Opus in WebM. Only used when no M4A stream exists; saved as-is, untagged. */
    WEBM_OPUS("webm", "audio/webm", supportsTagging = false),
}

enum class FailureReason(val userTitle: String, val retryable: Boolean) {
    AGE_RESTRICTED("This video is age-restricted", retryable = false),
    PRIVATE("This video is private", retryable = false),
    REGION_LOCKED("Not available in your country", retryable = false),
    UNAVAILABLE("Video unavailable", retryable = false),
    PAID_OR_MEMBERS_ONLY("Paid or members-only content", retryable = false),
    LIVE_STREAM("Live streams can't be downloaded", retryable = false),
    NO_AUDIO_STREAM("No downloadable audio stream found", retryable = true),
    NO_VIDEO_STREAM("No downloadable MP4 video stream found", retryable = true),
    BOT_CHECK("YouTube is asking to confirm you're not a bot", retryable = true),
    RATE_LIMITED("YouTube is rate-limiting this device", retryable = true),
    NETWORK("Network error", retryable = true),
    EXTRACTOR_BROKEN("YouTube changed something the extractor doesn't understand yet", retryable = true),
    DOWNLOAD_FAILED("The download failed", retryable = true),
    STORAGE("Couldn't save the file", retryable = true),
    UNKNOWN("Something went wrong", retryable = true),
}

class AudioSaveException(
    val reason: FailureReason,
    /** The underlying library/server message, passed through verbatim for diagnosis. */
    val detail: String,
    cause: Throwable? = null,
) : Exception("${reason.userTitle}: $detail", cause)
