package io.github.rudolfhk.audiosaver.engine.newpipe

import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.LinkParseResult
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.link.YouTubeLinkParser
import io.github.rudolfhk.audiosaver.core.pipeline.AudioDownloadPipeline
import io.github.rudolfhk.audiosaver.core.pipeline.DirectoryAudioSink
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadOptions
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.VideoResult
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the real YouTube. Off by default (network, flaky by nature); run with
 *   LIVE_YOUTUBE=1 ./gradlew :engine-newpipe:test --tests '*LiveYouTubeTest*'
 * This is the canary for "YouTube changed something": run it before blaming the app.
 */
class LiveYouTubeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val client = OkHttpClient()
    private val engine by lazy { NewPipeExtractionEngine(client) }

    @Before fun onlyWhenRequested() = assumeTrue("set LIVE_YOUTUBE=1 to run", System.getenv("LIVE_YOUTUBE") == "1")

    /** "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", CC BY 3.0. */
    private val creativeCommonsVideo = VideoId.of("aqz-KE-bpKQ")

    @Test fun `resolves a public creative-commons video to an m4a stream`() = runBlocking {
        val audio = engine.resolve(creativeCommonsVideo)
        println("Resolved: $audio")
        assertTrue(audio.title.contains("Big Buck Bunny"), audio.title)
        assertEquals(AudioContainer.M4A, audio.stream.container)
        assertTrue((audio.stream.contentLength ?: 0) > 100_000)
    }

    @Test fun `full pipeline saves a tagged, non-empty file`() = runBlocking {
        val out = File(tmp.root, "Music")
        val states = mutableListOf<DownloadState>()
        AudioDownloadPipeline(engine, HttpAudioDownloader(client), DirectoryAudioSink(out), File(tmp.root, "work"))
            .run(creativeCommonsVideo) { states += it; if (it !is DownloadState.Downloading) println(it) }
        val done = assertIs<DownloadState.Done>(states.last(), "ended in ${states.last()}")
        assertEquals(null, done.taggingWarning)
        assertTrue(File(out, done.saved!!.displayName).length() > 100_000)
    }

    @Test fun `unavailable video id gives a specific failure`() = runBlocking {
        val states = mutableListOf<DownloadState>()
        AudioDownloadPipeline(engine, HttpAudioDownloader(client), DirectoryAudioSink(tmp.root), tmp.root)
            .run(VideoId.of("aaaaaaaaaaa")) { states += it }
        val failed = assertIs<DownloadState.Failed>(states.last())
        println("Failure as the user sees it: ${failed.reason.userTitle} - ${failed.detail}")
        assertEquals(FailureReason.UNAVAILABLE, failed.reason)
    }

    @Test fun `lists the mix of a creative-commons video and pages further`() = runBlocking {
        val ref = (YouTubeLinkParser.parse("https://www.youtube.com/watch?v=${creativeCommonsVideo}&list=RD$creativeCommonsVideo")
            as LinkParseResult.Collection).ref
        val first = engine.listCollection(ref)
        println("Mix '${first.title}': ${first.items.size} items, more=${first.next != null}")
        first.items.take(5).forEach { println("  ${it.videoId} ${it.title} - ${it.uploader}") }
        assertTrue(first.items.size >= 5)
        assertEquals(creativeCommonsVideo, first.items.first().videoId, "a mix starts with its seed")
        val second = engine.listCollection(ref, assertNotNull(first.next))
        assertTrue(second.items.isNotEmpty())
    }

    @Test fun `saves the video as a merged mp4 next to the audio`() = runBlocking {
        val out = File(tmp.root, "Music")
        val states = mutableListOf<DownloadState>()
        AudioDownloadPipeline(engine, HttpAudioDownloader(client), DirectoryAudioSink(out), File(tmp.root, "work"))
            .run(creativeCommonsVideo, options = DownloadOptions(video = VideoQuality.P360)) { states += it; if (it !is DownloadState.Downloading) println(it) }
        val done = assertIs<DownloadState.Done>(states.last(), "ended in ${states.last()}")
        val video = assertIs<VideoResult.Saved>(done.video, "video: ${done.video}")
        println("Video: ${video.width}x${video.height}@${video.fps}, ${video.saved.sizeBytes} bytes")
        assertTrue(video.height in 240..480)
        val mp4 = File(File(out, "video"), video.saved.displayName)
        assertTrue(mp4.length() > 1_000_000)
        checkWithFfprobe(mp4, expectedSeconds = 635.0)
        keepForInspection(mp4, File(out, done.saved!!.displayName))
    }

    @Test fun `video only saves a playable mp4 and no audio file`() = runBlocking {
        val out = File(tmp.root, "Music")
        val states = mutableListOf<DownloadState>()
        AudioDownloadPipeline(engine, HttpAudioDownloader(client), DirectoryAudioSink(out), File(tmp.root, "work"))
            .run(creativeCommonsVideo, options = DownloadOptions.videoOnly(VideoQuality.P360)) { states += it }
        val done = assertIs<DownloadState.Done>(states.last(), "ended in ${states.last()}")
        assertEquals(null, done.saved)
        val video = assertIs<VideoResult.Saved>(done.video)
        checkWithFfprobe(File(File(out, "video"), video.saved.displayName), expectedSeconds = 635.0)
        assertTrue(out.listFiles()!!.none { it.isFile }, "no audio file expected")
    }

    /** Independent check of the real YouTube result (skipped where ffprobe isn't installed). */
    private fun checkWithFfprobe(mp4: File, expectedSeconds: Double) {
        fun run(vararg cmd: String) = runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            p.exitValue() to text
        }.getOrNull()
        val probe = run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height:format=duration", "-of", "default=noprint_wrappers=1", mp4.path)
            ?: return println("ffprobe not installed - skipping the independent check")
        println("ffprobe: ${probe.second}")
        assertEquals(0, probe.first, probe.second)
        assertTrue(probe.second.contains("codec_name=h264") && probe.second.contains("codec_name=aac"), probe.second)
        val duration = Regex("duration=([0-9.]+)").find(probe.second)!!.groupValues[1].toDouble()
        assertTrue(kotlin.math.abs(duration - expectedSeconds) < 5, "duration $duration, expected ~$expectedSeconds")
        val decode = run("ffmpeg", "-v", "error", "-i", mp4.path, "-f", "null", "-")!!
        assertEquals("", decode.second.trim(), "decoder errors in the real merged file")
    }

    private fun keepForInspection(vararg files: File) {
        val dir = System.getenv("LIVE_OUTPUT_DIR")?.let(::File) ?: return
        dir.mkdirs()
        files.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
    }
}
