package io.github.rudolfhk.audiosaver.core.opus

import io.github.rudolfhk.audiosaver.core.audio.TimeRange
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class OpusRemuxException(message: String) : IOException(message)

/**
 * Turns YouTube's Opus stream (WebM/Matroska, itag 249-251) into a standard Ogg Opus `.opus` file
 * (RFC 7845) - the same Opus packets, nothing re-encoded - with Vorbis-comment tags, the cover as
 * `METADATA_BLOCK_PICTURE` and loudness as `R128_TRACK_GAIN`.
 *
 * Granule positions are computed from each packet's TOC byte (not from the container's millisecond
 * timestamps), the encoder delay comes over as the header's pre-skip and WebM's DiscardPadding as
 * end trimming, so the decoded audio is sample-identical to the original. An optional [TimeRange]
 * cuts at packet boundaries (≤ 20 ms) with 80 ms of decoder pre-roll, as RFC 7845 recommends.
 */
object WebmOpusRemuxer {

    private const val SAMPLE_RATE = 48_000L
    private const val PRE_ROLL = 3_840L // 80 ms
    private const val MAX_PRE_SKIP = 65_535L

    fun remux(input: File, output: File, metadata: TrackMetadata?, range: TimeRange = TimeRange.ALL) {
        val webm = RandomAccessFile(input, "r").use { WebmReader(it).read() }
        if (webm.packets.isEmpty()) throw OpusRemuxException("The Opus stream contains no audio")
        val head = webm.opusHead.copyOf()
        if (head.size < 19 || String(head, 0, 8, Charsets.ISO_8859_1) != "OpusHead") throw OpusRemuxException("Missing OpusHead")
        val preSkip = (ByteBuffer.wrap(head, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF).toLong()

        val durations = webm.packets.map { packetSamples(it.data) }
        val starts = LongArray(durations.size) // playback position of each packet start, pre-skip removed
        var cumulative = -preSkip
        durations.forEachIndexed { i, d -> starts[i] = cumulative; cumulative += d }
        val totalPlayable = cumulative - webm.endPaddingSamples

        val startSample = range.startMs * SAMPLE_RATE / 1000
        val endSample = (range.endMs?.let { it * SAMPLE_RATE / 1000 } ?: totalPlayable).coerceAtMost(totalPlayable)
        if (endSample <= startSample) throw OpusRemuxException("Nothing left after trimming")
        val first = if (startSample <= 0) 0 else durations.indices.first { starts[it] + durations[it] > startSample - PRE_ROLL }
        val last = durations.indices.last { starts[it] < endSample }
        val newPreSkip = (startSample - starts[first]).coerceIn(0, MAX_PRE_SKIP)
        ByteBuffer.wrap(head, 10, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(newPreSkip.toShort())

        val keptTotal = (first..last).sumOf { durations[it] }
        val finalGranule = minOf(keptTotal, newPreSkip + (endSample - maxOf(startSample, starts[first] + newPreSkip)))

        BufferedOutputStream(output.outputStream(), 256 * 1024).use { out ->
            val ogg = OggWriter(out, serial = (System.nanoTime() and 0x7FFFFFFF).toInt())
            ogg.packet(head, granule = 0, bos = true)
            ogg.flush()
            ogg.packet(opusTags(metadata), granule = 0)
            ogg.flush()
            var granule = 0L
            for (i in first..last) {
                granule += durations[i]
                val isLast = i == last
                ogg.packet(webm.packets[i].data, granule = if (isLast) finalGranule else granule, eos = isLast)
                if (isLast) ogg.flush() else ogg.flushIfFull()
            }
        }
    }

    /** Samples (at 48 kHz) in one Opus packet, from its TOC byte (RFC 6716 section 3.1). */
    internal fun packetSamples(packet: ByteArray): Long {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSamples = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3] // SILK 10/20/40/60 ms
            config < 16 -> intArrayOf(480, 960)[config and 1] // hybrid 10/20 ms
            else -> intArrayOf(120, 240, 480, 960)[config and 3] // CELT 2.5/5/10/20 ms
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else throw OpusRemuxException("Truncated Opus packet")
        }
        return frameSamples.toLong() * frames
    }

