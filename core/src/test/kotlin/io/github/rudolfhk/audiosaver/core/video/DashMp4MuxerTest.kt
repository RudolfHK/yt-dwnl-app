package io.github.rudolfhk.audiosaver.core.video

import io.github.rudolfhk.audiosaver.core.tag.Mp4Tagger
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Merges real ffmpeg-made DASH streams and checks the result with ffprobe/ffmpeg as independent
 * readers: two streams, the right duration, no decode errors, and decoded audio *and* video
 * bit-identical to the inputs. Skipped when ffmpeg isn't installed.
 */
class DashMp4MuxerTest {

    @get:Rule val tmp = TemporaryFolder()

    companion object {
        private var ffmpeg = false

        @BeforeClass @JvmStatic fun detect() {
            ffmpeg = runCatching { run("ffmpeg", "-version").first == 0 && run("ffprobe", "-version").first == 0 }.getOrDefault(false)
        }

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)
            return p.exitValue() to out
        }
    }

    private fun make(name: String, vararg args: String): File {
        assumeTrue("ffmpeg/ffprobe not installed", ffmpeg)
        val f = File(tmp.root, name)
        val (code, log) = run("ffmpeg", "-v", "error", "-y", *args, f.absolutePath)
        assertEquals(0, code, log)
        return f
    }

    /** Like YouTube's DASH streams: ftyp 'dash', sidx, fragments relative to their moof. */
    private fun dashVideo(seconds: Int = 6, rate: Int = 25) = make(
        "v$seconds-$rate.mp4", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=$rate", "-t", "$seconds",
        "-c:v", "libx264", "-g", "$rate", "-pix_fmt", "yuv420p", "-an",
        "-f", "mp4", "-movflags", "+dash+global_sidx", "-frag_duration", "1000000", "-brand", "dash",
    )

    private fun dashAudio(seconds: Int = 6) = make(
        "a$seconds.m4a", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds", "-c:a", "aac", "-b:a", "64k",
        "-f", "mp4", "-movflags", "+dash+global_sidx", "-frag_duration", "1000000", "-brand", "dash",
    )

    private fun md5(file: File, stream: String): String {
        val (code, out) = run("ffmpeg", "-v", "error", "-i", file.absolutePath, "-map", "0:$stream", "-f", "md5", "-")
        assertEquals(0, code, out)
        return out.trim()
    }

    private fun probe(file: File, entries: String): List<String> =
        run("ffprobe", "-v", "error", "-show_entries", entries, "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath)
            .second.lines().map(String::trim).filter(String::isNotEmpty)

    private fun decodeErrors(file: File) = run("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "null", "-").second.trim()

    private fun assertMerged(video: File, audio: File, expectedSeconds: Double): File {
        val out = File(tmp.root, "out-${video.nameWithoutExtension}-${audio.nameWithoutExtension}.mp4")
        DashMp4Muxer.mux(video, audio, out)
        assertEquals(listOf("h264", "aac"), probe(out, "stream=codec_name"))
        val duration = probe(out, "format=duration").single().toDouble()
        assertTrue(abs(duration - expectedSeconds) < 0.25, "duration $duration, expected ~$expectedSeconds")
        assertEquals("", decodeErrors(out))
        assertEquals(md5(video, "v"), md5(out, "v"), "video changed")
        assertEquals(md5(audio, "a"), md5(out, "a"), "audio changed")
        return out
    }

    @Test fun `youtube style dash video and audio`() {
        assertMerged(dashVideo(), dashAudio(), 6.0)
    }

    @Test fun `fragments with absolute base offsets, a different frame rate and timescale`() {
        val video = make(
            "vabs.mp4", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-t", "6", "-c:v", "libx264", "-g", "30",
            "-pix_fmt", "yuv420p", "-an", "-f", "mp4", "-movflags", "+frag_keyframe+empty_moov", "-video_track_timescale", "90000",
        )
        val audio = make(
            "aabs.m4a", "-f", "lavfi", "-i", "sine=frequency=330:duration=6:sample_rate=48000", "-c:a", "aac",
            "-f", "mp4", "-movflags", "+frag_keyframe+empty_moov", "-frag_duration", "500000",
        )
        assertMerged(video, audio, 6.0)
    }

    @Test fun `audio longer than video sets the movie duration`() {
        assertMerged(dashVideo(seconds = 4), dashAudio(seconds = 6), 6.0)
    }

    // ---- structural checks: what a player reading the file front to back actually sees.
    // (ffmpeg itself re-sorts packets and recomputes durations, so it can't check these.)

    private class Layout(val trackOrder: List<Long>, val movieSeconds: Double, val mehdSeconds: Double, val trackSeconds: List<Double>)

    private fun layout(file: File): Layout {
        val b = file.readBytes()
        fun u32(at: Int) = java.nio.ByteBuffer.wrap(b, at, 4).int.toUInt().toLong()
        fun u64(at: Int) = java.nio.ByteBuffer.wrap(b, at, 8).long
        fun boxes(start: Int, end: Int): List<Triple<String, Int, Int>> { // type, offset, size
            val list = mutableListOf<Triple<String, Int, Int>>()
            var p = start
            while (p + 8 <= end) {
                val size = u32(p).toInt()
                list += Triple(String(b, p + 4, 4, Charsets.ISO_8859_1), p, size)
                p += size
            }
            return list
        }
        fun child(parent: Triple<String, Int, Int>, type: String) = boxes(parent.second + 8, parent.second + parent.third).first { it.first == type }
        val top = boxes(0, b.size)
        val moov = top.first { it.first == "moov" }
        val mvhd = child(moov, "mvhd")
        val movieTimescale = u32(mvhd.second + 8 + 12).toDouble()
        val mehd = child(child(moov, "mvex"), "mehd")
        val trackSeconds = boxes(moov.second + 8, moov.second + moov.third).filter { it.first == "trak" }.map { trak ->
            val tkhd = child(trak, "tkhd")
            u32(tkhd.second + 8 + 20) / movieTimescale
        }
        val order = top.filter { it.first == "moof" }.map { moof -> u32(child(child(moof, "traf"), "tfhd").second + 8 + 4) }
        return Layout(order, u32(mvhd.second + 8 + 16) / movieTimescale, u64(mehd.second + 8 + 4) / movieTimescale, trackSeconds)
    }

    @Test fun `fragments are interleaved by time, not one track after the other`() {
        val out = assertMerged(dashVideo(), dashAudio(), 6.0)
        val order = layout(out).trackOrder
        assertEquals(setOf(1L, 2L), order.toSet())
        // Both tracks have ~1 s fragments, so a front-to-back reader must see them alternate.
        val switches = order.zipWithNext().count { (a, b) -> a != b }
        assertTrue(switches >= order.size / 2, "track order in file: $order")
    }

    @Test fun `durations are written where galleries read them`() {
        val l = layout(assertMerged(dashVideo(seconds = 4), dashAudio(seconds = 6), 6.0))
        assertTrue(abs(l.movieSeconds - 6.0) < 0.1, "mvhd ${l.movieSeconds}")
        assertTrue(abs(l.mehdSeconds - 6.0) < 0.1, "mehd ${l.mehdSeconds}")
        assertEquals(2, l.trackSeconds.size)
        assertTrue(abs(l.trackSeconds[0] - 4.0) < 0.1 && abs(l.trackSeconds[1] - 6.0) < 0.1, "tkhd ${l.trackSeconds}")
    }

    @Test fun `the merged file can be tagged like the audio`() {
        val merged = assertMerged(dashVideo(), dashAudio(), 6.0)
        val tagged = File(tmp.root, "tagged.mp4")
        Mp4Tagger.tag(merged, tagged, TrackMetadata("Big Buck Bunny", "Blender", 2008, "https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertEquals(listOf("Big Buck Bunny", "Blender"), probe(tagged, "format_tags=title,artist"))
        assertEquals(md5(merged, "v"), md5(tagged, "v"))
        assertEquals(md5(merged, "a"), md5(tagged, "a"))
        assertEquals("", decodeErrors(tagged))
    }

    @Test fun `swapped inputs, non-fragmented input and junk are rejected with a reason`() {
        val video = dashVideo()
        val audio = dashAudio()
        val swapped = assertFailsWith<MuxException> { DashMp4Muxer.mux(audio, video, File(tmp.root, "x.mp4")) }
        assertTrue(swapped.message!!.contains("'soun', not 'vide'"), swapped.message)

        val plain = make("plain.mp4", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-t", "1", "-c:v", "libx264", "-an", "-f", "mp4")
        val notFragmented = assertFailsWith<MuxException> { DashMp4Muxer.mux(plain, audio, File(tmp.root, "y.mp4")) }
        assertTrue(notFragmented.message!!.contains("not a fragmented MP4"), notFragmented.message)

        val junk = tmp.newFile("junk.mp4").apply { writeText("<html>403</html>") }
        assertFailsWith<MuxException> { DashMp4Muxer.mux(junk, audio, File(tmp.root, "z.mp4")) }
        val truncated = File(tmp.root, "trunc.mp4").apply { writeBytes(video.readBytes().copyOf(video.length().toInt() / 2)) }
        assertFailsWith<MuxException> { DashMp4Muxer.mux(truncated, audio, File(tmp.root, "w.mp4")) }
    }
}
