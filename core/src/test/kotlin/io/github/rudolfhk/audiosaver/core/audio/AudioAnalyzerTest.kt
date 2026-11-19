package io.github.rudolfhk.audiosaver.core.audio

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioAnalyzerTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun needsFfmpeg() = assumeTrue("ffmpeg not installed", FfmpegTools.available)

    private fun analyze(file: File) = AudioAnalyzer().also { FfmpegPcmDecoder().decode(file, it) }

    private fun assertMatchesFfmpeg(file: File) {
        val ours = assertNotNull(analyze(file).replayGain()).loudnessLufs
        val reference = FfmpegTools.integratedLoudness(file)
        assertTrue(abs(ours - reference) < 0.3, "ours $ours LUFS vs ffmpeg ebur128 $reference LUFS for ${file.name}")
    }

    @Test fun `loudness matches ffmpeg's ebur128 for a quiet tone, loud noise, mono and 44_1 kHz`() {
        assertMatchesFfmpeg(FfmpegTools.make(File(tmp.root, "tone.m4a"), "-f", "lavfi", "-i", "sine=frequency=1000:duration=8:sample_rate=48000", "-af", "volume=-20dB", "-ac", "2", "-c:a", "aac", "-b:a", "128k"))
        assertMatchesFfmpeg(FfmpegTools.make(File(tmp.root, "noise.m4a"), "-f", "lavfi", "-i", "anoisesrc=color=pink:duration=8:sample_rate=44100:amplitude=0.8", "-ac", "2", "-c:a", "aac", "-b:a", "160k"))
        assertMatchesFfmpeg(FfmpegTools.make(File(tmp.root, "mono.wav"), "-f", "lavfi", "-i", "sine=frequency=200:duration=6:sample_rate=22050"))
        // Music-like: loud and quiet passages, so the relative gate matters.
        assertMatchesFfmpeg(
            FfmpegTools.make(
                File(tmp.root, "dynamic.opus"),
                "-f", "lavfi", "-i", "anoisesrc=color=brown:duration=10:sample_rate=48000",
                "-af", "volume='if(lt(mod(t,4),2),1,0.05)':eval=frame", "-ac", "2", "-c:a", "libopus",
            ),
        )
    }

    @Test fun `replaygain is relative to -18 LUFS, R128 to -23 in Q7_8`() {
        val rg = ReplayGain(loudnessLufs = -10.5, peak = 0.98)
        assertEquals(-7.5, rg.trackGainDb, 1e-9)
        assertEquals("-7.50 dB", rg.gainTag)
        assertEquals("0.980000", rg.peakTag)
        assertEquals((-12.5 * 256).toInt(), rg.r128TrackGain)
    }

    @Test fun `peak is the loudest sample`() {
        // aevalsrc gives an exact amplitude (ffmpeg's "sine" source plays at 1/8 full scale).
        val file = FfmpegTools.make(File(tmp.root, "half.wav"), "-f", "lavfi", "-i", "aevalsrc=0.5*sin(2*PI*1000*t):s=48000:d=2")
        val peak = analyze(file).replayGain()!!.peak
        assertTrue(abs(peak - 0.5) < 0.01, "peak $peak")
    }

    @Test fun `leading and trailing silence are found, with a margin`() {
        val file = FfmpegTools.make(
            File(tmp.root, "padded.wav"),
            "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo:d=2",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=3:sample_rate=48000",
            "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo:d=1.5",
            "-filter_complex", "[1]aformat=channel_layouts=stereo[s];[0][s][2]concat=n=3:v=0:a=1",
        )
        val analyzer = analyze(file)
        assertEquals(6500, analyzer.durationMs, "duration")
        val range = analyzer.audibleRange()
        assertTrue(range.startMs in 1800..2000, "start ${range.startMs}")
        assertTrue(range.endMs!! in 5000..5200, "end ${range.endMs}")
        // Loudness of just the tone equals the tone's loudness, not diluted by the silence.
        val toneOnly = analyzer.replayGain(range)!!.loudnessLufs
        val whole = analyzer.replayGain()!!.loudnessLufs
        assertTrue(abs(toneOnly - whole) < 0.5, "gating already ignores silence: $toneOnly vs $whole")
    }

    @Test fun `nothing to trim and silent files`() {
        val tone = analyze(FfmpegTools.make(File(tmp.root, "tone.wav"), "-f", "lavfi", "-i", "sine=duration=3"))
        assertTrue(tone.audibleRange().isWholeTrack, "${tone.audibleRange()}")
        val silent = analyze(FfmpegTools.make(File(tmp.root, "silent.wav"), "-f", "lavfi", "-i", "anullsrc=d=3"))
        assertTrue(silent.audibleRange().isWholeTrack)
        assertNull(silent.replayGain(), "no loudness for silence")
    }

    @Test fun `time ranges`() {
        assertEquals(TimeRange(2000, 5000), TimeRange(1000, 5000).intersect(TimeRange(2000, null)))
        assertNull(TimeRange(0, 1000).intersect(TimeRange(2000, null)))
        assertTrue(TimeRange.ALL.isWholeTrack)
    }
}
