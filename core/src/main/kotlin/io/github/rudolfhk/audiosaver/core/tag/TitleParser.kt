package io.github.rudolfhk.audiosaver.core.tag

/** Artist and title as they should appear in a music library. */
data class ParsedTitle(val artist: String?, val title: String)

/**
 * Turns a YouTube video title + channel name into a proper artist and song title:
 *
 *  - "Artist - Song (Official Video)" -> artist "Artist", title "Song"
 *  - noise is dropped: "(Official Music Video)", "[HD]", "(Lyrics)", "| Official Audio", "4K", ...
 *    while meaningful parts stay: "(feat. X)", "(Live)", "(Acoustic)", "(Remix)", "(Remastered 2009)"
 *  - `Artist "Song"` (quoted title) -> artist "Artist", title "Song"
 *  - "Artist - Topic" channels (YouTube's auto-generated label uploads) already have a clean song
 *    title: it isn't split, and the channel is the artist
 *  - otherwise the channel is the artist, without "VEVO"/"- Topic" suffixes
 */
object TitleParser {

    private val NOISE_WORDS = listOf(
        "official music video", "official video", "official audio", "official lyric video", "official lyrics video",
        "official visualizer", "official visualiser", "official hd video", "official 4k video", "official",
        "music video", "lyric video", "lyrics video", "with lyrics", "lyrics", "lyric", "visualizer", "visualiser",
        "audio only", "official audio video", "audio", "video", "video oficial", "videoclip oficial", "clip officiel",
        "offizielles video", "offizielles musikvideo", "hd", "hq", "4k", "8k", "1080p", "720p", "uhd", "mv", "m/v",
        "explicit", "clean", "full song", "full version",
    )

    /** "(Official Video)", "[HD]", "{Lyrics}", "【MV】" - only when the whole bracket is noise. */
    private val BRACKETED = Regex("""\s*[(\[{【]([^)\]}】]*)[)\]}】]""")

    /** Un-bracketed noise at the end: " | Official Video", " - Official Audio", " HD". */
    private val TRAILING = Regex(
        """\s*(?:[|\-–—/]\s*)?(?:${NOISE_WORDS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val SEPARATORS = listOf(" - ", " – ", " — ", " -- ", " ~ ")
    private val QUOTED = Regex("""^(.+?)\s*["“„«']\s*(.+?)\s*["”“»']\s*(.*)$""")

    fun parse(videoTitle: String, channel: String?): ParsedTitle {
        val topic = channel?.trim()?.endsWith(" - Topic") == true
        val channelArtist = channel?.let(::cleanChannel)
        val title = stripNoise(videoTitle)
        if (topic) return ParsedTitle(channelArtist, title)

        for (separator in SEPARATORS) {
            val at = title.indexOf(separator)
            if (at > 0) {
                val artist = title.substring(0, at).trim()
                val song = stripNoise(title.substring(at + separator.length))
                if (artist.isNotEmpty() && song.isNotEmpty()) return ParsedTitle(artist, song)
            }
        }
        QUOTED.matchEntire(title)?.let { m ->
            val artist = m.groupValues[1].trim().trimEnd('-', ':', '–').trim()
            val song = (m.groupValues[2] + " " + m.groupValues[3]).trim()
            if (artist.isNotEmpty() && song.isNotEmpty()) return ParsedTitle(artist, stripNoise(song))
        }
        return ParsedTitle(channelArtist, title)
    }

    /** Removes noise brackets and trailing noise; keeps the original when everything would go. */
    fun stripNoise(text: String): String {
        var result = BRACKETED.replace(text) { m -> if (isNoise(m.groupValues[1])) "" else m.value }
        while (true) {
            val stripped = TRAILING.replace(result, "")
            if (stripped == result || stripped.isBlank()) break
            result = stripped
        }
        result = result.replace(Regex("""\s{2,}"""), " ").trim().trimEnd('-', '|', '–', '—', ':').trim()
        return result.ifBlank { text.trim() }
    }

    private fun isNoise(inside: String): Boolean {
        val words = inside.lowercase().replace(Regex("""[^\p{L}\p{N}/ ]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (words.isEmpty()) return false
        // Every comma/ampersand separated part must be noise: "(Official Video & Lyrics)" yes, "(Live, 2019)" no.
        return words.split(Regex(""" (?:and|&|with|\+) |,""")).all { part -> part.trim() in NOISE_WORDS || part.trim().isEmpty() }
    }

    private fun cleanChannel(channel: String): String? =
        channel.trim()
            .removeSuffix(" - Topic")
            .replace(Regex("""\s*VEVO$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { null }
}
