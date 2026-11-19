package io.github.rudolfhk.audiosaver.core.opus

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebmOpusRemuxerTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun needsFfmpeg() = assumeTrue("ffmpeg not installed", FfmpegTools.available)

    /** Like YouTube's itag 251: Opus in WebM, 48 kHz stereo. */
    private fun webm(name: String = "in.webm", seconds: Int = 6) = FfmpegTools.make(
        File(tmp.root, name), "-f", "lavfi", "-i", "anoisesrc=color=pink:duration=$seconds:sample_rate=48000:amplitude=0.3",
        "-ac", "2", "-c:a", "libopus", "-b:a", "128k", "-f", "webm",
    )

    private fun cover(size: Int = 400): CoverArt {
        val jpg = FfmpegTools.make(File(tmp.root, "cover$size.jpg"), "-f", "lavfi", "-i", "testsrc=size=${size}x$size", "-frames:v", "1", "-q:v", "2")
        return CoverArt(jpg.readBytes(), size, size)
    }

    private val metadata = TrackMetadata(
        title = "Grüße – Test ♪", artist = "Some Artist", year = 2021, comment = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        album = "An Album", trackNumber = 3, trackTotal = 9, replayGain = ReplayGain(loudnessLufs = -9.0, peak = 0.9),
    )

    @Test fun `remuxed audio decodes to exactly the same samples`() {
        val input = webm()
        val out = File(tmp.root, "out.opus")
        WebmOpusRemuxer.remux(input, out, metadata)
        assertEquals(listOf("opus"), FfmpegTools.probe(out, "stream=codec_name"))
        assertEquals("ogg", FfmpegTools.probe(out, "format=format_name").single())
        assertEquals("", FfmpegTools.decodeErrors(out), "CRC or framing errors")
        assertEquals(FfmpegTools.decodedMd5(input), FfmpegTools.decodedMd5(out), "decoded audio differs")
        assertTrue(abs(FfmpegTools.decodedSeconds(out) - 6.0) < 0.002, "decoded ${FfmpegTools.decodedSeconds(out)}")
    }

    @Test fun `tags, R128 gain and cover art are readable`() {
        val out = File(tmp.root, "tagged.opus")
        WebmOpusRemuxer.remux(webm(), out, metadata.copy(cover = cover()))
        val tags = FfmpegTools.probeTags(out, "stream_tags")
        assertEquals("Grüße – Test ♪", tags["title"])
        assertEquals("Some Artist", tags["artist"])
        assertEquals("Some Artist", tags["album_artist"], "$tags")
        assertEquals("An Album", tags["album"])
        assertEquals("2021", tags["date"])
        assertEquals("3", tags["track"], "$tags")
        assertEquals("${(-14.0 * 256).toInt()}", tags["r128_track_gain"], "R128 gain is relative to -23 LUFS: -23 - (-9) = -14 dB")
        // ffmpeg exposes METADATA_BLOCK_PICTURE as an attached picture stream.
        val streams = FfmpegTools.probe(out, "stream=codec_name,width,height")
        assertTrue(streams.containsAll(listOf("mjpeg", "400")), "cover not found: $streams")
    }

    @Test fun `a cover too big for one ogg page spans several`() {
        val out = File(tmp.root, "bigcover.opus")
        WebmOpusRemuxer.remux(webm(), out, metadata.copy(cover = cover(1400)))
        assertTrue(out.length() > 100_000)
        assertEquals("", FfmpegTools.decodeErrors(out))
        assertTrue(FfmpegTools.probe(out, "stream=codec_name").contains("mjpeg"))
        assertEquals(FfmpegTools.decodedMd5(File(tmp.root, "in.webm")), FfmpegTools.decodedMd5(out, "a:0"))
    }

    @Test fun `trimming cuts at packet boundaries`() {
        val out = File(tmp.root, "trim.opus")
        WebmOpusRemuxer.remux(webm(seconds = 10), out, metadata, TimeRange(2_000, 7_500))
        assertEquals("", FfmpegTools.decodeErrors(out))
        // The container duration includes the 80 ms decoder pre-roll kept on purpose (RFC 7845
        // section 4.6); what a player outputs is the decoded audio with the pre-skip removed.
        val d = FfmpegTools.decodedSeconds(out)
        assertTrue(abs(d - 5.5) < 0.03, "decoded duration $d, expected 5.5 (container says ${FfmpegTools.durationSeconds(out)})")
        val endOnly = File(tmp.root, "end.opus")
        WebmOpusRemuxer.remux(webm("in2.webm", seconds = 10), endOnly, null, TimeRange(0, 3_000))
        assertTrue(abs(FfmpegTools.decodedSeconds(endOnly) - 3.0) < 0.03, "decoded ${FfmpegTools.decodedSeconds(endOnly)}")
    }

    @Test fun `packet durations come from the TOC byte`() {
        assertEquals(960, WebmOpusRemuxer.packetSamples(byteArrayOf((31 shl 3).toByte()))) // CELT 20 ms, 1 frame
        assertEquals(1920, WebmOpusRemuxer.packetSamples(byteArrayOf(((31 shl 3) or 1).toByte()))) // 2 frames
        assertEquals(2880, WebmOpusRemuxer.packetSamples(byteArrayOf((3 shl 3).toByte()))) // SILK 60 ms
        assertEquals(3 * 120L, WebmOpusRemuxer.packetSamples(byteArrayOf(((16 shl 3) or 3).toByte(), 3))) // 3 x 2.5 ms
    }

    @Test fun `non-webm input is rejected cleanly`() {
        val junk = tmp.newFile("junk.webm").apply { writeText("<html>403</html>") }
        assertFailsWith<OpusRemuxException> { WebmOpusRemuxer.remux(junk, File(tmp.root, "x.opus"), null) }
        val aac = FfmpegTools.make(File(tmp.root, "aac.mkv"), "-f", "lavfi", "-i", "sine=duration=1", "-c:a", "aac")
        assertFailsWith<OpusRemuxException> { WebmOpusRemuxer.remux(aac, File(tmp.root, "y.opus"), null) }
    }
}
