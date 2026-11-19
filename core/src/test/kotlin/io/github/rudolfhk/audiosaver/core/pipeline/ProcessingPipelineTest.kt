package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.audio.FfmpegPcmDecoder
import io.github.rudolfhk.audiosaver.core.audio.FfmpegTools
import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.PageToken
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.engine.Thumbnail
import io.github.rudolfhk.audiosaver.core.engine.VideoStreamSource
import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.tag.CoverArt
import io.github.rudolfhk.audiosaver.core.tag.CoverGeometry
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The download pipeline with the advanced processing options, end to end with real media files. */
class ProcessingPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    companion object {
        private lateinit var fixtures: File

        @BeforeClass @JvmStatic fun makeFixtures() {
            if (!FfmpegTools.available) return
            fixtures = kotlin.io.path.createTempDirectory("pp").toFile().apply { deleteOnExit() }
            val dash = arrayOf("-movflags", "+dash+global_sidx", "-frag_duration", "1000000", "-brand", "dash")
            // 1.5 s silence, 4 s of noise, 1.5 s silence - like a music video's intro/outro.
            val padded = arrayOf(
                "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo:d=1.5",
                "-f", "lavfi", "-i", "anoisesrc=color=pink:duration=4:sample_rate=48000:amplitude=0.3",
                "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo:d=1.5",
                "-filter_complex", "[1]aformat=channel_layouts=stereo[s];[0][s][2]concat=n=3:v=0:a=1",
            )
            FfmpegTools.make(File(fixtures, "a.m4a"), *padded, "-c:a", "aac", "-b:a", "128k", "-f", "mp4", *dash)
            FfmpegTools.make(File(fixtures, "a.webm"), *padded, "-c:a", "libopus", "-b:a", "128k", "-f", "webm")
            FfmpegTools.make(File(fixtures, "v.mp4"), "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "7", "-c:v", "libx264", "-g", "25", "-pix_fmt", "yuv420p", "-an", "-f", "mp4", *dash)
            FfmpegTools.make(File(fixtures, "thumb.jpg"), "-f", "lavfi", "-i", "testsrc=size=1280x720", "-frames:v", "1")
        }
    }

    private val server = MockWebServer()
    private val requested = Collections.synchronizedList(mutableListOf<String>())
    private val states = Collections.synchronizedList(mutableListOf<DownloadState>())
    private var thumbStatus = 200
    private val videoId = VideoId.of("aqz-KE-bpKQ")