    /** OpusTags: vendor string + Vorbis comments (RFC 7845 section 5.2). */
    internal fun opusTags(metadata: TrackMetadata?): ByteArray {
        val comments = mutableListOf<String>()
        fun add(key: String, value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { comments += "$key=$it" }
        }
        if (metadata != null) {
            add("TITLE", metadata.title)
            add("ARTIST", metadata.artist)
            add("ALBUMARTIST", metadata.albumArtist ?: metadata.artist)
            add("ALBUM", metadata.album)
            add("DATE", metadata.year?.toString())
            add("TRACKNUMBER", metadata.trackNumber?.toString())
            add("TRACKTOTAL", metadata.trackTotal?.toString())
            add("COMMENT", metadata.comment)
            // RFC 7845: Ogg Opus uses R128_TRACK_GAIN (relative to -23 LUFS), never REPLAYGAIN_*.
            add("R128_TRACK_GAIN", metadata.replayGain?.r128TrackGain?.toString())
            metadata.cover?.let { cover -> add("METADATA_BLOCK_PICTURE", Base64.getEncoder().encodeToString(flacPicture(cover.jpeg, cover.width, cover.height))) }
        }
        val vendor = "Audio Saver".toByteArray(Charsets.UTF_8)
        val encoded = comments.map { it.toByteArray(Charsets.UTF_8) }
        val buffer = ByteBuffer.allocate(8 + 4 + vendor.size + 4 + encoded.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("OpusTags".toByteArray(Charsets.ISO_8859_1)).putInt(vendor.size).put(vendor).putInt(encoded.size)
        encoded.forEach { buffer.putInt(it.size).put(it) }
        return buffer.array()
    }

    /** A FLAC METADATA_BLOCK_PICTURE (front cover), the standard way to put art in Vorbis comments. */
    internal fun flacPicture(jpeg: ByteArray, width: Int, height: Int): ByteArray {
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(4 + 4 + mime.size + 4 + 4 * 4 + 4 + jpeg.size)
            .putInt(3).putInt(mime.size).put(mime).putInt(0)
            .putInt(width).putInt(height).putInt(24).putInt(0)
            .putInt(jpeg.size).put(jpeg).array()
    }

    // ---------------------------------------------------------------- WebM reading

    private class Packet(val data: ByteArray)

    private class Webm(val opusHead: ByteArray, val packets: List<Packet>, val endPaddingSamples: Long)

    /** Just enough Matroska/EBML to pull one Opus track's frames out of a WebM file. */
    private class WebmReader(private val raf: RandomAccessFile) {
        private val length = raf.length()
        private var opusHead: ByteArray? = null
        private var trackNumber = -1L
        private var timecodeScale = 1_000_000L
        private val packets = ArrayList<Packet>()
        private var endPadding = 0L

        fun read(): Webm {
            val header = element(0) ?: throw OpusRemuxException("Not a WebM file")
            if (header.id != 0x1A45DFA3L) throw OpusRemuxException("Not a WebM file")
            var pos = header.end
            while (pos < length) {
                val e = element(pos) ?: break
                if (e.id == 0x18538067L) readSegment(e)
                pos = e.end
            }
            return Webm(opusHead ?: throw OpusRemuxException("The WebM file has no Opus track"), packets, endPadding)
        }

        private fun readSegment(segment: Element) {
            forEachChild(segment) { e ->
                when (e.id) {
                    0x1549A966L -> forEachChild(e) { if (it.id == 0x2AD7B1L) timecodeScale = uint(it) } // Info / TimecodeScale
                    0x1654AE6BL -> forEachChild(e) { if (it.id == 0xAEL) readTrack(it) } // Tracks / TrackEntry
                    0x1F43B675L -> readCluster(e)
                }
            }
        }

        private fun readTrack(entry: Element) {
            var number = -1L
            var codec = ""
            var private: ByteArray? = null
            forEachChild(entry) { e ->
                when (e.id) {
                    0xD7L -> number = uint(e)
                    0x86L -> codec = String(bytes(e), Charsets.US_ASCII)
                    0x63A2L -> private = bytes(e)
                }
            }
            if (codec == "A_OPUS" && opusHead == null) {
                trackNumber = number
                opusHead = private ?: throw OpusRemuxException("Opus track without OpusHead")
            }
        }

        private fun readCluster(cluster: Element) {
            forEachChild(cluster) { e ->
                when (e.id) {
                    0xA3L -> readBlock(bytes(e)) // SimpleBlock
                    0xA0L -> { // BlockGroup: Block + optional DiscardPadding (ns, signed)
                        var block: ByteArray? = null
                        var discardNs = 0L
                        forEachChild(e) { g ->
                            when (g.id) {
                                0xA1L -> block = bytes(g)
                                0x75A2L -> discardNs = sint(g)
                            }
                        }
                        block?.let { if (readBlock(it)) endPadding = discardNs.coerceAtLeast(0) * SAMPLE_RATE / 1_000_000_000L }
                    }
                }
            }
        }

        /** Returns true if the block belonged to the Opus track. */
        private fun readBlock(block: ByteArray): Boolean {
            val (track, trackLength) = vint(block, 0)
            if (track != trackNumber || opusHead == null) return false
            val flags = block[trackLength + 2].toInt()
            if (flags and 0x06 != 0) throw OpusRemuxException("Laced WebM blocks are not supported")
            packets += Packet(block.copyOfRange(trackLength + 3, block.size))
            return true
        }

        private class Element(val id: Long, val dataStart: Long, val end: Long)

        private fun element(pos: Long): Element? {
            if (pos >= length) return null
            raf.seek(pos)
            val first = raf.read()
            if (first <= 0) throw OpusRemuxException("Corrupt WebM element at $pos")
            val idLength = Integer.numberOfLeadingZeros(first) - 23
            if (idLength !in 1..4) throw OpusRemuxException("Corrupt WebM element id at $pos")
            var id = first.toLong()
            repeat(idLength - 1) { id = (id shl 8) or raf.read().toLong() }
            val sizeFirst = raf.read()
            val sizeLength = Integer.numberOfLeadingZeros(sizeFirst) - 23
            if (sizeLength !in 1..8) throw OpusRemuxException("Corrupt WebM element size at $pos")
            var size = (sizeFirst and (0xFF shr sizeLength)).toLong()
            var allOnes = size == (0xFF shr sizeLength).toLong()
            repeat(sizeLength - 1) {
                val b = raf.read()
                size = (size shl 8) or b.toLong()
                if (b != 0xFF) allOnes = false
            }
            val dataStart = pos + idLength + sizeLength
            // Unknown size (live recordings): runs to the end of the file.
            val end = if (allOnes) length else dataStart + size
            if (end > length) throw OpusRemuxException("The WebM file is truncated")
            return Element(id, dataStart, end)
        }

        private inline fun forEachChild(parent: Element, block: (Element) -> Unit) {
            var pos = parent.dataStart
            while (pos < parent.end) {
                val e = element(pos) ?: break
                block(e)
                pos = e.end
            }
        }

        private fun bytes(e: Element): ByteArray {
            val size = e.end - e.dataStart
            if (size > 64L * 1024 * 1024) throw OpusRemuxException("Implausibly large WebM element")
            raf.seek(e.dataStart)
            return ByteArray(size.toInt()).also { raf.readFully(it) }
        }

        private fun uint(e: Element): Long = bytes(e).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

        private fun sint(e: Element): Long {
            val b = bytes(e)
            if (b.isEmpty()) return 0
            var v = b[0].toLong() // sign-extended
            for (i in 1 until b.size) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }

        /** EBML variable-length integer (marker bit removed) and its length. */
        private fun vint(buf: ByteArray, at: Int): Pair<Long, Int> {
            val first = buf[at].toInt() and 0xFF
            val len = Integer.numberOfLeadingZeros(first) - 23
            if (len !in 1..8) throw OpusRemuxException("Corrupt block header")
            var v = (first and (0xFF shr len)).toLong()
            for (i in 1 until len) v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
            return v to len
        }
    }

    // ---------------------------------------------------------------- Ogg writing

    /** Ogg pages (RFC 3533): packets split into 255-byte lacing values, pages of ~4 KiB. */
    private class OggWriter(private val out: OutputStream, private val serial: Int) {
        private val lacing = ArrayList<Int>()
        private val data = java.io.ByteArrayOutputStream()
        private var sequence = 0
        private var pageGranule = -1L // granule of the last packet completed on the current page
        private var continued = false // current page starts in the middle of a packet
        private var bos = false
        private var eos = false

        fun packet(packet: ByteArray, granule: Long, bos: Boolean = false, eos: Boolean = false) {
            if (bos) this.bos = true
            var offset = 0
            while (true) {
                val chunk = minOf(255, packet.size - offset)
                if (lacing.size == 255) emit(midPacket = offset > 0)
                lacing += chunk
                data.write(packet, offset, chunk)
                offset += chunk
                if (chunk < 255) break // a lacing value below 255 ends the packet (0 for exact multiples)
            }
            pageGranule = granule
            if (eos) this.eos = true
        }

        fun flushIfFull() {
            if (data.size() >= 4096 || lacing.size >= 240) flush()
        }

        fun flush() {
            if (lacing.isNotEmpty()) emit(midPacket = false)
        }

        private fun emit(midPacket: Boolean) {
            val body = data.toByteArray()
            val headerType = (if (continued) 0x01 else 0) or (if (bos) 0x02 else 0) or (if (eos && !midPacket) 0x04 else 0)
            val header = ByteBuffer.allocate(27 + lacing.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("OggS".toByteArray(Charsets.ISO_8859_1)).put(0).put(headerType.toByte())
                .putLong(pageGranule) // -1 when no packet ends on this page
                .putInt(serial).putInt(sequence++).putInt(0).put(lacing.size.toByte())
            lacing.forEach { header.put(it.toByte()) }
            val page = header.array() + body
            val crc = crc32(page)
            ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
            out.write(page)
            continued = midPacket
            bos = false
            lacing.clear()
            data.reset()
            pageGranule = -1
        }

        companion object {
            private val TABLE = IntArray(256) { i ->
                var r = i shl 24
                repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
                r
            }

            /** Ogg's CRC-32: polynomial 0x04C11DB7, MSB first, no reflection, initial value 0. */
            fun crc32(bytes: ByteArray): Int {
                var crc = 0
                for (b in bytes) crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
                return crc
            }
        }
    }
}
