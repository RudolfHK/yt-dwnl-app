package io.github.rudolfhk.audiosaver.engine.newpipe

import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Asks YouTube's stream servers for the first 64 KiB of the same audio and video stream in several
 * request styles and prints which ones work. Run with LIVE_YOUTUBE=1. This is how the request style
 * the downloader uses was chosen - re-run it when downloads start failing with HTTP 403.
 */
class LiveStreamRequestDiagnostic {

    @Before fun onlyWhenRequested() = assumeTrue("set LIVE_YOUTUBE=1 to run", System.getenv("LIVE_YOUTUBE") == "1")

    private val client = OkHttpClient()
    private val firefox = OkHttpNewPipeDownloader.USER_AGENT
    private val visionOs = YoutubeParsingHelper.getVisionOsUserAgent(null)

    private data class Style(val name: String, val build: (url: String) -> Request)

    private val styles = listOf(
        Style("GET + Range header + Firefox UA (app before this fix)") { url ->
            Request.Builder().url(url).header("User-Agent", firefox).header("Range", "bytes=0-65535").build()
        },
        Style("GET + Range header + visionOS UA") { url ->
            Request.Builder().url(url).header("User-Agent", visionOs).header("Range", "bytes=0-65535").build()
        },
        Style("GET + &range= param + visionOS UA") { url ->
            Request.Builder().url("$url&range=0-65535").header("User-Agent", visionOs).build()
        },
        Style("POST x\\0 + &range= + &rn=1 + visionOS UA (NewPipe player)") { url ->
            Request.Builder().url("$url&rn=1&range=0-65535").header("User-Agent", visionOs)
                .post(byteArrayOf(0x78, 0).toRequestBody()).build()
        },
    )

    @Test fun `which request styles do youtube's stream servers accept`() = runBlocking {
        val audio = NewPipeExtractionEngine(client).resolve(VideoId.of("aqz-KE-bpKQ"))
        val video = VideoQuality.P360.pick(audio.videoStreams)
        val report = StringBuilder()
        report.appendLine("visionOS stream URLs: audio=${YoutubeParsingHelper.isVisionOsStreamingUrl(audio.stream.url)}, video=${video?.let { YoutubeParsingHelper.isVisionOsStreamingUrl(it.url) }}")
        report.appendLine("video streams offered: " + audio.videoStreams.joinToString { "${it.height}p${it.fps} ${it.codec} ${it.contentLength}" })
        var anyWorks = false
        for ((label, url) in listOfNotNull("audio" to audio.stream.url, video?.let { "video ${it.height}p" to it.url })) {
            for (style in styles) {
                val line = runCatching {
                    client.newCall(style.build(url)).execute().use { r ->
                        val bytes = r.body.bytes()
                        val head = String(bytes.copyOfRange(4, minOf(8, bytes.size).coerceAtLeast(4)), Charsets.ISO_8859_1)
                        val ok = r.code in 200..299 && bytes.size >= 1000 && head == "ftyp"
                        if (ok) anyWorks = true
                        "${if (ok) "OK  " else "FAIL"} HTTP ${r.code} ${bytes.size} bytes, box '$head'"
                    }
                }.getOrElse { "FAIL ${it::class.simpleName}: ${it.message}" }
                report.appendLine("$label | ${style.name} | $line")
            }
        }
        println(report)
        System.getenv("LIVE_OUTPUT_DIR")?.let { File(it).apply { mkdirs() }.resolve("request-styles.txt").writeText(report.toString()) }
        assertTrue(anyWorks, "no request style worked:\n$report")
    }
}
