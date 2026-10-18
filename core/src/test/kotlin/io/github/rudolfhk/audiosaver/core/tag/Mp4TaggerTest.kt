package io.github.rudolfhk.audiosaver.core.tag

import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-trips real MP4 layouts produced by ffmpeg through the tagger and checks, with ffprobe/ffmpeg as
 * independent readers, that (a) the tags are read back and (b) the decoded audio is bit-identical.
 * Skipped when ffmpeg isn't on PATH.
 */
class Mp4TaggerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val metadata = TrackMetadata(
        title = "Grüße – Test Tone ♪",
        artist = "Some Artist",
        year = 2019,
        comment = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    )

    companion object {
        private var ffmpegAvailable = false

        @BeforeClass @JvmStatic fun detectFfmpeg() {
            ffmpegAvailable = runCatching { run("ffmpeg", "-version").first == 0 && run("ffprobe", "-version").first == 0 }
                .getOrDefault(false)
        }

        private fun run(vararg cmd: String): Pair<Int, String> {
            val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            return process.exitValue() to output
        }
    }

    private fun fixture(name: String, vararg muxerArgs: String): File {
        assumeTrue("ffmpeg/ffprobe not installed", ffmpegAvailable)
        val out = File(tmp.root, name)
        val (code, log) = run(
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
            "-c:a", "aac", "-b:a", "64k", *muxerArgs, out.absolutePath,
        )
        assertEquals(0, code, log)
        return out
    }

    private fun decodedMd5(file: File): String {
        val (code, out) = run("ffmpeg", "-v", "error", "-i", file.absolutePath, "-map", "0:a", "-f", "md5", "-")
        assertEquals(0, code, out)
        return out.trim()
    }

    private fun probeTags(file: File): Map<String, String> {
        val (code, out) = run(
            "ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "default=noprint_wrappers=1", file.absolutePath,
        )
        assertEquals(0, code, out)
        return out.lines().filter { it.startsWith("TAG:") }.associate {
            it.removePrefix("TAG:").substringBefore('=').lowercase() to it.substringAfter('=')
        }
    }

    private fun decodeErrors(file: File): String =
        run("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "null", "-").second.trim()

    private fun assertTaggedCorrectly(input: File) {
        val output = File(tmp.root, input.nameWithoutExtension + "-tagged.m4a")
        Mp4Tagger.tag(input, output, metadata)

        assertEquals("", decodeErrors(output), "decoder reported errors")
        assertEquals(decodedMd5(input), decodedMd5(output), "audio payload changed")
        val tags = probeTags(output)
        assertEquals(metadata.title, tags["title"])
        assertEquals(metadata.artist, tags["artist"])
        assertEquals(metadata.artist, tags["album_artist"])
        assertEquals("2019", tags["date"])
        assertEquals(metadata.comment, tags["comment"])
        assertTrue(tags["major_brand"]?.trim() != "dash", "major brand still dash: $tags")
    }

    @Test fun `youtube style dash fragmented m4a`() {
        // Same shape as YouTube's itag 140: ftyp(dash), moov(empty stco), sidx, moof/mdat...
        val input = fixture("dash.m4a", "-f", "mp4", "-movflags", "+dash+global_sidx", "-frag_duration", "1000000", "-brand", "dash")
        assertTaggedCorrectly(input)
    }

    @Test fun `fragmented with mfra index`() {
        val input = fixture("frag.m4a", "-f", "mp4", "-movflags", "+frag_keyframe+empty_moov", "-frag_duration", "1000000")
        assertTaggedCorrectly(input)
    }

    @Test fun `regular faststart m4a with populated stco`() {
        val input = fixture("faststart.m4a", "-f", "mp4", "-movflags", "+faststart")
        assertTaggedCorrectly(input)
    }

    @Test fun `regular m4a with moov at end`() {
        val input = fixture("moovlast.m4a", "-f", "mp4")
        assertTaggedCorrectly(input)
    }

    @Test fun `retagging replaces previous tags instead of stacking them`() {
        val input = fixture("retag.m4a", "-f", "mp4", "-movflags", "+faststart", "-metadata", "title=Old", "-metadata", "artist=Old")
        val once = File(tmp.root, "once.m4a")
        val twice = File(tmp.root, "twice.m4a")
        Mp4Tagger.tag(input, once, metadata)
        Mp4Tagger.tag(once, twice, metadata.copy(title = "New"))
        assertEquals("New", probeTags(twice)["title"])
        assertEquals(once.length(), twice.length() + metadata.title.toByteArray().size - "New".length)
        assertEquals(decodedMd5(input), decodedMd5(twice))
    }

    @Test fun `non mp4 input is rejected cleanly`() {
        val junk = tmp.newFile("junk.m4a").apply { writeText("<html>403 Forbidden</html>") }
        assertFailsWith<TaggingException> { Mp4Tagger.tag(junk, File(tmp.root, "out.m4a"), metadata) }
    }

    @Test fun `truncated mp4 is rejected cleanly`() {
        assumeTrue(ffmpegAvailable)
        val input = fixture("trunc-src.m4a", "-f", "mp4", "-movflags", "+faststart")
        val truncated = File(tmp.root, "trunc.m4a").apply { writeBytes(input.readBytes().copyOf((input.length() / 2).toInt())) }
        assertFailsWith<TaggingException> { Mp4Tagger.tag(truncated, File(tmp.root, "out2.m4a"), metadata) }
    }
}
