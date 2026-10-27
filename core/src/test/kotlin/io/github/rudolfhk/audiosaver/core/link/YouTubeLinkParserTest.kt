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

    private fun assertCollection(input: String, kind: CollectionKind, listId: String, seed: String?): CollectionRef {
        val result = YouTubeLinkParser.parse(input)
        assertIs<LinkParseResult.Collection>(result, "expected '$input' to be a collection but got $result")
        assertEquals(kind, result.ref.kind, "kind for '$input'")
        assertEquals(listId, result.ref.listId, "list id for '$input'")
        assertEquals(seed, result.ref.seedVideoId?.value, "seed for '$input'")
        return result.ref
    }

    @Test fun `mix links`() {
        // "Copy link" while a mix plays: video + list
        assertCollection("https://www.youtube.com/watch?v=$id&list=RD$id&start_radio=1", CollectionKind.MIX, "RD$id", id)
        assertCollection("https://youtube.com/watch?v=abcdefghijk&list=RD$id&index=4", CollectionKind.MIX, "RD$id", "abcdefghijk")
        // Share sheet inside a mix
        assertCollection("https://youtu.be/$id?list=RD$id&si=Xy12", CollectionKind.MIX, "RD$id", id)
        // List only: the seed is derived from the mix id
        assertCollection("https://www.youtube.com/playlist?list=RD$id", CollectionKind.MIX, "RD$id", id)
        assertCollection("https://www.youtube.com/watch?list=RDMM$id", CollectionKind.MIX, "RDMM$id", id)
        assertCollection("https://music.youtube.com/watch?v=$id&list=RDAMVM$id", CollectionKind.MUSIC_MIX, "RDAMVM$id", id)
        // Mix whose id isn't based on a song (genre mixes): no seed
        assertCollection("https://www.youtube.com/playlist?list=RDGMEMQ1dJ7wXfLlqCjwV0xfSNbA", CollectionKind.MIX, "RDGMEMQ1dJ7wXfLlqCjwV0xfSNbA", null)
        assertCollection("Mix - Rick Astley\nhttps://www.youtube.com/watch?v=$id&list=RD$id", CollectionKind.MIX, "RD$id", id)
    }

    @Test fun `album and playlist links`() {
        val album = "OLAK5uy_kpkiHPDtH4uw0Ri5BqO6pIGVnHbbYzr6c"
        assertCollection("https://music.youtube.com/playlist?list=$album", CollectionKind.ALBUM, album, null)
        assertCollection("https://www.youtube.com/playlist?list=$album&si=abc", CollectionKind.ALBUM, album, null)
        assertCollection("https://www.youtube.com/watch?v=$id&list=$album&index=2", CollectionKind.ALBUM, album, id)
        val pl = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"
        assertCollection("https://www.youtube.com/playlist?list=$pl", CollectionKind.PLAYLIST, pl, null)
        assertCollection("https://m.youtube.com/watch?v=$id&list=$pl&index=3", CollectionKind.PLAYLIST, pl, id)
        val ref = assertCollection("https://www.youtube.com/watch?list=$pl", CollectionKind.PLAYLIST, pl, null)
        assertEquals("https://www.youtube.com/playlist?list=$pl", ref.url)
    }

    @Test fun `private or broken lists`() {
        assertInvalid("https://www.youtube.com/playlist?list=LL", InvalidLinkReason.PRIVATE_LIST)
        assertInvalid("https://www.youtube.com/playlist?list=WL", InvalidLinkReason.PRIVATE_LIST)
        assertInvalid("https://www.youtube.com/playlist?list=abc", InvalidLinkReason.MALFORMED_LIST_ID)
        assertInvalid("https://www.youtube.com/playlist", InvalidLinkReason.MALFORMED_LIST_ID)
        // ...but a video link that merely carries such a list still works as a single video
        assertValid("https://www.youtube.com/watch?v=$id&list=WL&index=5")
        assertValid("https://www.youtube.com/watch?v=$id&list=LL")
        assertValid("https://www.youtube.com/watch?v=$id&list=x!")
    }

    @Test fun `other pages are rejected with a reason`() {
        assertInvalid("https://www.youtube.com/@SomeChannel", InvalidLinkReason.UNSUPPORTED_YOUTUBE_PAGE)
        assertInvalid("https://www.youtube.com/", InvalidLinkReason.UNSUPPORTED_YOUTUBE_PAGE)
        assertInvalid("https://www.youtube.com/results?search_query=music", InvalidLinkReason.UNSUPPORTED_YOUTUBE_PAGE)
    }

    @Test fun `invalid results explain why`() {
        val result = YouTubeLinkParser.parse("https://youtu.be/abc") as LinkParseResult.Invalid
        assertEquals(true, result.message.contains("3 characters"), result.message)
    }
}
