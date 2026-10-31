package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.PageToken
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.engine.VideoStreamSource
import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val videoId = VideoId.of("aqz-KE-bpKQ")
    private val states = Collections.synchronizedList(mutableListOf<DownloadState>())
    private val requested = Collections.synchronizedList(mutableListOf<String>())
    private var stallVideo = false
    private var videoStatus = 200

    companion object {
        private var ffmpeg = false
        private lateinit var fixtures: File

        @BeforeClass @JvmStatic fun makeFixtures() {
            ffmpeg = runCatching { run("ffmpeg", "-version").first == 0 }.getOrDefault(false)
            if (!ffmpeg) return
            fixtures = kotlin.io.path.createTempDirectory("vp").toFile().apply { deleteOnExit() }
            fun make(name: String, vararg args: String) =
                check(run("ffmpeg", "-v", "error", "-y", *args, File(fixtures, name).absolutePath).first == 0) { name }
            val dash = arrayOf("-f", "mp4", "-movflags", "+dash+global_sidx", "-frag_duration", "1000000", "-brand", "dash")
            make("v.mp4", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "4", "-c:v", "libx264", "-g", "25", "-pix_fmt", "yuv420p", "-an", *dash)
            make("a.m4a", "-f", "lavfi", "-i", "sine=frequency=440:duration=4", "-c:a", "aac", "-b:a", "64k", *dash)
        }

        private fun run(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(60, TimeUnit.SECONDS)
            return p.exitValue() to out
        }
    }

    private val audioBytes by lazy { File(fixtures, "a.m4a").readBytes() }
    private val videoBytes by lazy { File(fixtures, "v.mp4").readBytes() }

    @Before fun start() {
        assumeTrue("ffmpeg not installed", ffmpeg)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requested += path
                val bytes = if (path.startsWith("/video")) videoBytes else audioBytes
                if (path.startsWith("/video") && videoStatus != 200) return MockResponse.Builder().code(videoStatus).build()
                val range = request.headers["Range"]?.removePrefix("bytes=")?.split('-')?.map(String::toLong)
                val (from, to) = range?.let { it[0].toInt() to minOf(it[1].toInt(), bytes.size - 1) } ?: (0 to bytes.size - 1)
                return MockResponse.Builder()
                    .code(if (range != null) 206 else 200)
                    .body(Buffer().write(bytes, from, to - from + 1))
                    .apply { if (stallVideo && path.startsWith("/video")) onResponseBody(SocketEffect.Stall) }
                    .build()
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun engine(
        container: AudioContainer = AudioContainer.M4A,
        videoStreams: List<VideoStreamSource>? = null,
    ) = object : ExtractionEngine {
        override val description = "fake"
        override suspend fun resolve(videoId: VideoId) = ResolvedAudio(
            videoId, "Big Buck Bunny", "Blender", 2008, 4,
            AudioStreamSource(server.url("/audio").toString(), container, "mp4a.40.2", 64_000, audioBytes.size.toLong()),
            videoStreams ?: listOf(
                VideoStreamSource(server.url("/video-1080").toString(), 1920, 1080, 25, "vp9", 2_000_000, videoBytes.size.toLong()),
                VideoStreamSource(server.url("/video-240").toString(), 320, 240, 25, "avc1.64000d", 300_000, videoBytes.size.toLong()),
            ),
        )

        override suspend fun listCollection(ref: CollectionRef, page: PageToken?) = error("not used")
    }

    private val out get() = File(tmp.root, "out")
    private fun pipeline(engine: ExtractionEngine) =
        AudioDownloadPipeline(engine, HttpAudioDownloader(OkHttpClient()), DirectoryAudioSink(out), File(tmp.root, "work"))

    private fun run(engine: ExtractionEngine = engine(), options: DownloadOptions = DownloadOptions(video = VideoQuality.BEST)) =
        runBlocking { pipeline(engine).run(videoId, options = options) { states += it } }

    private fun probe(file: File, entries: String) =
        run("ffprobe", "-v", "error", "-show_entries", entries, "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath)
            .second.lines().map(String::trim).filter(String::isNotEmpty)

    private fun workLeftovers() = File(tmp.root, "work").listFiles().orEmpty().map { it.name }

    @Test fun `audio and video are both saved, video merged, tagged and playable`() {
        run()
        val done = assertIs<DownloadState.Done>(states.last())
        assertEquals("Blender - Big Buck Bunny.m4a", done.saved!!.displayName)
        val video = assertIs<VideoResult.Saved>(done.video)
        assertEquals(240, video.height, "the only H.264 stream (VP9 1080p must be ignored)")
        assertNull(video.warning)
        assertEquals("Blender - Big Buck Bunny.mp4", video.saved.displayName)
        assertEquals(PipelineMime.VIDEO, video.saved.mimeType)

        val mp4 = File(File(out, "video"), video.saved.displayName)
        assertEquals(listOf("h264", "aac"), probe(mp4, "stream=codec_name"))
        assertEquals(listOf("Big Buck Bunny", "Blender"), probe(mp4, "format_tags=title,artist"))
        assertEquals("", run("ffmpeg", "-v", "error", "-i", mp4.absolutePath, "-f", "null", "-").second.trim())
        assertEquals(listOf("/audio", "/video-240"), requested.distinct())
        assertTrue(workLeftovers().isEmpty(), "temp files left: ${workLeftovers()}")
    }

    @Test fun `one progress bar spans both downloads and the phases are reported in order`() {
        run()
        val downloading = states.filterIsInstance<DownloadState.Downloading>()
        val total = (audioBytes.size + videoBytes.size).toLong()
        assertTrue(downloading.all { it.withVideo && it.totalBytes == total }, "every update covers audio + video")
        assertEquals(downloading.map { it.bytesDownloaded }.sorted(), downloading.map { it.bytesDownloaded }, "never goes backwards")
        assertEquals(total, downloading.last().bytesDownloaded)
        val kinds = states.map { it::class.simpleName }.distinct()
        assertEquals(listOf("Resolving", "Downloading", "Saving", "Merging", "Done"), kinds)
    }

    @Test fun `a failing video keeps the audio and says why`() {
        videoStatus = 403
        run()
        val done = assertIs<DownloadState.Done>(states.last())
        assertNotNull(done.saved)
        assertTrue(File(out, done.saved!!.displayName).exists())
        val failed = assertIs<VideoResult.Failed>(done.video)
        assertEquals(FailureReason.DOWNLOAD_FAILED, failed.reason)
        assertTrue(failed.detail.contains("403"), failed.detail)
        assertTrue(workLeftovers().isEmpty())
    }

    @Test fun `retry video only does not save the audio again`() {
        run(options = DownloadOptions(saveAudio = false, video = VideoQuality.BEST))
        val done = assertIs<DownloadState.Done>(states.last())
        assertNull(done.saved)
        assertIs<VideoResult.Saved>(done.video)
        assertFalse(states.any { it is DownloadState.Saving }, "no audio save phase")
        assertEquals(emptyList(), out.listFiles()!!.filter { it.isFile }.map { it.name }, "no audio file written")
    }

    @Test fun `no h264 stream fails the video before downloading it`() {
        run(engine(videoStreams = listOf(VideoStreamSource(server.url("/video-av1").toString(), 1920, 1080, 30, "av01.0.08M.08", 1, 1))))
        val done = assertIs<DownloadState.Done>(states.last())
        assertNotNull(done.saved)
        assertEquals(FailureReason.NO_VIDEO_STREAM, assertIs<VideoResult.Failed>(done.video).reason)
        assertEquals(listOf("/audio"), requested.distinct())
    }

    @Test fun `not enough space is reported up front and the audio is still saved`() {
        val huge = VideoStreamSource(server.url("/video-big").toString(), 1920, 1080, 30, "avc1.640028", 1, Long.MAX_VALUE / 8)
        run(engine(videoStreams = listOf(huge)))
        val done = assertIs<DownloadState.Done>(states.last())
        assertNotNull(done.saved)
        val failed = assertIs<VideoResult.Failed>(done.video)
        assertEquals(FailureReason.STORAGE, failed.reason)
        assertTrue(failed.detail.startsWith("Not enough free space"), failed.detail)
        assertEquals(listOf("/audio"), requested.distinct(), "the video must not be fetched")
        assertTrue(states.filterIsInstance<DownloadState.Downloading>().none { it.withVideo })
    }

    @Test fun `retry video only with no usable stream ends without downloading anything`() {
        run(engine(container = AudioContainer.WEBM_OPUS), DownloadOptions(saveAudio = false, video = VideoQuality.BEST))
        val done = assertIs<DownloadState.Done>(states.last())
        assertEquals(FailureReason.NO_AUDIO_STREAM, assertIs<VideoResult.Failed>(done.video).reason)
        assertTrue(requested.isEmpty())
    }

    @Test fun `cancel during the video download cancels the job and leaves no temp files`() {
        stallVideo = true
        val p = pipeline(engine())
        runBlocking {
            val job = async(Dispatchers.IO) { p.run(videoId, options = DownloadOptions(video = VideoQuality.BEST)) { states += it } }
            while (requested.none { it.startsWith("/video") }) delay(10)
            delay(300)
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }
        assertIs<DownloadState.Cancelled>(states.last())
        assertTrue(workLeftovers().isEmpty(), "temp files left: ${workLeftovers()}")
        assertFalse(File(out, "video").exists(), "no partial video saved")
    }
}

private object PipelineMime {
    const val VIDEO = AudioDownloadPipeline.VIDEO_MIME
}
