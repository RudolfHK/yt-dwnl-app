package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.CollectionItem
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.PageToken
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.link.CollectionKind
import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.VideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BatchDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val m4a by lazy { javaClass.getResourceAsStream("/tiny.m4a")!!.readBytes() }
    private val states = Collections.synchronizedList(mutableListOf<BatchState>())
    private val resolved = Collections.synchronizedList(mutableListOf<String>())

    /** Video ids starting with "fail" are unavailable, "rate" are rate-limited, "hang" never resolve. */
    private val engine = object : ExtractionEngine {
        override val description = "fake"
        override suspend fun resolve(videoId: VideoId): ResolvedAudio {
            resolved += videoId.value
            when {
                videoId.value.startsWith("fail") -> throw AudioSaveException(FailureReason.UNAVAILABLE, "Video unavailable")
                videoId.value.startsWith("rate") -> throw AudioSaveException(FailureReason.RATE_LIMITED, "HTTP 429")
                videoId.value.startsWith("hang") -> awaitCancellation()
            }
            return ResolvedAudio(
                videoId, "Song ${videoId.value}", "Artist", 2020, 200,
                AudioStreamSource(server.url("/${videoId.value}").toString(), AudioContainer.M4A, null, null, null),
            )
        }

        override suspend fun listCollection(ref: CollectionRef, page: PageToken?) = error("not used")
    }

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().body(Buffer().write(m4a)).build()
        }
        server.start()
    }

    @After fun stop() = server.close()

    private val music get() = File(tmp.root, "Music")

    private fun downloader() = BatchDownloader(
        AudioDownloadPipeline(engine, HttpAudioDownloader(OkHttpClient()), DirectoryAudioSink(music), File(tmp.root, "work")),
        pauseBetweenItemsMs = 0,
    )

    private fun item(id: String, uploader: String? = "Artist - Topic", available: Boolean = true) =
        CollectionItem(VideoId.of(id), "Song $id", uploader, 200, available)

    private fun mix(vararg ids: String) = BatchRequest.forSelection(
        "Mix - Something", CollectionKind.MIX, null, ids.map { item(it) }, ids.map(VideoId::of).toSet(),
    )

    @Test fun `downloads in order and continues after a failure`() {
        val request = mix("aaaaaaaaaaa", "failfailfai", "bbbbbbbbbbb")
        runBlocking { downloader().run(request) { states += it } }

        assertEquals(listOf("aaaaaaaaaaa", "failfailfai", "bbbbbbbbbbb"), resolved)
        val final = states.last()
        assertTrue(final.finished)
        assertEquals(2, final.savedCount)
        assertEquals(1, final.failedCount)
        val failed = assertIs<ItemStatus.Failed>(final.items[1].status)
        assertEquals(FailureReason.UNAVAILABLE, failed.reason)
        assertEquals(listOf("failfailfai"), final.failedEntries.map { it.item.videoId.value })
        // mixes are not albums: flat folder, no album fields
        assertTrue(File(music, "Artist - Song aaaaaaaaaaa.m4a").exists(), music.listFiles()?.map { it.name }.toString())
        assertTrue(states.any { it.activeIndex == 1 }, "progress should show which song is active")
    }

    @Test fun `rate limiting stops the rest of the batch instead of hammering youtube`() {
        val request = mix("aaaaaaaaaaa", "ratelimited", "bbbbbbbbbbb", "ccccccccccc")
        runBlocking { downloader().run(request) { states += it } }
        assertEquals(listOf("aaaaaaaaaaa", "ratelimited"), resolved, "nothing after the 429 may be requested")
        val final = states.last()
        assertEquals(1, final.savedCount)
        assertEquals(3, final.failedCount)
        val skipped = assertIs<ItemStatus.Failed>(final.items[3].status)
        assertTrue(skipped.detail.startsWith("Not attempted"), skipped.detail)
    }

    @Test fun `cancel marks the current and remaining songs cancelled and keeps finished ones`() {
        val request = mix("aaaaaaaaaaa", "hanghanghan", "bbbbbbbbbbb")
        runBlocking {
            val job = async(Dispatchers.Default) { downloader().run(request) { states += it } }
            while (states.none { it.activeIndex == 1 }) delay(10)
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }
        val final = states.last()
        assertTrue(final.finished)
        assertIs<ItemStatus.Saved>(final.items[0].status)
        assertIs<ItemStatus.Cancelled>(final.items[1].status)
        assertIs<ItemStatus.Cancelled>(final.items[2].status)
        assertEquals(listOf("aaaaaaaaaaa", "hanghanghan"), resolved)
    }

    @Test fun `album selection keeps whole-album track numbers, album artist and folder`() {
        val all = listOf(item("aaaaaaaaaaa"), item("bbbbbbbbbbb"), item("ccccccccccc"), item("deleteddddd", available = false))
        val request = BatchRequest.forSelection(
            title = "Whenever You Need Somebody",
            kind = CollectionKind.ALBUM,
            uploader = null,
            allItems = all,
            selected = setOf(VideoId.of("ccccccccccc"), VideoId.of("aaaaaaaaaaa"), VideoId.of("deleteddddd")),
        )
        assertEquals(listOf("aaaaaaaaaaa", "ccccccccccc"), request.entries.map { it.item.videoId.value }, "album order, unavailable dropped")
        val third = request.entries[1].context
        assertEquals(3, third.trackNumber)
        assertEquals(4, third.trackTotal)
        assertEquals("Artist", third.albumArtist, "derived from the songs' ' - Topic' channel")
        assertEquals("Whenever You Need Somebody", third.album)

        runBlocking { downloader().run(request) { states += it } }
        val folder = File(music, "Whenever You Need Somebody")
        assertEquals(setOf("01 - Song aaaaaaaaaaa.m4a", "03 - Song ccccccccccc.m4a"), folder.list()!!.toSet())
        val text = File(folder, "03 - Song ccccccccccc.m4a").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("Whenever You Need Somebody"), "album tag written")
    }

    @Test fun `retry of failed entries reuses their album context`() {
        val all = listOf(item("aaaaaaaaaaa"), item("failfailfai"))
        val request = BatchRequest.forSelection("Album X", CollectionKind.ALBUM, "Band", all, all.map { it.videoId }.toSet())
        runBlocking { downloader().run(request) { states += it } }
        val retry = BatchRequest(request.title, request.kind, states.last().failedEntries)
        assertEquals(2, retry.entries.single().context.trackNumber)
        assertEquals("Band", retry.entries.single().context.albumArtist)
    }
}
