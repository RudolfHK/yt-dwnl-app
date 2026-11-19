package io.github.rudolfhk.audiosaver.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/** ReplayGain 2.0 values: gain to reach -18 LUFS, and the sample peak (1.0 = full scale). */
data class ReplayGain(val loudnessLufs: Double, val peak: Double) {
    val trackGainDb: Double get() = REFERENCE_LUFS - loudnessLufs

    /** Opus' R128_TRACK_GAIN: Q7.8 fixed point, relative to -23 LUFS (RFC 7845). */
    val r128TrackGain: Int get() = Math.round((R128_REFERENCE_LUFS - loudnessLufs) * 256).toInt().coerceIn(-32768, 32767)

    val gainTag: String get() = String.format(java.util.Locale.ROOT, "%.2f dB", trackGainDb)
    val peakTag: String get() = String.format(java.util.Locale.ROOT, "%.6f", peak)

    companion object {
        const val REFERENCE_LUFS = -18.0
        const val R128_REFERENCE_LUFS = -23.0
    }
}

/**
 * One decoding pass that measures what the post-processing needs, in 100 ms segments:
 * K-weighted energy (ITU-R BS.1770-4 / EBU R128 loudness, the basis of ReplayGain 2.0) and the
 * sample peak. Loudness for any part of the track can then be computed without decoding again,
 * which matters because silence trimming decides that part only after the pass.
 */
class AudioAnalyzer : PcmSink {
    var sampleRate = 0
        private set
    var channels = 0
        private set
    var totalFrames = 0L
        private set

    private lateinit var filters: Array<KWeighting>
    private var segmentFrames = 0
    private var framesInSegment = 0
    private var energyInSegment = 0.0
    private var peakInSegment = 0f
    private val energies = ArrayList<Double>() // mean square per segment, summed over channels
    private val peaks = ArrayList<Float>()

    override fun start(sampleRate: Int, channels: Int) {
        require(sampleRate > 0 && channels > 0) { "bad PCM format $sampleRate Hz x $channels" }
        this.sampleRate = sampleRate
        this.channels = channels
        filters = Array(channels) { KWeighting(sampleRate.toDouble()) }
        segmentFrames = sampleRate / 10
    }

    override fun write(samples: ShortArray, count: Int) {
        var i = 0
        while (i + channels <= count) {
            var frameEnergy = 0.0
            for (c in 0 until channels) {
                val s = samples[i + c] / 32768.0
                val a = abs(s).toFloat()
                if (a > peakInSegment) peakInSegment = a
                // BS.1770 channel weights: 1.0 for front channels; surround (c >= 3) would be 1.41,
                // the LFE (c == 3 in 5.1) is excluded. YouTube audio is mono or stereo.
                if (c < 3) {
                    val k = filters[c].process(s)
                    frameEnergy += k * k
                }
            }
            energyInSegment += frameEnergy
            framesInSegment++
            totalFrames++
            if (framesInSegment == segmentFrames) closeSegment()
            i += channels
        }
    }

    private fun closeSegment() {
        energies += energyInSegment / framesInSegment
        peaks += peakInSegment
        energyInSegment = 0.0
        peakInSegment = 0f
        framesInSegment = 0
    }

    val durationMs: Long get() = if (sampleRate == 0) 0 else totalFrames * 1000 / sampleRate

    /**
     * Integrated loudness (LUFS) and sample peak of [range], gated as EBU R128 specifies:
     * 400 ms blocks every 100 ms, absolute gate -70 LUFS, relative gate 10 LU below the
     * ungated mean. Null when the range holds no audible audio at all.
     */
    fun replayGain(range: TimeRange = TimeRange.ALL): ReplayGain? {
        val first = (range.startMs / 100).toInt().coerceIn(0, energies.size)
        val last = ((range.endMs ?: Long.MAX_VALUE) / 100).coerceAtMost(energies.size.toLong()).toInt()
        if (last - first < 4) return null
        val blocks = (first..last - 4).map { k -> (energies[k] + energies[k + 1] + energies[k + 2] + energies[k + 3]) / 4 }
        fun lufs(z: Double) = -0.691 + 10 * log10(z)
        val absolute = blocks.filter { it > 0 && lufs(it) > -70.0 }
        if (absolute.isEmpty()) return null
        val relativeGate = lufs(absolute.average()) - 10.0
        val gated = absolute.filter { lufs(it) > relativeGate }
        val peak = peaks.subList(first, last).maxOrNull()?.toDouble() ?: 0.0
        return ReplayGain(lufs(gated.average()), peak)
    }

    /**
     * Where the audible part starts and ends: leading/trailing 100 ms segments whose peak stays
     * below [thresholdDb] are silence. A [marginMs] of silence is kept so fades aren't cut hard.
     * Returns the whole track when there's nothing to trim (or nothing audible at all).
     */
    fun audibleRange(thresholdDb: Double = -50.0, marginMs: Long = 150): TimeRange {
        val threshold = 10.0.pow(thresholdDb / 20).toFloat()
        val first = peaks.indexOfFirst { it >= threshold }
        val last = peaks.indexOfLast { it >= threshold }
        if (first < 0) return TimeRange.ALL
        val start = (first * 100L - marginMs).coerceAtLeast(0)
        val endSegment = (last + 1) * 100L + marginMs
        val end = endSegment.takeIf { it < durationMs - 50 }
        return TimeRange(start, end)
    }

    /** The two K-weighting biquads of BS.1770 (high shelf + high pass), coefficients for any sample rate. */
    private class KWeighting(fs: Double) {
        private val b1: DoubleArray
        private val a1: DoubleArray
        private val b2: DoubleArray
        private val a2: DoubleArray
        private val z1 = DoubleArray(2)
        private val z2 = DoubleArray(2)

        init {
            // Pre-filter (head shelf), same derivation as libebur128.
            var f0 = 1681.974450955533
            val g = 3.999843853973347
            var q = 0.7071752369554196
            var k = tan(PI * f0 / fs)
            val vh = 10.0.pow(g / 20)
            val vb = vh.pow(0.4996667741545416)
            var a0 = 1 + k / q + k * k
            b1 = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0)
            a1 = doubleArrayOf(2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            // RLB high-pass.
            f0 = 38.13547087602444
            q = 0.5003270373238773
            k = tan(PI * f0 / fs)
            a0 = 1 + k / q + k * k
            b2 = doubleArrayOf(1.0, -2.0, 1.0)
            a2 = doubleArrayOf(2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }

        fun process(x: Double): Double = biquad(biquad(x, b1, a1, z1), b2, a2, z2)

        /** Transposed direct form II. */
        private fun biquad(x: Double, b: DoubleArray, a: DoubleArray, z: DoubleArray): Double {
            val y = b[0] * x + z[0]
            z[0] = b[1] * x - a[0] * y + z[1]
            z[1] = b[2] * x - a[1] * y
            return y
        }
    }
}
