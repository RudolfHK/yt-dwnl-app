package io.github.rudolfhk.audiosaver.core.engine

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
}

data class ResolvedAudio(
    val videoId: VideoId,
    val title: String,
    /** Channel name, already cleaned of YouTube's auto-generated " - Topic" suffix. */
    val uploader: String?,
    val uploadYear: Int?,
    val durationSeconds: Long?,
    val stream: AudioStreamSource,
)

data class AudioStreamSource(
    val url: String,
    val container: AudioContainer,
    val codec: String?,
    val averageBitrate: Int?,
    /** Byte size if the extractor knows it up front; null means progress must be indeterminate. */
    val contentLength: Long?,
    /** Extra headers the stream host expects (e.g. a matching User-Agent). */
    val requestHeaders: Map<String, String> = emptyMap(),
)

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
    BOT_CHECK("YouTube is asking to confirm you're not a bot", retryable = true),
    RATE_LIMITED("YouTube is rate-limiting this device", retryable = true),
    NETWORK("Network error", retryable = true),
    EXTRACTOR_BROKEN("YouTube changed something the extractor doesn't understand yet", retryable = true),
    DOWNLOAD_FAILED("The audio download failed", retryable = true),
    STORAGE("Couldn't save the file", retryable = true),
    UNKNOWN("Something went wrong", retryable = true),
}

class AudioSaveException(
    val reason: FailureReason,
    /** The underlying library/server message, passed through verbatim for diagnosis. */
    val detail: String,
    cause: Throwable? = null,
) : Exception("${reason.userTitle}: $detail", cause)
