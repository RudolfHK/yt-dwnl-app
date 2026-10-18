package io.github.rudolfhk.audiosaver.core.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class YouTubeLinkParserTest {

    private val id = "dQw4w9WgXcQ"

    private fun assertValid(input: String, expected: String = id) {
        val result = YouTubeLinkParser.parse(input)
        assertIs<LinkParseResult.Valid>(result, "expected '$input' to be valid but got $result")
        assertEquals(expected, result.videoId.value, "for input '$input'")
    }

    private fun assertInvalid(input: String?, reason: InvalidLinkReason) {
        val result = YouTubeLinkParser.parse(input)
        assertIs<LinkParseResult.Invalid>(result, "expected '$input' to be invalid")
        assertEquals(reason, result.reason, "for input '$input': ${result.message}")
    }

    @Test fun `standard watch urls`() {
        assertValid("https://www.youtube.com/watch?v=$id")
        assertValid("http://youtube.com/watch?v=$id")
        assertValid("https://m.youtube.com/watch?v=$id&feature=share")
        assertValid("https://music.youtube.com/watch?v=$id&si=abc")
        assertValid("https://www.youtube.com/watch?feature=youtu.be&v=$id&t=42s")
        assertValid("https://www.youtube.com/watch?v=$id&list=PL1234567890&index=3")
        assertValid("www.youtube.com/watch?v=$id")
        assertValid("youtube.com/watch?v=$id")
        assertValid("HTTPS://WWW.YOUTUBE.COM/watch?v=$id")
    }

    @Test fun `short links as produced by the YouTube app share sheet`() {
        assertValid("https://youtu.be/$id")
        assertValid("https://youtu.be/$id?si=Xy12AbCdEfGh-IjK")
        assertValid("youtu.be/$id?t=10")
        assertValid("https://youtu.be/$id/")
    }

    @Test fun `path style links`() {
        assertValid("https://www.youtube.com/shorts/$id")
        assertValid("https://youtube.com/shorts/$id?si=abc")
        assertValid("https://www.youtube.com/live/$id?feature=share")
        assertValid("https://www.youtube.com/embed/$id")
        assertValid("https://www.youtube-nocookie.com/embed/$id?rel=0")
        assertValid("https://www.youtube.com/v/$id")
    }

    @Test fun `links embedded in shared text`() {
        assertValid("Check this out: https://youtu.be/$id?si=abc")
        assertValid("Never Gonna Give You Up\nhttps://www.youtube.com/watch?v=$id")
        assertValid("(https://youtu.be/$id).")
        assertValid("  https://youtu.be/$id  \n")
    }

    @Test fun `bare id`() {
        assertValid(id)
        assertValid("  $id ")
        assertValid("a-b_c-d_e-f", "a-b_c-d_e-f")
    }

    @Test fun `empty and garbage`() {
        assertInvalid(null, InvalidLinkReason.EMPTY)
        assertInvalid("", InvalidLinkReason.EMPTY)
        assertInvalid("   ", InvalidLinkReason.EMPTY)
        assertInvalid("hello", InvalidLinkReason.NO_LINK_FOUND)
        assertInvalid("asdf qwer zxcv", InvalidLinkReason.NO_LINK_FOUND)
        assertInvalid("dQw4w9WgXc", InvalidLinkReason.NO_LINK_FOUND) // 10 chars
    }

    @Test fun `non youtube hosts`() {
        assertInvalid("https://vimeo.com/123456", InvalidLinkReason.NOT_YOUTUBE)
        assertInvalid("https://notyoutube.com/watch?v=$id", InvalidLinkReason.NOT_YOUTUBE)
        assertInvalid("https://youtube.com.evil.example/watch?v=$id", InvalidLinkReason.NOT_YOUTUBE)
    }

    @Test fun `malformed or missing ids`() {
        assertInvalid("https://www.youtube.com/watch?v=dQw4w9WgXc", InvalidLinkReason.MALFORMED_VIDEO_ID)
        assertInvalid("https://www.youtube.com/watch?v=dQw4w9WgXcQQ", InvalidLinkReason.MALFORMED_VIDEO_ID)
        assertInvalid("https://youtu.be/abc", InvalidLinkReason.MALFORMED_VIDEO_ID)
        assertInvalid("https://www.youtube.com/watch?v=dQw4w9WgX!Q", InvalidLinkReason.MALFORMED_VIDEO_ID)
        assertInvalid("https://www.youtube.com/watch", InvalidLinkReason.MISSING_VIDEO_ID)
        assertInvalid("https://youtu.be/", InvalidLinkReason.MISSING_VIDEO_ID)
        assertInvalid("https://www.youtube.com/shorts/", InvalidLinkReason.MISSING_VIDEO_ID)
    }

    @Test fun `playlists and other pages are rejected with a reason`() {
        assertInvalid("https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", InvalidLinkReason.PLAYLIST_ONLY)
        assertInvalid("https://www.youtube.com/watch?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", InvalidLinkReason.PLAYLIST_ONLY)
        assertInvalid("https://www.youtube.com/@SomeChannel", InvalidLinkReason.UNSUPPORTED_YOUTUBE_PAGE)
        assertInvalid("https://www.youtube.com/", InvalidLinkReason.UNSUPPORTED_YOUTUBE_PAGE)
        assertInvalid("https://www.youtube.com/results?search_query=music", InvalidLinkReason.UNSUPPORTED_YOUTUBE_PAGE)
    }

    @Test fun `invalid results explain why`() {
        val result = YouTubeLinkParser.parse("https://youtu.be/abc") as LinkParseResult.Invalid
        assertEquals(true, result.message.contains("3 characters"), result.message)
    }
}
