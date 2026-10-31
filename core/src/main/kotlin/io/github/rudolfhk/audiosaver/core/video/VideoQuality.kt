package io.github.rudolfhk.audiosaver.core.video

import io.github.rudolfhk.audiosaver.core.engine.VideoStreamSource

/**
 * The quality the user asked for, as a ceiling. "Best" stops at 1080p because that's the highest
 * H.264 YouTube serves; above it there's only VP9/AV1, which older phones and TVs can't play.
 */
enum class VideoQuality(val maxHeight: Int, val label: String) {
    BEST(1080, "Best (up to 1080p)"),
    P720(720, "720p"),
    P480(480, "480p"),
    P360(360, "360p"),
    ;

    /**
     * Picks the H.264 stream to merge: the tallest at or below the ceiling, then higher fps, then
     * bitrate. If every stream is taller than the ceiling, the smallest one - a video slightly
     * bigger than asked beats no video. Null only when there is no H.264 stream at all.
     */
    fun pick(streams: List<VideoStreamSource>): VideoStreamSource? {
        val h264 = streams.filter { it.codec?.startsWith("avc1") != false }
        val fitting = h264.filter { it.height <= maxHeight }
        return fitting.maxWithOrNull(compareBy<VideoStreamSource> { it.height }.thenBy { it.fps }.thenBy { it.averageBitrate ?: 0 })
            ?: h264.minWithOrNull(compareBy<VideoStreamSource> { it.height }.thenByDescending { it.fps })
    }

    companion object {
        fun fromName(name: String?): VideoQuality = entries.firstOrNull { it.name == name } ?: BEST
    }
}
