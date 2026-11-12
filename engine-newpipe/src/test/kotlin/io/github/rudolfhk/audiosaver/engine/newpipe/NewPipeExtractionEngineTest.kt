package io.github.rudolfhk.audiosaver.engine.newpipe

import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.CollectionKind
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewPipeExtractionEngineTest {

    @Test fun `extractor exceptions map to specific reasons and keep the original message`() {
        val cases = mapOf(
            AgeRestrictedContentException("This age-restricted video cannot be watched anonymously") to FailureReason.AGE_RESTRICTED,
            PrivateContentException("This video is private") to FailureReason.PRIVATE,
            GeographicRestrictionException("This video is not available in client's country.") to FailureReason.REGION_LOCKED,
            PaidContentException("This video is a paid video") to FailureReason.PAID_OR_MEMBERS_ONLY,
            SignInConfirmNotBotException("Sign in to confirm you're not a bot") to FailureReason.BOT_CHECK,
            ReCaptchaException("reCaptcha", "https://www.youtube.com") to FailureReason.RATE_LIMITED,
            ContentNotAvailableException("Got error ERROR: \"Video unavailable\"") to FailureReason.UNAVAILABLE,
            ParsingException("Could not get audio streams") to FailureReason.EXTRACTOR_BROKEN,
            SocketTimeoutException("timeout") to FailureReason.NETWORK,
            IllegalStateException("boom") to FailureReason.UNKNOWN,
        )
        for ((exception, expected) in cases) {
            val mapped = NewPipeExtractionEngine.mapFailure(exception)
            assertEquals(expected, mapped.reason, "for ${exception::class.simpleName}")
            assertTrue(mapped.detail.startsWith(exception.message!!), mapped.detail)
        }
    }

    @Test fun `extractor breakage names the engine version so the cause is traceable`() {
        val mapped = NewPipeExtractionEngine.mapFailure(ParsingException("Could not get audio streams"))
        assertTrue(mapped.detail.contains(NewPipeExtractionEngine.EXTRACTOR_VERSION), mapped.detail)
    }

    @Test fun `topic channels are cleaned up`() {
        assertEquals("Rick Astley", NewPipeExtractionEngine.cleanUploader("Rick Astley - Topic"))
        assertEquals("Rick Astley", NewPipeExtractionEngine.cleanUploader("Rick Astley"))
        assertNull(NewPipeExtractionEngine.cleanUploader(" - Topic"))
    }

    private fun stream(
        id: String,
        format: MediaFormat,
        bitrate: Int,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        track: AudioTrackType? = null,
    ) = AudioStream.Builder()
        .setId(id)
        .setContent("https://example.invalid/$id", true)
        .setMediaFormat(format)
        .setDeliveryMethod(delivery)
        .setAverageBitrate(bitrate)
        .setAudioTrackType(track)
        .build()

    @Test fun `prefers original-language M4A, then bitrate`() {
        val chosen = NewPipeExtractionEngine.chooseStream(
            listOf(
                stream("251", MediaFormat.WEBMA_OPUS, 160_000),
                stream("139", MediaFormat.M4A, 48_000),
                stream("140", MediaFormat.M4A, 128_000),
                stream("140-dub", MediaFormat.M4A, 256_000, track = AudioTrackType.DUBBED),
                stream("dash", MediaFormat.M4A, 512_000, delivery = DeliveryMethod.DASH),
            ),
        )
        assertEquals("140", chosen?.id)
    }

    @Test fun `falls back to opus when there is no m4a, and to nothing when nothing is progressive`() {
        assertEquals("251", NewPipeExtractionEngine.chooseStream(listOf(stream("251", MediaFormat.WEBMA_OPUS, 160_000)))?.id)
        assertNull(NewPipeExtractionEngine.chooseStream(listOf(stream("hls", MediaFormat.M4A, 1, delivery = DeliveryMethod.HLS))))
        assertNull(NewPipeExtractionEngine.chooseStream(emptyList()))
    }

    private fun info(url: String, name: String, type: StreamType = StreamType.VIDEO_STREAM, availability: ContentAvailability? = null) =
        StreamInfoItem(0, url, name, type).apply {
            uploaderName = "Rick Astley - Topic"
            duration = 213
            availability?.let { contentAvailability = it }
        }

    @Test fun `playlist entries become collection items`() {
        val item = NewPipeExtractionEngine.toCollectionItem(info("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ&index=1", "Never Gonna Give You Up"))!!
        assertEquals("dQw4w9WgXcQ", item.videoId.value)
        assertEquals("Rick Astley", item.uploader)
        assertEquals(213L, item.durationSeconds)
        assertTrue(item.available)
    }

    @Test fun `placeholders, live streams and paid entries are listed but not selectable`() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals(false, NewPipeExtractionEngine.toCollectionItem(info(url, "[Deleted video]"))!!.available)
        assertEquals(false, NewPipeExtractionEngine.toCollectionItem(info(url, "[Private video]"))!!.available)
        assertEquals(false, NewPipeExtractionEngine.toCollectionItem(info(url, "Live", StreamType.LIVE_STREAM))!!.available)
        assertEquals(false, NewPipeExtractionEngine.toCollectionItem(info(url, "Members", availability = ContentAvailability.MEMBERSHIP))!!.available)
        assertNull(NewPipeExtractionEngine.toCollectionItem(info("https://www.youtube.com/channel/UC123", "not a video")))
    }

    @Test fun `album titles lose youtube's prefix, other lists keep their name`() {
        assertEquals("Whenever You Need Somebody", NewPipeExtractionEngine.cleanCollectionTitle("Album - Whenever You Need Somebody", CollectionKind.ALBUM))
        assertEquals("Mix - Rick Astley", NewPipeExtractionEngine.cleanCollectionTitle("Mix - Rick Astley", CollectionKind.MIX))
        assertEquals("Album - Best of", NewPipeExtractionEngine.cleanCollectionTitle("Album - Best of", CollectionKind.PLAYLIST))
    }

    private fun video(
        format: MediaFormat,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        resolution: String = "720p",
        withItag: Boolean = true,
    ) = VideoStream.Builder()
        .setId("136")
        .setContent("https://example.invalid/136", true)
        .setMediaFormat(format)
        .setDeliveryMethod(delivery)
        .setResolution(resolution)
        .setIsVideoOnly(true)
        .apply { if (withItag) setItagItem(ItagItem.getItag(136).apply { contentLength = 83_132_227 }) }
        .build()

    @Test fun `only progressive mp4 video-only streams are offered to the core`() {
        val mapped = NewPipeExtractionEngine.toVideoStream(video(MediaFormat.MPEG_4))!!
        assertEquals("https://example.invalid/136", mapped.url)
        assertEquals(720, mapped.height)
        assertEquals(30, mapped.fps, "itag 136 is 720p30")
        assertEquals(83_132_227L, mapped.contentLength)
        assertNull(NewPipeExtractionEngine.toVideoStream(video(MediaFormat.WEBM)))
        assertNull(NewPipeExtractionEngine.toVideoStream(video(MediaFormat.MPEG_4, DeliveryMethod.DASH)))
    }

    @Test fun `without itag data the size comes from the quality label`() {
        val mapped = NewPipeExtractionEngine.toVideoStream(video(MediaFormat.MPEG_4, resolution = "1080p60", withItag = false))!!
        assertEquals(1080, mapped.height)
        assertEquals(60, mapped.fps)
        assertNull(mapped.contentLength)
        assertNull(NewPipeExtractionEngine.toVideoStream(video(MediaFormat.MPEG_4, resolution = "", withItag = false)), "no size at all")
    }

    @Test fun `visionOS stream urls are fetched like NewPipe's player first, with fallbacks`() {
        val visionOs = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&itag=137&c=VISIONOS&n=x"
        val variants = NewPipeExtractionEngine.requestVariantsFor(visionOs)
        assertEquals(3, variants.size)
        val first = variants.first()
        assertTrue(first.rangeAsQueryParameter && first.requestNumberParameter)
        assertTrue(first.postBody!!.contentEquals(byteArrayOf(0x78, 0)))
        assertTrue(first.headers["User-Agent"]!!.startsWith("com.google.visionos.youtube/"), first.headers.toString())
        assertTrue(variants[1].headers["User-Agent"]!!.startsWith("com.google.visionos.youtube/") && !variants[1].rangeAsQueryParameter)
        assertEquals(OkHttpNewPipeDownloader.USER_AGENT, variants.last().headers["User-Agent"])

        val other = NewPipeExtractionEngine.requestVariantsFor("https://rr1---sn-abc.googlevideo.com/videoplayback?itag=140&c=WEB")
        assertEquals(1, other.size)
        assertEquals(OkHttpNewPipeDownloader.USER_AGENT, other.single().headers["User-Agent"])
    }
}
