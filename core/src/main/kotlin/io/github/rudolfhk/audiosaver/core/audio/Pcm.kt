package io.github.rudolfhk.audiosaver.core.audio

import java.io.File
import java.io.IOException

/** Receives decoded audio: interleaved 16-bit samples. */
interface PcmSink {
    fun start(sampleRate: Int, channels: Int)

    /** [samples] holds [count] interleaved values (count = frames x channels). */
    fun write(samples: ShortArray, count: Int)
}

/**
 * Decodes an audio file (M4A/AAC or Opus) to PCM. Android implements this with MediaCodec;
 * the core only needs it for MP3 export, loudness measurement and silence detection.
 */
fun interface PcmDecoder {
    @Throws(IOException::class)
    fun decode(file: File, sink: PcmSink)
}

/** Fans one decoding pass out to several sinks. */
class TeeSink(private val sinks: List<PcmSink>) : PcmSink {
    override fun start(sampleRate: Int, channels: Int) = sinks.forEach { it.start(sampleRate, channels) }
    override fun write(samples: ShortArray, count: Int) = sinks.forEach { it.write(samples, count) }
}

/** A part of a track, in milliseconds. [endMs] null = to the end. */
data class TimeRange(val startMs: Long = 0, val endMs: Long? = null) {
    init {
        require(startMs >= 0) { "start must not be negative" }
        require(endMs == null || endMs > startMs) { "end must be after start" }
    }

    val isWholeTrack: Boolean get() = startMs == 0L && endMs == null

    /** The overlap of two ranges, or null if they don't overlap. */
    fun intersect(other: TimeRange): TimeRange? {
        val start = maxOf(startMs, other.startMs)
        val end = listOfNotNull(endMs, other.endMs).minOrNull()
        return if (end != null && end <= start) null else TimeRange(start, end)
    }

    companion object {
        val ALL = TimeRange()
    }
}

/** Passes on only the frames inside [range] - an exact, sample-accurate cut for re-encoded output. */
class TrimmingSink(private val range: TimeRange, private val delegate: PcmSink) : PcmSink {
    private var channels = 1
    private var firstFrame = 0L
    private var endFrame = Long.MAX_VALUE
    private var frame = 0L

    override fun start(sampleRate: Int, channels: Int) {
        this.channels = channels
        firstFrame = range.startMs * sampleRate / 1000
        endFrame = range.endMs?.let { it * sampleRate / 1000 } ?: Long.MAX_VALUE
        delegate.start(sampleRate, channels)
    }

    override fun write(samples: ShortArray, count: Int) {
        val frames = count / channels
        val from = (firstFrame - frame).coerceIn(0, frames.toLong()).toInt()
        val to = (endFrame - frame).coerceIn(0, frames.toLong()).toInt()
        frame += frames
        if (to <= from) return
        if (from == 0 && to == frames) {
            delegate.write(samples, count)
        } else {
            delegate.write(samples.copyOfRange(from * channels, to * channels), (to - from) * channels)
        }
    }
}
