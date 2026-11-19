package io.github.rudolfhk.audiosaver.core.audio

/** "1:23", "1:02:03", "83", "1:23.5" <-> milliseconds, for the trim fields. */
object TimeCodes {
    private val pattern = Regex("""^(?:(\d+):)?(?:(\d+):)?(\d+)(?:[.,](\d{1,3}))?$""")

    /** Null for blank input; throws [IllegalArgumentException] for anything unreadable. */
    fun parse(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val m = requireNotNull(pattern.matchEntire(t)) { "Use m:ss, e.g. 1:23" }
        val (a, b, c, frac) = m.destructured
        // With two colons: h:m:s; with one: m:s; with none: s.
        val (h, min) = if (b.isNotEmpty()) a.toLong() to b.toLong() else 0L to (a.ifEmpty { "0" }.toLong())
        val s = c.toLong()
        require(a.isEmpty() || s < 60) { "Seconds must be below 60" }
        require(b.isEmpty() || min < 60) { "Minutes must be below 60" }
        val ms = frac.padEnd(3, '0').ifEmpty { "0" }.toLong()
        return ((h * 60 + min) * 60 + s) * 1000 + ms
    }

    fun format(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        val rest = ms % 1000
        return if (rest == 0L) base else base + "." + "%03d".format(rest).trimEnd('0')
    }

    /** The trim range for two fields; null start = 0, null end = to the end. */
    fun range(start: String, end: String): TimeRange {
        val from = parse(start) ?: 0
        val to = parse(end)
        require(to == null || to > from) { "The end must be after the start" }
        return TimeRange(from, to)
    }
}
