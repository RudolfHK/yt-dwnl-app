package io.github.rudolfhk.audiosaver.core.mp3

import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ID3 tags for the MP3 export. ID3v2.3 (UTF-16 text) rather than v2.4: it's the version old car
 * stereos and players - the reason to export MP3 at all - actually read. Plus a 128-byte ID3v1 tag
 * at the end for the very oldest ones.
 */
object Id3Writer {

    /** ID3v2.3 tag to put in front of the MP3 frames. */
    fun v2(metadata: TrackMetadata): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(ByteBuffer.allocate(4).putInt(body.size).array()) // v2.3: plain 32-bit size
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        fun text(id: String, value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { frame(id, byteArrayOf(1) + utf16(it)) }
        }
        text("TIT2", metadata.title)
        text("TPE1", metadata.artist)
        text("TPE2", metadata.albumArtist ?: metadata.artist)
        text("TALB", metadata.album)
        text("TYER", metadata.year?.toString())
        text("TRCK", metadata.trackNumber?.let { n -> metadata.trackTotal?.let { "$n/$it" } ?: "$n" })
        metadata.comment?.let { frame("COMM", byteArrayOf(1) + "eng".toByteArray(Charsets.ISO_8859_1) + utf16("") + byteArrayOf(0, 0) + utf16(it)) }
        metadata.replayGain?.let { rg ->
            fun txxx(name: String, value: String) = frame("TXXX", byteArrayOf(1) + utf16(name) + byteArrayOf(0, 0) + utf16(value))
            txxx("REPLAYGAIN_TRACK_GAIN", rg.gainTag)
            txxx("REPLAYGAIN_TRACK_PEAK", rg.peakTag)
        }
        metadata.cover?.let { cover ->
            // encoding 0 (Latin-1 description), MIME, picture type 3 = front cover, empty description
            frame("APIC", byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + cover.jpeg)
        }
        val body = frames.toByteArray()
        return "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0) + syncSafe(body.size) + body
    }

    /** ID3v1.1: fixed 128 bytes, Latin-1, truncated fields. */
    fun v1(metadata: TrackMetadata): ByteArray {
        fun field(value: String?, length: Int): ByteArray {
            val latin1 = (value ?: "").map { if (it.code in 32..255) it else '?' }.joinToString("").toByteArray(Charsets.ISO_8859_1)
            return latin1.copyOf(length)
        }
        return "TAG".toByteArray(Charsets.ISO_8859_1) +
            field(metadata.title, 30) + field(metadata.artist, 30) + field(metadata.album, 30) +
            field(metadata.year?.toString(), 4) + field(null, 28) + byteArrayOf(0, (metadata.trackNumber ?: 0).coerceIn(0, 255).toByte(), -1)
    }

    /** UTF-16 with a byte-order mark, as ID3v2.3 encoding 1 requires. */
    private fun utf16(text: String) = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    private fun syncSafe(size: Int) = byteArrayOf(
        ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
    )
}
