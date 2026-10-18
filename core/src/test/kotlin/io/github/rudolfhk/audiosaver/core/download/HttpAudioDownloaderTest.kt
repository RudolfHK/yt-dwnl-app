package io.github.rudolfhk.audiosaver.core.download

import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpAudioDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val payload = Random(42).nextBytes(1_000_000)
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val chunk = 300_000L
    private val downloader = HttpAudioDownloader(client, chunkSize = chunk)
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())
    private val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun source(contentLength: Long?) = AudioStreamSource(
        url = server.url("/audio").toString(),
        container = AudioContainer.M4A,
        codec = "mp4a.40.2",
        averageBitrate = 128_000,
        contentLength = contentLength,
        requestHeaders = mapOf("User-Agent" to "test-agent"),
    )

    /** Serves [payload], honouring Range; [override] can replace the response for a given request number. */
    private fun serve(override: (requestNumber: Int, range: String?) -> MockResponse? = { _, _ -> null }) {
        server.dispatcher = object : Dispatcher() {
            var n = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]
                ranges += range
                override(n++, range)?.let { return it }
                if (range == null) return MockResponse.Builder().code(200).body(Buffer().write(payload)).build()
                val (from, to) = range.removePrefix("bytes=").split('-').map { it.toLong() }
                val end = minOf(to, payload.size - 1L)
                return MockResponse.Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes $from-$end/${payload.size}")
                    .body(Buffer().write(payload, from.toInt(), (end - from + 1).toInt()))
                    .build()
            }
        }
    }

    private fun download(contentLength: Long?): ByteArray {
        val target = tmp.newFile()
        runBlocking { downloader.download(source(contentLength), target) { d, t -> progress += d to t } }
        return target.readBytes()
    }

    @Test fun `known size downloads in range chunks with determinate progress`() {
        serve()
        assertContentEquals(payload, download(payload.size.toLong()))
        assertEquals(listOf("bytes=0-299999", "bytes=300000-599999", "bytes=600000-899999", "bytes=900000-999999"), ranges)
        assertTrue(progress.all { it.second == payload.size.toLong() })
        assertEquals(payload.size.toLong(), progress.last().first)
        assertEquals("test-agent", server.takeRequest().headers["User-Agent"])
    }

    @Test fun `unknown size streams in one request and reports total from Content-Length if present`() {
        serve()
        assertContentEquals(payload, download(null))
        assertEquals(listOf<String?>(null), ranges)
        assertEquals(payload.size.toLong(), progress.last().second)
    }

    @Test fun `unknown size and no Content-Length stays indeterminate`() {
        serve { _, _ -> MockResponse.Builder().code(200).chunkedBody(Buffer().write(payload), 64 * 1024).build() }
        assertContentEquals(payload, download(null))
        assertTrue(progress.all { it.second == null }, "progress must not invent a total")
    }

    @Test fun `server ignoring Range on the first request is accepted`() {
        serve { n, _ -> if (n == 0) MockResponse.Builder().code(200).body(Buffer().write(payload)).build() else null }
        assertContentEquals(payload, download(payload.size.toLong()))
        assertEquals(1, ranges.size)
    }

    @Test fun `dropped connection mid-chunk resumes from the last received byte`() {
        serve { n, range ->
            if (n == 1) {
                val from = range!!.removePrefix("bytes=").substringBefore('-').toInt()
                MockResponse.Builder().code(206)
                    .body(Buffer().write(payload, from, chunk.toInt()))
                    .onResponseBody(SocketEffect.ShutdownConnection)
                    .build()
            } else {
                null
            }
        }
        assertContentEquals(payload, download(payload.size.toLong()))
        // The second chunk broke halfway; the retry must start mid-chunk rather than from its beginning.
        assertTrue(ranges.any { it != null && it.removePrefix("bytes=").substringBefore('-').toLong() % chunk != 0L }, "$ranges")
    }

    @Test fun `http 403 surfaces as a specific failure`() {
        serve { _, _ -> MockResponse.Builder().code(403).body("nope").build() }
        val e = assertFailsWith<AudioSaveException> { download(payload.size.toLong()) }
        assertEquals(FailureReason.DOWNLOAD_FAILED, e.reason)
        assertTrue(e.detail.contains("403"), e.detail)
    }

    @Test fun `http 429 is reported as rate limiting`() {
        serve { _, _ -> MockResponse.Builder().code(429).build() }
        val e = assertFailsWith<AudioSaveException> { download(null) }
        assertEquals(FailureReason.RATE_LIMITED, e.reason)
    }

    @Test fun `persistent network failure gives up with NETWORK reason`() {
        serve { _, _ -> MockResponse.Builder().onRequestStart(SocketEffect.ShutdownConnection).build() }
        val e = assertFailsWith<AudioSaveException> { download(payload.size.toLong()) }
        assertEquals(FailureReason.NETWORK, e.reason)
    }

    @Test fun `empty partial responses fail instead of looping or reporting success`() {
        serve { _, _ -> MockResponse.Builder().code(206).body("").build() }
        val e = assertFailsWith<AudioSaveException> { download(payload.size.toLong()) }
        assertEquals(FailureReason.NETWORK, e.reason)
        assertEquals(3, ranges.size)
    }

    @Test fun `cancellation aborts an in-flight stalled request promptly`() {
        val started = CountDownLatch(1)
        serve { _, _ ->
            started.countDown()
            MockResponse.Builder().code(206).body(Buffer().write(payload, 0, 300_000)).onResponseBody(SocketEffect.Stall).build()
        }
        val target = tmp.newFile()
        runBlocking {
            val job = async(Dispatchers.IO) { downloader.download(source(payload.size.toLong()), target) { _, _ -> } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            delay(500) // let the client get past the headers and block reading the stalled body
            val t0 = System.nanoTime()
            job.cancel()
            assertFailsWith<CancellationException> { withTimeout(5_000) { job.await() } }
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(3), "cancel took too long")
        }
    }
}
