package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioDownloadPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val videoId = VideoId.of("dQw4w9WgXcQ")
    private val states = Collections.synchronizedList(mutableListOf<DownloadState>())

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    /** A tiny but structurally valid M4A (ftyp + moov + mdat), so the real tagger runs on it. */
    private val m4a: ByteArray by lazy {
        javaClass.getResourceAsStream("/tiny.m4a")?.readBytes() ?: error("missing test resource tiny.m4a")
    }

    private fun engine(container: AudioContainer = AudioContainer.M4A, length: Long? = null, resolve: suspend () -> Unit = {}) =
        object : ExtractionEngine {
            override val description = "fake"
            override suspend fun resolve(videoId: VideoId): ResolvedAudio {
                resolve()
                return ResolvedAudio(
                    videoId, "Never Gonna Give You Up", "Rick Astley", 2009, 213,
                    AudioStreamSource(server.url("/a").toString(), container, null, null, length),
                )
            }
        }

    private fun pipeline(engine: ExtractionEngine, sink: AudioSink = DirectoryAudioSink(File(tmp.root, "Music"))) =
        AudioDownloadPipeline(engine, HttpAudioDownloader(OkHttpClient()), sink, File(tmp.root, "work"))

    private fun run(p: AudioDownloadPipeline) = runBlocking { p.run(videoId) { states += it } }

    @Test fun `happy path reports phases in order and saves a tagged file`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(m4a)).build())
        run(pipeline(engine()))

        assertIs<DownloadState.Resolving>(states.first())
        val downloading = states.filterIsInstance<DownloadState.Downloading>()
        assertTrue(downloading.isNotEmpty())
        assertEquals(m4a.size.toLong(), downloading.last().totalBytes, "total from Content-Length")
        assertEquals(1f, downloading.last().fraction)
        val savingIndex = states.indexOfFirst { it is DownloadState.Saving }
        assertTrue(savingIndex > states.indexOf(downloading.last()))

        val done = assertIs<DownloadState.Done>(states.last())
        assertNull(done.taggingWarning)
        assertEquals("Rick Astley - Never Gonna Give You Up.m4a", done.saved.displayName)
        val saved = File(File(tmp.root, "Music"), done.saved.displayName)
        assertTrue(saved.length() > m4a.size, "tags should have grown the file")
        val text = saved.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("Never Gonna Give You Up") && text.contains("Rick Astley") && text.contains("2009"))
        assertTrue(File(tmp.root, "work").listFiles().orEmpty().isEmpty(), "temp files left behind")
    }

    @Test fun `unknown size gives indeterminate progress`() {
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(m4a), 100).build())
        run(pipeline(engine()))
        assertTrue(states.filterIsInstance<DownloadState.Downloading>().all { it.totalBytes == null && it.fraction == null })
        assertIs<DownloadState.Done>(states.last())
    }

    @Test fun `extractor failure is passed through with its specific reason and message`() {
        val failing = engine(resolve = { throw AudioSaveException(FailureReason.UNAVAILABLE, "Got error ERROR: \"Video unavailable\"") })
        run(pipeline(failing))
        val failed = assertIs<DownloadState.Failed>(states.last())
        assertEquals(FailureReason.UNAVAILABLE, failed.reason)
        assertEquals("Got error ERROR: \"Video unavailable\"", failed.detail)
        assertEquals(0, server.requestCount, "nothing should be downloaded after a failed resolve")
    }

    @Test fun `untaggable download is still saved, with a warning`() {
        server.enqueue(MockResponse.Builder().body("definitely not an mp4").build())
        run(pipeline(engine()))
        val done = assertIs<DownloadState.Done>(states.last())
        assertNotNull(done.taggingWarning)
        assertTrue(done.taggingWarning.contains("without embedded tags"))
    }

    @Test fun `webm streams are saved untagged with an explanation`() {
        server.enqueue(MockResponse.Builder().body("webm bytes").build())
        run(pipeline(engine(container = AudioContainer.WEBM_OPUS)))
        val done = assertIs<DownloadState.Done>(states.last())
        assertTrue(done.saved.displayName.endsWith(".webm"))
        assertNotNull(done.taggingWarning)
    }

    @Test fun `a sink that reports the wrong size is not treated as success`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(m4a)).build())
        val lyingSink = object : AudioSink {
            override suspend fun save(file: File, displayName: String, mimeType: String, metadata: TrackMetadata) =
                SavedAudio(displayName, "nowhere", "content://x/1", mimeType, sizeBytes = 0)
        }
        run(pipeline(engine(), lyingSink))
        val failed = assertIs<DownloadState.Failed>(states.last())
        assertEquals(FailureReason.STORAGE, failed.reason)
    }

    @Test fun `storage exceptions become STORAGE failures`() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(m4a)).build())
        val fullDisk = object : AudioSink {
            override suspend fun save(file: File, displayName: String, mimeType: String, metadata: TrackMetadata): SavedAudio =
                throw java.io.IOException("No space left on device")
        }
        run(pipeline(engine(), fullDisk))
        val failed = assertIs<DownloadState.Failed>(states.last())
        assertEquals(FailureReason.STORAGE, failed.reason)
        assertEquals("No space left on device", failed.detail)
    }

    @Test fun `cancel during resolve ends in Cancelled, not Failed`() {
        val p = pipeline(engine(resolve = { awaitCancellation() }))
        runBlocking {
            val job = async(Dispatchers.Default) { p.run(videoId) { states += it } }
            while (states.isEmpty()) delay(10)
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }
        assertIs<DownloadState.Cancelled>(states.last())
    }

    @Test fun `file names are sanitised`() {
        assertEquals("AC_DC - Back In Black.m4a", FileNames.forTrack("AC/DC", "Back In Black", "m4a"))
        // Title already contains the artist: don't repeat it.
        assertEquals("Artist - Song (Official Video).m4a", FileNames.forTrack("Artist", "Artist - Song (Official Video)", "m4a"))
        assertEquals("What_ Why_.m4a", FileNames.forTrack(null, "What? Why?", "m4a"))
        assertEquals("audio.m4a", FileNames.forTrack(null, "...", "m4a"))
        assertEquals(124, FileNames.forTrack(null, "x".repeat(500), "m4a").length)
    }

    @Test fun `directory sink never overwrites`() {
        val sink = DirectoryAudioSink(File(tmp.root, "out"))
        val src = tmp.newFile().apply { writeText("abc") }
        val meta = TrackMetadata("t", null, null, null)
        runBlocking {
            assertEquals("a.m4a", sink.save(src, "a.m4a", "audio/mp4", meta).displayName)
            assertEquals("a (1).m4a", sink.save(src, "a.m4a", "audio/mp4", meta).displayName)
        }
        assertContentEquals("abc".toByteArray(), File(tmp.root, "out/a (1).m4a").readBytes())
    }
}
