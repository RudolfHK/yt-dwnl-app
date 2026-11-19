package io.github.rudolfhk.audiosaver.core.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import java.io.IOException
import java.time.LocalDate
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    private val server = MockWebServer()
    private val responses = mutableMapOf<String, MockResponse>()
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    private val build = BuildInfo("1.0", "65cabc2 (dev, 2026-10-05)", LocalDate.of(2026, 10, 5))

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.url.encodedPath
                return responses[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun release(tag: String, published: String, prerelease: Boolean = false) = MockResponse.Builder().body(
        """{"tag_name":"$tag","name":"Release $tag","html_url":"https://github.com/x/releases/$tag",
           "published_at":"$published","draft":false,"prerelease":$prerelease,"assets":[],"body":"…"}""",
    ).build()

    private val appPath = "/repos/RudolfHK/yt-dwnl-app/releases/latest"
    private val extractorPath = "/repos/TeamNewPipe/NewPipeExtractor/releases/latest"

    private fun check() = runBlocking { UpdateChecker(OkHttpClient(), build, server.url("/").toString()).check() }

    @Test fun `nothing newer`() {
        responses[appPath] = release("v1.0", "2026-11-01T10:00:00Z")
        responses[extractorPath] = release("v0.26.5", "2026-08-15T12:00:00Z")
        val status = check()
        assertFalse(status.anything)
        assertNull(status.summary(build))
        assertNull(status.brokenExtractorHint())
        assertEquals(setOf(appPath, extractorPath), paths.toSet(), "only the two release endpoints")
    }

    @Test fun `newer extractor release than the built-in commit`() {
        responses[extractorPath] = release("v0.27.0", "2026-11-10T08:00:00Z")
        val status = check()
        assertNull(status.appUpdate, "no app releases yet (404) is not an error")
        assertEquals("v0.27.0", status.newerExtractor?.tag)
        assertTrue(status.summary(build)!!.contains("65cabc2"))
        assertTrue(status.brokenExtractorHint()!!.contains("v0.27.0"))
    }

    @Test fun `a release on the pinned day itself is not newer`() {
        responses[extractorPath] = release("v0.26.6", "2026-10-05T20:00:00Z")
        assertNull(check().newerExtractor)
    }

    @Test fun `newer app build wins the hint`() {
        responses[appPath] = release("v1.1", "2026-11-12T08:00:00Z")
        responses[extractorPath] = release("v0.27.0", "2026-11-10T08:00:00Z")
        val status = check()
        assertEquals("v1.1", status.appUpdate?.tag)
        assertTrue(status.brokenExtractorHint()!!.contains("v1.1"))
        assertNotNull(status.summary(build))
    }

    @Test fun `prereleases are ignored`() {
        responses[appPath] = release("v2.0-beta", "2026-11-12T08:00:00Z", prerelease = true)
        assertNull(check().appUpdate)
    }

    @Test fun `server errors and garbage throw so the last result is kept`() {
        responses[appPath] = MockResponse.Builder().code(403).body("rate limit").build()
        assertFailsWith<IOException> { check() }
        responses[appPath] = MockResponse.Builder().body("<html>").build()
        assertFailsWith<IOException> { check() }
    }

    @Test fun `version comparison`() {
        assertTrue(Versions.isNewer("v1.0.1", "1.0"))
        assertTrue(Versions.isNewer("1.10", "1.9"))
        assertFalse(Versions.isNewer("v1.0", "1.0.0"))
        assertFalse(Versions.isNewer("0.9", "1.0"))
        assertFalse(Versions.isNewer("v1.0-rc1", "1.0"))
    }

    @Test fun `at most once a day`() {
        val day = UpdateChecker.INTERVAL_MS
        assertTrue(UpdateChecker.isDue(0, 1_000))
        assertFalse(UpdateChecker.isDue(10 * day, 10 * day + day - 1))
        assertTrue(UpdateChecker.isDue(10 * day, 11 * day))
        assertTrue(UpdateChecker.isDue(10 * day, 9 * day), "clock moved back")
    }
}