    @Before fun start() {
        assumeTrue("ffmpeg not installed", FfmpegTools.available)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requested += path
                if (path == "/thumb" && thumbStatus != 200) return MockResponse.Builder().code(thumbStatus).build()
                val file = File(fixtures, mapOf("/m4a" to "a.m4a", "/opus" to "a.webm", "/video" to "v.mp4", "/thumb" to "thumb.jpg").getValue(path))
                return MockResponse.Builder().body(Buffer().write(file.readBytes())).build()
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun engine(withOpus: Boolean = true) = object : ExtractionEngine {
        override val description = "fake"
        override suspend fun resolve(videoId: VideoId) = ResolvedAudio(
            videoId, "Blender - Big Buck Bunny (Official Video) [HD]", "Blender Foundation", 2008, 7,
            AudioStreamSource(server.url("/m4a").toString(), AudioContainer.M4A, "mp4a.40.2", 128_000, File(fixtures, "a.m4a").length()),
            videoStreams = listOf(VideoStreamSource(server.url("/video").toString(), 320, 240, 25, "avc1.4d401e", 300_000, File(fixtures, "v.mp4").length())),
            opusStream = if (withOpus) AudioStreamSource(server.url("/opus").toString(), AudioContainer.WEBM_OPUS, "opus", 128_000, File(fixtures, "a.webm").length()) else null,
            channel = "Blender Foundation",
            thumbnails = listOf(Thumbnail(server.url("/thumb").toString(), 1280, 720), Thumbnail("unused", 120, 90)),
        )

        override suspend fun listCollection(ref: CollectionRef, page: PageToken?) = error("not used")
    }

    /** JVM stand-in for the app's Bitmap cropper. */
    private val cropper = ImageCropper { bytes, maxSize ->
        ImageIO.read(bytes.inputStream())?.let { image ->
            val rect = CoverGeometry.squareCrop(image.width, image.height) { false }
            val size = minOf(rect.size, maxSize)
            val square = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
            square.createGraphics().drawImage(image, 0, 0, size, size, rect.left, rect.top, rect.left + rect.size, rect.top + rect.size, null)
            CoverArt(ByteArrayOutputStream().also { ImageIO.write(square, "jpg", it) }.toByteArray(), size, size)
        }
    }

    private val out get() = File(tmp.root, "out")

    private fun run(
        processing: Processing,
        video: VideoQuality? = null,
        engine: ExtractionEngine = engine(),
        toolkit: Toolkit = Toolkit(FfmpegPcmDecoder(), cropper),
    ): DownloadState {
        val pipeline = AudioDownloadPipeline(engine, HttpAudioDownloader(OkHttpClient()), DirectoryAudioSink(out), File(tmp.root, "work"), toolkit)
        runBlocking { pipeline.run(videoId, options = DownloadOptions(video = video, processing = processing)) { states += it } }
        return states.last()
    }

    private fun savedFile(done: DownloadState.Done) = File(out, done.saved!!.displayName)

    @Test fun `opus with smart title, cover and replaygain`() {
        val done = assertIs<DownloadState.Done>(run(Processing(audioFormat = AudioFormat.OPUS, replayGain = true)))
        assertEquals("Blender - Big Buck Bunny.opus", done.saved!!.displayName, "smart title: noise stripped, artist from the title")
        assertNull(done.taggingWarning)
        val file = savedFile(done)
        val tags = FfmpegTools.probeTags(file, "stream_tags")
        assertEquals("Big Buck Bunny", tags["title"])
        assertEquals("Blender", tags["artist"])
        assertNotNull(tags["r128_track_gain"], "$tags")
        assertTrue(FfmpegTools.probe(file, "stream=codec_name").containsAll(listOf("opus", "mjpeg")))
        assertTrue(requested.containsAll(listOf("/opus", "/thumb")) && "/m4a" !in requested, "$requested")
        assertTrue(states.any { it is DownloadState.Processing && it.step == ProcessingStep.REMUXING_OPUS })
    }

    @Test fun `mp3 with replaygain matching the measured loudness`() {
        val done = assertIs<DownloadState.Done>(run(Processing(audioFormat = AudioFormat.MP3, replayGain = true)))
        val file = savedFile(done)
        assertTrue(file.name.endsWith(".mp3"))
        assertEquals("audio/mpeg", done.saved!!.mimeType)
        val tags = FfmpegTools.probeTags(file, "format_tags")
        val gain = tags["replaygain_track_gain"]!!.removeSuffix(" dB").toDouble()
        val expected = -18.0 - FfmpegTools.integratedLoudness(File(fixtures, "a.m4a"))
        assertTrue(abs(gain - expected) < 0.5, "gain $gain vs expected $expected")
        assertTrue(states.any { it is DownloadState.Processing && it.step == ProcessingStep.ENCODING_MP3 })
    }

    @Test fun `silence trimming removes the intro and outro without re-encoding`() {
        val done = assertIs<DownloadState.Done>(run(Processing(trimSilence = true)))
        val file = savedFile(done)
        assertTrue(file.name.endsWith(".m4a"))
        val d = FfmpegTools.durationSeconds(file)
        assertTrue(d in 4.0..4.6, "7 s with 1.5 s silence each side should become ~4.3 s, was $d")
        assertEquals("", FfmpegTools.decodeErrors(file))
    }

    @Test fun `manual trim for opus`() {
        val done = assertIs<DownloadState.Done>(run(Processing(audioFormat = AudioFormat.OPUS, trim = io.github.rudolfhk.audiosaver.core.audio.TimeRange(1_000, 5_000))))
        val d = FfmpegTools.decodedSeconds(savedFile(done))
        assertTrue(abs(d - 4.0) < 0.03, "decoded $d")
    }

    @Test fun `edited tags are the ones written`() {
        val reviewer = TagReviewer { proposal -> proposal.metadata.copy(title = "My Title", album = "My Album") }
        val done = assertIs<DownloadState.Done>(run(Processing(reviewTags = true), toolkit = Toolkit(FfmpegPcmDecoder(), cropper, reviewer)))
        assertEquals("Blender - My Title.m4a", done.saved!!.displayName)
        val tags = FfmpegTools.probeTags(savedFile(done), "format_tags")
        assertEquals("My Title", tags["title"])
        assertEquals("My Album", tags["album"])
    }

    @Test fun `cancelling the tag dialog cancels the download and saves nothing`() {
        val reviewer = TagReviewer { null }
        assertFailsWith<CancellationException> { run(Processing(reviewTags = true), toolkit = Toolkit(FfmpegPcmDecoder(), cropper, reviewer)) }
        assertIs<DownloadState.Cancelled>(states.last())
        assertTrue(out.listFiles().orEmpty().isEmpty())
        assertTrue(File(tmp.root, "work").listFiles().orEmpty().isEmpty(), "temp files left")
    }

    @Test fun `fallbacks never cost the song`() {
        // No Opus stream -> M4A
        var done = assertIs<DownloadState.Done>(run(Processing(audioFormat = AudioFormat.OPUS), engine = engine(withOpus = false)))
        assertTrue(done.saved!!.displayName.endsWith(".m4a"))
        assertTrue(done.taggingWarning!!.contains("no Opus stream"))
        // No decoder -> MP3 not possible, saved as M4A
        states.clear()
        done = assertIs<DownloadState.Done>(run(Processing(audioFormat = AudioFormat.MP3, replayGain = true), toolkit = Toolkit(null, cropper)))
        assertTrue(done.saved!!.displayName.endsWith(".m4a"))
        assertTrue(done.taggingWarning!!.contains("decoder"), done.taggingWarning)
        // Thumbnail fails -> no cover, still saved
        states.clear()
        thumbStatus = 404
        done = assertIs<DownloadState.Done>(run(Processing()))
        assertTrue(done.taggingWarning!!.startsWith("No cover art"), done.taggingWarning)
        assertTrue(FfmpegTools.probe(savedFile(done), "stream=codec_name").none { it == "mjpeg" })
    }

    @Test fun `smart titles can be switched off`() {
        val done = assertIs<DownloadState.Done>(run(Processing(smartTitles = false, coverArt = false)))
        assertEquals("Blender Foundation - Blender - Big Buck Bunny (Official Video) [HD].m4a", done.saved!!.displayName)
        assertTrue("/thumb" !in requested, "no cover requested when it's off")
    }

    @Test fun `opus audio plus video downloads the AAC stream for the mp4 as well`() {
        val done = assertIs<DownloadState.Done>(run(Processing(audioFormat = AudioFormat.OPUS), video = VideoQuality.BEST))
        assertTrue(done.saved!!.displayName.endsWith(".opus"))
        val video = assertIs<VideoResult.Saved>(done.video)
        val mp4 = File(File(out, "video"), video.saved.displayName)
        assertEquals(listOf("h264", "aac", "mjpeg"), FfmpegTools.probe(mp4, "stream=codec_name"), "video with AAC sound and the cover")
        assertTrue(requested.containsAll(listOf("/opus", "/m4a", "/video")), "$requested")
        val downloading = states.filterIsInstance<DownloadState.Downloading>()
        val total = listOf("a.webm", "a.m4a", "v.mp4").sumOf { File(fixtures, it).length() }
        assertTrue(downloading.all { it.totalBytes == total }, "one bar over all three downloads")
        assertEquals(total, downloading.last().bytesDownloaded)
    }
}
