package io.github.rudolfhk.audiosaver.core.mp3

import io.github.rudolfhk.audiosaver.core.audio.FfmpegPcmDecoder
import io.github.rudolfhk.audiosaver.core.audio.FfmpegTools
import io.github.rudolfhk.audiosaver.core.audio.ReplayGain
import io.github.rudolfhk.audiosaver.core.audio.TimeRange
import io.github.rudolfhk.audiosaver.core.tag.CoverArt
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mp3ExporterTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun needsFfmpeg() = assumeTrue("ffmpeg not installed", FfmpegTools.available)

    private fun source(seconds: Int = 8, channels: Int = 2) = FfmpegTools.make(
        File(tmp.root, "src-$seconds-$channels.m4a"), "-f", "lavfi", "-i", "anoisesrc=color=pink:duration=$seconds:sample_rate=44100:amplitude=0.4",
        "-ac", "$channels", "-c:a", "aac", "-b:a", "160k",
    )

    private val metadata = TrackMetadata(
        title = "Grüße – Test ♪", artist = "Some Artist", year = 2021, comment = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        album = "An Album", trackNumber = 3, trackTotal = 9, replayGain = ReplayGain(loudnessLufs = -12.0, peak = 0.8),
    )

    @Test fun `mp3 at 192 kbps with the same loudness and duration`() {
        val src = source()
        val out = File(tmp.root, "out.mp3")
        val started = System.nanoTime()
        Mp3Exporter.export(src, FfmpegPcmDecoder(), out, metadata)
        val seconds = (System.nanoTime() - started) / 1e9
        println("encoded 8 s of stereo audio in %.2f s".format(seconds))
        assertEquals(listOf("mp3", "192000"), FfmpegTools.probe(out, "stream=codec_name,bit_rate"))
        assertEquals("", FfmpegTools.decodeErrors(out))
        assertTrue(abs(FfmpegTools.durationSeconds(out) - 8.0) < 0.1, "duration ${FfmpegTools.durationSeconds(out)}")
        val loudnessIn = FfmpegTools.integratedLoudness(src)
        val loudnessOut = FfmpegTools.integratedLoudness(out)
        assertTrue(abs(loudnessIn - loudnessOut) < 0.5, "loudness changed: $loudnessIn -> $loudnessOut")
        assertTrue(seconds < 30, "encoding far too slow: $seconds s")
    }

    @Test fun `id3 tags, replaygain and cover are readable`() {
        val jpg = FfmpegTools.make(File(tmp.root, "c.jpg"), "-f", "lavfi", "-i", "testsrc=size=320x320", "-frames:v", "1")
        val out = File(tmp.root, "tags.mp3")
        Mp3Exporter.export(source(seconds = 3), FfmpegPcmDecoder(), out, metadata.copy(cover = CoverArt(jpg.readBytes(), 320, 320)))
        val tags = FfmpegTools.probeTags(out, "format_tags")
        assertEquals("Grüße – Test ♪", tags["title"])
        assertEquals("Some Artist", tags["artist"])
        assertEquals("Some Artist", tags["album_artist"])
        assertEquals("An Album", tags["album"])
        assertEquals("2021", tags["date"])
        assertEquals("3/9", tags["track"])
        assertEquals("-6.00 dB", tags["replaygain_track_gain"], "$tags")
        assertEquals("0.800000", tags["replaygain_track_peak"])
        val streams = FfmpegTools.probe(out, "stream=codec_name,width")
        assertTrue(streams.containsAll(listOf("mjpeg", "320")), "$streams")
        // ID3v1 at the very end for the oldest players.
        val tail = out.readBytes().takeLast(128).toByteArray()
        assertEquals("TAG", String(tail, 0, 3, Charsets.ISO_8859_1))
        assertEquals("Gr", String(tail, 3, 2, Charsets.ISO_8859_1))
        assertEquals(3, tail[126].toInt())
    }

    @Test fun `mono and exact trimming`() {
        val out = File(tmp.root, "trim.mp3")
        Mp3Exporter.export(source(seconds = 8, channels = 1), FfmpegPcmDecoder(), out, metadata, TimeRange(1_500, 5_500))
        assertEquals(listOf("1"), FfmpegTools.probe(out, "stream=channels"))
        val d = FfmpegTools.durationSeconds(out)
        assertTrue(abs(d - 4.0) < 0.1, "duration $d")
    }
}
