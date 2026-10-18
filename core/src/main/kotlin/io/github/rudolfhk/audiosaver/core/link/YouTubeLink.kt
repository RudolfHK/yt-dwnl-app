package io.github.rudolfhk.audiosaver.core.link

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** An 11-character YouTube video ID that has already passed validation. */
@JvmInline
value class VideoId private constructor(val value: String) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$value"

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

        fun isValid(candidate: String): Boolean = PATTERN.matches(candidate)

        fun of(candidate: String): VideoId {
            require(isValid(candidate)) { "Not a YouTube video ID: '$candidate'" }
            return VideoId(candidate)
        }
    }
}

sealed interface LinkParseResult {
    data class Valid(val videoId: VideoId) : LinkParseResult
    data class Invalid(val reason: InvalidLinkReason, val message: String) : LinkParseResult
}

enum class InvalidLinkReason {
    EMPTY,
    NO_LINK_FOUND,
    NOT_YOUTUBE,
    PLAYLIST_ONLY,
    UNSUPPORTED_YOUTUBE_PAGE,
    MISSING_VIDEO_ID,
    MALFORMED_VIDEO_ID,
}

/**
 * Turns whatever the user pasted or shared into a validated [VideoId] - or a specific reason why not -
 * without touching the network. This runs on every keystroke, so it must stay cheap and pure.
 *
 * Accepted:
 *  - youtube.com / www. / m. / music. / youtube-nocookie.com with /watch?v=ID, /shorts/ID, /live/ID,
 *    /embed/ID, /v/ID, /e/ID
 *  - youtu.be/ID (the format the YouTube app's Share button produces, usually with ?si=... appended)
 *  - a bare 11-character ID
 *  - any of the above embedded in a longer shared text ("Check this out: https://youtu.be/...")
 */
object YouTubeLinkParser {

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com",
    )
    private val SHORT_HOSTS = setOf("youtu.be", "www.youtu.be")
    private val PATH_PREFIXES = listOf("shorts", "live", "embed", "v", "e")

    // Scheme optional so that "youtube.com/watch?v=..." typed by hand still works.
    private val URL_IN_TEXT = Regex(
        """(?<![a-z0-9.@-])(?:https?://)?(?:[a-z0-9-]+\.)*(?:youtube\.com|youtube-nocookie\.com|youtu\.be)(?![a-z0-9.-])(?::\d+)?(?:[/?#][^\s<>"']*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val ANY_URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun parse(input: String?): LinkParseResult {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) {
            return invalid(InvalidLinkReason.EMPTY, "Paste or share a YouTube link.")
        }
        if (VideoId.isValid(text)) {
            return LinkParseResult.Valid(VideoId.of(text))
        }

        val candidate = URL_IN_TEXT.find(text)?.value
            ?: return ANY_URL_IN_TEXT.find(text)?.value
                ?.let { otherUrl -> invalid(InvalidLinkReason.NOT_YOUTUBE, "Not a YouTube link (${hostOf(otherUrl) ?: otherUrl}).") }
            ?: invalid(InvalidLinkReason.NO_LINK_FOUND, "No link found. Expected something like https://youtu.be/dQw4w9WgXcQ")

        return parseUrl(candidate.trimEnd('.', ',', ')', ']', '!', '?'))
    }

    private fun parseUrl(raw: String): LinkParseResult {
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val uri = try {
            URI(withScheme)
        } catch (e: URISyntaxException) {
            return invalid(InvalidLinkReason.NO_LINK_FOUND, "That link is malformed.")
        }
        val host = uri.host?.lowercase()
            ?: return invalid(InvalidLinkReason.NO_LINK_FOUND, "That link is malformed.")
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = parseQuery(uri.rawQuery)

        return when (host) {
            in SHORT_HOSTS -> {
                val id = segments.firstOrNull()
                    ?: return invalid(InvalidLinkReason.MISSING_VIDEO_ID, "This youtu.be link has no video ID.")
                validateId(id)
            }

            in YOUTUBE_HOSTS -> parseYouTubePath(segments, query)

            else -> invalid(InvalidLinkReason.NOT_YOUTUBE, "Not a YouTube link ($host).")
        }
    }

    private fun parseYouTubePath(segments: List<String>, query: Map<String, String>): LinkParseResult {
        val first = segments.firstOrNull()?.lowercase()
        if (first == "watch" || first == null && query.containsKey("v")) {
            val id = query["v"]
                ?: return if (query.containsKey("list")) playlistOnly()
                else invalid(InvalidLinkReason.MISSING_VIDEO_ID, "This watch link has no video ID (missing ?v=).")
            return validateId(id)
        }
        if (first in PATH_PREFIXES) {
            val id = segments.getOrNull(1)
                ?: return invalid(InvalidLinkReason.MISSING_VIDEO_ID, "This /$first/ link has no video ID.")
            return validateId(id)
        }
        if (first == "playlist") return playlistOnly()
        return invalid(
            InvalidLinkReason.UNSUPPORTED_YOUTUBE_PAGE,
            "That's a YouTube page, not a single video. Open a video and share that instead.",
        )
    }

    private fun validateId(id: String): LinkParseResult =
        if (VideoId.isValid(id)) {
            LinkParseResult.Valid(VideoId.of(id))
        } else {
            invalid(
                InvalidLinkReason.MALFORMED_VIDEO_ID,
                "\"$id\" isn't a valid video ID (expected 11 letters, digits, - or _; got ${id.length} characters).",
            )
        }

    private fun playlistOnly() = invalid(
        InvalidLinkReason.PLAYLIST_ONLY,
        "Playlist links aren't supported. Share a single video instead.",
    )

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isEmpty() || !pair.contains('=')) return@mapNotNull null
            decode(key) to decode(pair.substringAfter('='))
        }.toMap()

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, Charsets.UTF_8.name())
    } catch (e: IllegalArgumentException) {
        s
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (e: URISyntaxException) {
        null
    }

    private fun invalid(reason: InvalidLinkReason, message: String) = LinkParseResult.Invalid(reason, message)
}
