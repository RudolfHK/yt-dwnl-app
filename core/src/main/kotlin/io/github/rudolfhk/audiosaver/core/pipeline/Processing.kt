package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.audio.PcmDecoder
import io.github.rudolfhk.audiosaver.core.audio.TimeRange
import io.github.rudolfhk.audiosaver.core.tag.CoverArt
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata

/** The audio file the user wants. */
enum class AudioFormat(val label: String) {
    /** YouTube's AAC as-is: plays everywhere, no re-encoding. */
    M4A("M4A (AAC)"),

    /** YouTube's Opus as-is (≈160 kbps), remuxed into a standard .opus file: better quality, newer players. */
    OPUS("Opus"),

    /** Re-encoded on the phone for old players: slower, slightly lower quality. */
    MP3("MP3"),
}

/** Everything that happens to the audio after download. Defaults = the app's defaults. */
data class Processing(
    val audioFormat: AudioFormat = AudioFormat.M4A,
    val coverArt: Boolean = true,
    val smartTitles: Boolean = true,
    val replayGain: Boolean = false,
    val trimSilence: Boolean = false,
    /** Manual cut; single downloads only. */
    val trim: TimeRange = TimeRange.ALL,
    /** Ask the user to confirm/edit the tags; single downloads only. */
    val reviewTags: Boolean = false,
) {
    /** These need the audio decoded once more on the phone. */
    val needsAnalysis: Boolean get() = replayGain || trimSilence
}

/** Turns a downloaded thumbnail into a square JPEG cover (Android: Bitmap; tests: ImageIO). */
fun interface ImageCropper {
    /** Null when the image can't be decoded. */
    fun cropSquare(image: ByteArray, maxSize: Int): CoverArt?
}

/** What the user is asked to confirm when "edit tags before saving" is on. */
data class TagProposal(val videoTitle: String, val metadata: TrackMetadata)

/** Asks the user to confirm or correct the tags; null = they cancelled the download. */
fun interface TagReviewer {
    suspend fun review(proposal: TagProposal): TrackMetadata?
}

/**
 * Platform pieces the pipeline can use. Each is optional: without a decoder, MP3 export,
 * loudness and silence trimming are skipped with a warning; without a cropper, no cover.
 */
class Toolkit(
    val decoder: PcmDecoder? = null,
    val cropper: ImageCropper? = null,
    val reviewer: TagReviewer? = null,
)

/** Slow steps after the download, so the UI can say what's happening. */
enum class ProcessingStep { WAITING_FOR_TAGS, ANALYZING, ENCODING_MP3, REMUXING_OPUS }
