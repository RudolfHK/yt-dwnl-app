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
 * bit-identical to the inputs (frame order included, so B-frame timing must be right). Skipped when
 * ffmpeg isn't installed. What Android itself makes of the file is checked by VideoOnDeviceTest.
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
    // (ffmpeg itself recomputes durations and copes with odd layouts, so it can't check these.)

    private class Layout(
        val topLevel: List<String>,
        val movieSeconds: Double,
        val trackSeconds: List<Double>,
        /** Chunk offsets per track (from stco/co64). */
        val chunks: List<List<Long>>,
        val hasSyncTable: List<Boolean>,
        val hasCompositionTable: List<Boolean>,
    )

    private fun layout(file: File): Layout {
        val b = file.readBytes()
        fun u32(at: Int) = java.nio.ByteBuffer.wrap(b, at, 4).int.toUInt().toLong()
        fun u64(at: Int) = java.nio.ByteBuffer.wrap(b, at, 8).long
        fun boxes(start: Int, end: Int): List<Triple<String, Int, Int>> { // type, offset, size
            val list = mutableListOf<Triple<String, Int, Int>>()
            var p = start
            while (p + 8 <= end) {
                val size = u32(p).let { if (it == 1L) u64(p + 8) else it }.toInt()
                list += Triple(String(b, p + 4, 4, Charsets.ISO_8859_1), p, size)
                p += size
            }
            return list
        }
        fun kids(parent: Triple<String, Int, Int>) = boxes(parent.second + 8, parent.second + parent.third)
        fun child(parent: Triple<String, Int, Int>, type: String) = kids(parent).firstOrNull { it.first == type }
        val top = boxes(0, b.size)
        val moov = top.first { it.first == "moov" }
        val mvhd = child(moov, "mvhd")!!
        val movieTimescale = u32(mvhd.second + 8 + 12).toDouble()
        val traks = kids(moov).filter { it.first == "trak" }
        val stbls = traks.map { child(child(child(it, "mdia")!!, "minf")!!, "stbl")!! }
        val chunks = stbls.map { stbl ->
            child(stbl, "stco")?.let { stco -> (0 until u32(stco.second + 12).toInt()).map { u32(stco.second + 16 + 4 * it) } }
                ?: child(stbl, "co64")!!.let { co64 -> (0 until u32(co64.second + 12).toInt()).map { u64(co64.second + 16 + 8 * it) } }
        }
        return Layout(
            topLevel = top.map { it.first },
            movieSeconds = u32(mvhd.second + 8 + 16) / movieTimescale,
            trackSeconds = traks.map { u32(child(it, "tkhd")!!.second + 8 + 20) / movieTimescale },
            chunks = chunks,
            hasSyncTable = stbls.map { child(it, "stss") != null },
            hasCompositionTable = stbls.map { child(it, "ctts") != null },
        )
    }

    @Test fun `output is a regular mp4 that Android's own extractor can read to the end`() {
        // Android's MPEG4Extractor stops at the first fragment without its track, so the output
        // must not be fragmented at all: moov first (plays while downloading), then one mdat.
        val l = layout(assertMerged(dashVideo(), dashAudio(), 6.0))
        assertEquals(listOf("ftyp", "moov", "mdat"), l.topLevel)
        assertEquals(listOf(true, false), l.hasSyncTable, "keyframe table for video only (every audio sample is a sync sample)")
        assertEquals(true, l.hasCompositionTable[0], "x264's B-frames need composition offsets")
    }

    @Test fun `chunks of both tracks are interleaved by time, not one track after the other`() {
        val l = layout(assertMerged(dashVideo(), dashAudio(), 6.0))
        val order = (l.chunks[0].map { it to 1 } + l.chunks[1].map { it to 2 }).sortedBy { it.first }.map { it.second }
        val switches = order.zipWithNext().count { (a, b) -> a != b }
        assertTrue(switches >= order.size / 2, "track order in file: $order")
    }

    @Test fun `durations are written where galleries read them`() {
        val l = layout(assertMerged(dashVideo(seconds = 4), dashAudio(seconds = 6), 6.0))
        assertTrue(abs(l.movieSeconds - 6.0) < 0.1, "mvhd ${l.movieSeconds}")
        assertEquals(2, l.trackSeconds.size)
        assertTrue(abs(l.trackSeconds[0] - 4.0) < 0.1 && abs(l.trackSeconds[1] - 6.0) < 0.1, "tkhd ${l.trackSeconds}")
    }

    /**
     * The DASH fixture with `edts/elst` inserted into its video trak after `tkhd`: one entry with
     * duration 0 ("whole track" in DASH) and, as real encoders write it, media_time = the first
     * frame's presentation time (B-frames delay it). Fragment offsets are moof-relative, so growing
     * moov is safe.
     */
    private fun withZeroLengthEdit(dash: File, mediaTime: Int): File {
        val b = dash.readBytes()
        fun u32(at: Int) = java.nio.ByteBuffer.wrap(b, at, 4).int
        fun box(type: String, payload: ByteArray) =
            java.nio.ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()
        var p = 0
        while (String(b, p + 4, 4, Charsets.ISO_8859_1) != "moov") p += u32(p)
        val moovStart = p
        var t = moovStart + 8
        while (String(b, t + 4, 4, Charsets.ISO_8859_1) != "trak") t += u32(t)
        val tkhdEnd = t + 8 + u32(t + 8)
        val elst = box("elst", java.nio.ByteBuffer.allocate(4 + 4 + 12).putInt(0).putInt(1).putInt(0).putInt(mediaTime).putInt(0x00010000).array())
        val edts = box("edts", elst)
        val out = java.io.ByteArrayOutputStream()
        out.write(b, 0, tkhdEnd)
        out.write(edts)
        out.write(b, tkhdEnd, b.size - tkhdEnd)
        val patched = out.toByteArray()
        // grow moov and trak sizes
        java.nio.ByteBuffer.wrap(patched, moovStart, 4).putInt(u32(moovStart) + edts.size)
        java.nio.ByteBuffer.wrap(patched, t, 4).putInt(u32(t) + edts.size)
        return File(tmp.root, "edts-" + dash.name).apply { writeBytes(patched) }
    }

    @Test fun `a zero-length dash edit list becomes the real track duration`() {
        val dash = dashVideo()
        val firstPts = run("ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=pts", "-of", "csv=p=0", dash.absolutePath)
            .second.lines().first().trim().toInt()
        assertEquals(1024, firstPts, "fixture assumption: x264's B-frames present the first frame at 1024/12800 s")
        val video = withZeroLengthEdit(dash, mediaTime = firstPts)
        val out = assertMerged(video, dashAudio(), 6.0)
        val b = out.readBytes()
        val at = String(b, Charsets.ISO_8859_1).indexOf("elst")
        assertTrue(at > 0, "edit list kept")
        val segmentDuration = java.nio.ByteBuffer.wrap(b, at + 4 + 4 + 4, 4).int.toUInt().toLong()
        val l = layout(out)
        val movieTimescale = segmentDuration / l.trackSeconds[0]
        assertTrue(segmentDuration > 0, "a zero-length edit in a regular MP4 means 'show nothing'")
        assertTrue(abs(segmentDuration / movieTimescale - 6.0) < 0.1, "edit covers the track: $segmentDuration")
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
