package io.github.rudolfhk.audiosaver.core.download

import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.RequestVariant
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

    // ---------------------------------------------------------------- request variants

    private val playerStyle = RequestVariant(
        "player", mapOf("User-Agent" to "visionos-ua"),
        rangeAsQueryParameter = true, requestNumberParameter = true, postBody = byteArrayOf(0x78, 0),
    )
    private val headerStyle = RequestVariant("header", mapOf("User-Agent" to "visionos-ua"))
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    private fun variantSource(vararg variants: RequestVariant) =
        source(payload.size.toLong()).copy(requestVariants = variants.toList())

    /** Serves [payload]; [accept] decides per request whether this style is allowed (else 403). */
    private fun serveVariants(ignoreRangeParameter: Boolean = false, accept: (RecordedRequest) -> Boolean) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (!accept(request)) return MockResponse.Builder().code(403).build()
                val range = request.url.queryParameter("range")?.takeUnless { ignoreRangeParameter }
                    ?: request.headers["Range"]?.removePrefix("bytes=")
                if (range == null) return MockResponse.Builder().code(200).body(Buffer().write(payload)).build()
                val (from, to) = range.split('-').map { it.toInt() }
                val end = minOf(to, payload.size - 1)
                val code = if (request.headers["Range"] != null) 206 else 200 // YouTube answers range= with 200
                return MockResponse.Builder().code(code).body(Buffer().write(payload, from, end - from + 1)).build()
            }
        }
    }

    private fun downloadVariants(vararg variants: RequestVariant): ByteArray {
        val target = tmp.newFile()
        runBlocking { downloader.download(variantSource(*variants), target) { _, _ -> } }
        return target.readBytes()
    }

    @Test fun `player style - POST, range and rn in the url, its headers`() {
        serveVariants { it.method == "POST" && it.headers["User-Agent"] == "visionos-ua" && it.url.queryParameter("range") != null }
        assertContentEquals(payload, downloadVariants(playerStyle, headerStyle))
        assertTrue(requests.all { it.method == "POST" && it.body?.toByteArray()?.contentEquals(byteArrayOf(0x78, 0)) == true })
        assertEquals(listOf("0", "1", "2", "3"), requests.map { it.url.queryParameter("rn") })
        assertEquals(listOf("0-299999", "300000-599999", "600000-899999", "900000-999999"), requests.map { it.url.queryParameter("range") })
        assertTrue(requests.none { it.headers["Range"] != null })
    }

    @Test fun `a refused style falls back once and the working one is kept`() {
        serveVariants { it.method == "GET" && it.headers["Range"] != null }
        assertContentEquals(payload, downloadVariants(playerStyle, headerStyle))
        assertEquals(1 + 4, requests.size, "one refusal, then 4 chunks without trying the refused style again")
        assertEquals("POST", requests.first().method)
        assertTrue(requests.drop(1).all { it.method == "GET" })
    }

    @Test fun `a host ignoring the range parameter is detected instead of writing wrong bytes`() {
        serveVariants(ignoreRangeParameter = true) { true }
        assertContentEquals(payload, downloadVariants(playerStyle, headerStyle))
        assertTrue(requests.drop(1).all { it.headers["Range"] != null }, "switched to the Range header")
    }

    @Test fun `refused by every style says which were tried`() {
        serveVariants { false }
        val e = assertFailsWith<AudioSaveException> { downloadVariants(playerStyle, headerStyle) }
        assertEquals(FailureReason.DOWNLOAD_FAILED, e.reason)
        assertTrue(e.detail.contains("403") && e.detail.contains("tried: player, header"), e.detail)
        assertEquals(2, requests.size)
    }

    @Test fun `unknown size also falls back`() {
        serveVariants { it.method == "GET" }
        val target = tmp.newFile()
        runBlocking { downloader.download(variantSource(playerStyle, headerStyle).copy(contentLength = null), target) { _, _ -> } }
        assertContentEquals(payload, target.readBytes())
        assertEquals(2, requests.size)
    }

    // ---------------------------------------------------------------- parallel chunks

    private val smallChunks = HttpAudioDownloader(client, chunkSize = 100_000)
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()
    private val maxInFlight = java.util.concurrent.atomic.AtomicInteger()
    private val concurrencyAfter429 = java.util.concurrent.atomic.AtomicInteger()

    /** Like [serve], but each answer takes a moment so overlapping requests can be counted. */
    private fun serveSlowly(override: (requestNumber: Int, range: String?) -> MockResponse? = { _, _ -> null }) {
        serve { n, range ->
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(60)
                override(n, range)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun downloadParallel(source: AudioStreamSource = source(payload.size.toLong()), connections: Int = 3): ByteArray {
        val target = tmp.newFile()
        runBlocking { smallChunks.download(source, target, connections) { d, t -> progress += d to t } }
        return target.readBytes()
    }

    @Test fun `parallel chunks give the same bytes over up to three connections`() {
        serveSlowly()
        assertContentEquals(payload, downloadParallel(connections = 8))
        assertEquals(10, ranges.size, "each chunk fetched once: $ranges")
        assertEquals(3, maxInFlight.get(), "capped at three connections")
        assertEquals("bytes=0-99999", ranges.first(), "first chunk alone, to settle the request style")
        assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first }, "progress never goes backwards")
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
    }

    @Test fun `one connection is the default`() {
        serveSlowly()
        assertContentEquals(payload, run { val t = tmp.newFile(); runBlocking { smallChunks.download(source(payload.size.toLong()), t) { _, _ -> } }; t.readBytes() })
        assertEquals(1, maxInFlight.get())
    }

    @Test fun `http 429 while parallel drops back to one connection and still finishes`() {
        val limited = java.util.concurrent.atomic.AtomicBoolean(false)
        serveSlowly { n, _ ->
            if (limited.get()) concurrencyAfter429.accumulateAndGet(inFlight.get()) { a, b -> maxOf(a, b) }
            if (n == 3) {
                limited.set(true)
                MockResponse.Builder().code(429).build()
            } else {
                null
            }
        }
        assertContentEquals(payload, downloadParallel())
        assertTrue(maxInFlight.get() > 1)
        // Requests already in flight finish; once they have, everything runs one at a time.
        val tail = ranges.takeLast(4)
        assertEquals(tail.size, tail.toSet().size, "no chunk fetched twice at the end: $ranges")
        assertTrue(concurrencyAfter429.get() <= 3)
    }

    @Test fun `http 429 with one connection left is still a rate-limit failure`() {
        serveSlowly { n, _ -> if (n >= 1) MockResponse.Builder().code(429).build() else null }
        val e = assertFailsWith<AudioSaveException> { downloadParallel() }
        assertEquals(FailureReason.RATE_LIMITED, e.reason)
    }

    @Test fun `a host ignoring Range falls back to the sequential download`() {
        serve { _, _ -> MockResponse.Builder().code(200).body(Buffer().write(payload)).build() }
        assertContentEquals(payload, downloadParallel())
    }

    @Test fun `parallel works with the player request style`() {
        serveVariants { it.method == "POST" }
        val target = tmp.newFile()
        runBlocking { smallChunks.download(variantSource(playerStyle), target, 3) { _, _ -> } }
        assertContentEquals(payload, target.readBytes())
        val rn = requests.map { it.url.queryParameter("rn")!!.toInt() }
        assertEquals(rn.size, rn.toSet().size, "request numbers stay unique across connections")
    }

    @Test fun `a refusal after the first chunk moves every connection to the next style once`() {
        val refused = java.util.concurrent.atomic.AtomicInteger()
        serveVariants { r ->
            val isPlayer = r.method == "POST"
            // The player style works for the first chunk, then the host starts refusing it.
            if (isPlayer && requests.count { it.method == "POST" } > 1) { refused.incrementAndGet(); false } else true
        }
        val target = tmp.newFile()
        runBlocking { smallChunks.download(variantSource(playerStyle, headerStyle), target, 3) { _, _ -> } }
        assertContentEquals(payload, target.readBytes())
        assertTrue(refused.get() in 1..3, "each connection hits the refusal at most once: ${refused.get()}")
    }
}
