package io.github.rudolfhk.audiosaver.core.tag

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

data class TrackMetadata(
    val title: String,
    val artist: String?,
    val year: Int?,
    val comment: String?,
)

class TaggingException(message: String) : IOException(message)

/**
 * Embeds iTunes-style metadata (`moov/udta/meta/ilst`) into an MP4/M4A audio file, which is what
 * Android's MediaStore scanner, Navidrome/TagLib, foobar2000, iTunes etc. read for M4A. (ID3 is the
 * MP3 equivalent and is not read from MP4 files.)
 *
 * Why not just use a library or FFmpeg: YouTube's audio-only streams are DASH *fragmented* MP4
 * (`ftyp`=dash, `moov`, `sidx`, then `moof`/`mdat` pairs). Growing `moov` shifts every byte after it,
 * so any absolute file offset pointing past `moov` has to be fixed up. That is a small, well-defined
 * job, done here in one streaming pass without decoding or re-encoding any audio:
 *
 *  - `stco`/`co64` chunk offsets (regular, non-fragmented MP4)
 *  - `tfhd.base_data_offset` (fragments that use explicit base offsets)
 *  - `tfra.moof_offset` (the optional `mfra` random-access index)
 *
 * `sidx` and `trun` offsets are relative and unaffected. Additionally the DASH `ftyp` major brand is
 * rewritten to `M4A ` (same box size) because some players refuse files branded `dash`.
 */
object Mp4Tagger {

    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
    private val CONTAINERS_WITH_OFFSETS = setOf("trak", "mdia", "minf", "stbl")

    fun tag(input: File, output: File, metadata: TrackMetadata) {
        RandomAccessFile(input, "r").use { raf ->
            val boxes = readTopLevelBoxes(raf)
            val ftyp = boxes.firstOrNull()?.takeIf { it.type == "ftyp" }
                ?: throw TaggingException("Not an MP4 file (no leading ftyp box)")
            val moov = boxes.singleOrNull { it.type == "moov" }
                ?: throw TaggingException("MP4 file has no (or several) moov boxes")
            if (moov.size > MAX_MOOV_BYTES) throw TaggingException("moov box unreasonably large: ${moov.size}")

            val oldMoovEnd = moov.offset + moov.size
            val moovPayload = raf.readAt(moov.offset + moov.headerSize, (moov.size - moov.headerSize).toInt())
            val newMoovPayload = rebuildMoovPayload(moovPayload, metadata)
            val newMoovSize = 8L + newMoovPayload.size
            if (newMoovSize > UInt.MAX_VALUE.toLong()) throw TaggingException("moov too large after tagging")
            val delta = newMoovSize - moov.size

            // Patch absolute offsets inside the moov itself (stco/co64) now that delta is known.
            patchChunkOffsets(newMoovPayload, 0, newMoovPayload.size, oldMoovEnd, delta)

            BufferedOutputStream(output.outputStream(), 256 * 1024).use { out ->
                for (box in boxes) {
                    when {
                        box === ftyp -> out.write(patchFtyp(raf.readAt(box.offset, box.size.toInt())))
                        box === moov -> {
                            out.writeU32(newMoovSize)
                            out.write("moov".toByteArray(Charsets.ISO_8859_1))
                            out.write(newMoovPayload)
                        }
                        box.offset >= oldMoovEnd && box.type == "moof" ->
                            out.write(patchMoof(raf.readAt(box.offset, box.size.toInt()), oldMoovEnd, delta))
                        box.offset >= oldMoovEnd && box.type == "mfra" ->
                            out.write(patchMfra(raf.readAt(box.offset, box.size.toInt()), oldMoovEnd, delta))
                        else -> raf.copyRange(box.offset, box.size, out)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- box scanning

    private class Box(val type: String, val offset: Long, val headerSize: Int, val size: Long)

    private fun readTopLevelBoxes(raf: RandomAccessFile): List<Box> {
        val boxes = mutableListOf<Box>()
        val fileLength = raf.length()
        var pos = 0L
        while (pos < fileLength) {
            if (fileLength - pos < 8) throw TaggingException("Truncated box header at $pos")
            raf.seek(pos)
            var size = raf.readInt().toUInt().toLong()
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            when (size) {
                1L -> {
                    size = raf.readLong(); header = 16
                }
                0L -> size = fileLength - pos
            }
            if (size < header || pos + size > fileLength) {
                throw TaggingException("Invalid size $size for box '$type' at $pos (file is $fileLength bytes)")
            }
            boxes += Box(type, pos, header, size)
            pos += size
        }
        return boxes
    }

    /** Iterates the child boxes inside buf[start, end). */
    private inline fun forEachChild(buf: ByteArray, start: Int, end: Int, block: (type: String, offset: Int, header: Int, size: Int) -> Unit) {
        var pos = start
        while (pos + 8 <= end) {
            var size = buf.u32(pos)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = buf.u64(pos + 8); header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) throw TaggingException("Invalid child box '$type' at $pos")
            block(type, pos, header, size.toInt())
            pos += size.toInt()
        }
    }

    // ---------------------------------------------------------------- moov / udta

    private fun rebuildMoovPayload(payload: ByteArray, metadata: TrackMetadata): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 512)
        var keptUdtaChildren = ByteArray(0)
        forEachChild(payload, 0, payload.size) { type, offset, header, size ->
            if (type == "udta") {
                // Keep foreign udta children (chapters etc.), drop any previous iTunes meta.
                val kept = ByteArrayOutputStream()
                forEachChild(payload, offset + header, offset + size) { childType, childOffset, _, childSize ->
                    if (childType != "meta") kept.write(payload, childOffset, childSize)
                }
                keptUdtaChildren = kept.toByteArray()
            } else {
                out.write(payload, offset, size)
            }
        }
        out.write(box("udta", keptUdtaChildren + buildMeta(metadata)))
        return out.toByteArray()
    }

    private fun buildMeta(metadata: TrackMetadata): ByteArray {
        val hdlr = box(
            "hdlr",
            ByteArray(4) + // version + flags
                ByteArray(4) + // pre_defined
                "mdir".latin1() +
                "appl".latin1() + ByteArray(8) + // reserved
                byteArrayOf(0), // empty, null-terminated name
        )
        val items = ByteArrayOutputStream()
        fun item(atom: String, value: String?) {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            // data box: type indicator 1 = UTF-8 text, locale 0
            val data = box("data", byteArrayOf(0, 0, 0, 1) + ByteArray(4) + text.toByteArray(Charsets.UTF_8))
            items.write(box(atom, data))
        }
        item("©nam", metadata.title)
        item("©ART", metadata.artist)
        item("aART", metadata.artist)
        item("©day", metadata.year?.toString())
        item("©cmt", metadata.comment)
        val ilst = box("ilst", items.toByteArray())
        return box("meta", ByteArray(4) + hdlr + ilst)
    }

    // ---------------------------------------------------------------- offset fix-ups

    private fun patchChunkOffsets(buf: ByteArray, start: Int, end: Int, threshold: Long, delta: Long) {
        forEachChild(buf, start, end) { type, offset, header, size ->
            val body = offset + header
            when (type) {
                in CONTAINERS_WITH_OFFSETS -> patchChunkOffsets(buf, body, offset + size, threshold, delta)
                "stco" -> {
                    val count = buf.u32(body + 4).toInt()
                    for (i in 0 until count) {
                        val at = body + 8 + i * 4
                        val value = buf.u32(at)
                        if (value >= threshold) {
                            val patched = value + delta
                            if (patched > UInt.MAX_VALUE.toLong()) throw TaggingException("stco offset overflow")
                            buf.putU32(at, patched)
                        }
                    }
                }
                "co64" -> {
                    val count = buf.u32(body + 4).toInt()
                    for (i in 0 until count) {
                        val at = body + 8 + i * 8
                        val value = buf.u64(at)
                        if (value >= threshold) buf.putU64(at, value + delta)
                    }
                }
            }
        }
    }

    private fun patchMoof(moof: ByteArray, threshold: Long, delta: Long): ByteArray {
        val headerSize = if (moof.u32(0) == 1L) 16 else 8
        forEachChild(moof, headerSize, moof.size) { type, offset, header, size ->
            if (type != "traf") return@forEachChild
            forEachChild(moof, offset + header, offset + size) { childType, childOffset, childHeader, _ ->
                if (childType == "tfhd") {
                    val body = childOffset + childHeader
                    val flags = moof.u32(body) and 0xFFFFFF
                    if (flags and 0x000001L != 0L) { // base-data-offset-present
                        val at = body + 4 + 4 // after version/flags and track_ID
                        val value = moof.u64(at)
                        if (value >= threshold) moof.putU64(at, value + delta)
                    }
                }
            }
        }
        return moof
    }

    private fun patchMfra(mfra: ByteArray, threshold: Long, delta: Long): ByteArray {
        val headerSize = if (mfra.u32(0) == 1L) 16 else 8
        forEachChild(mfra, headerSize, mfra.size) { type, offset, header, _ ->
            if (type != "tfra") return@forEachChild
            val body = offset + header
            val version = mfra[body].toInt()
            val lengths = mfra.u32(body + 8)
            val trafLen = ((lengths shr 4) and 3).toInt() + 1
            val trunLen = ((lengths shr 2) and 3).toInt() + 1
            val sampleLen = (lengths and 3).toInt() + 1
            val count = mfra.u32(body + 12).toInt()
            val fieldLen = if (version == 1) 8 else 4
            var pos = body + 16
            repeat(count) {
                val offsetAt = pos + fieldLen // skip "time"
                if (version == 1) {
                    val v = mfra.u64(offsetAt)
                    if (v >= threshold) mfra.putU64(offsetAt, v + delta)
                } else {
                    val v = mfra.u32(offsetAt)
                    if (v >= threshold) mfra.putU32(offsetAt, v + delta)
                }
                pos += fieldLen * 2 + trafLen + trunLen + sampleLen
            }
        }
        return mfra
    }

    private fun patchFtyp(ftyp: ByteArray): ByteArray {
        val headerSize = if (ftyp.u32(0) == 1L) 16 else 8
        val dash = "dash".latin1()
        val m4a = "M4A ".latin1()
        if (ftyp.regionEquals(headerSize, dash)) m4a.copyInto(ftyp, headerSize)
        // Compatible brands start after major_brand (4) + minor_version (4).
        var pos = headerSize + 8
        while (pos + 4 <= ftyp.size) {
            if (ftyp.regionEquals(pos, dash)) "mp42".latin1().copyInto(ftyp, pos)
            pos += 4
        }
        return ftyp
    }

    // ---------------------------------------------------------------- byte helpers

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.latin1()).put(payload).array()

    private fun String.latin1() = toByteArray(Charsets.ISO_8859_1)

    private fun ByteArray.u32(at: Int): Long = ByteBuffer.wrap(this, at, 4).int.toUInt().toLong()
    private fun ByteArray.u64(at: Int): Long = ByteBuffer.wrap(this, at, 8).long
    private fun ByteArray.putU32(at: Int, value: Long) { ByteBuffer.wrap(this, at, 4).putInt(value.toInt()) }
    private fun ByteArray.putU64(at: Int, value: Long) { ByteBuffer.wrap(this, at, 8).putLong(value) }
    private fun ByteArray.regionEquals(at: Int, other: ByteArray) =
        at + other.size <= size && other.indices.all { this[at + it] == other[it] }

    private fun OutputStream.writeU32(value: Long) = write(ByteBuffer.allocate(4).putInt(value.toInt()).array())

    private fun RandomAccessFile.readAt(offset: Long, length: Int): ByteArray {
        seek(offset)
        return ByteArray(length).also { readFully(it) }
    }

    private fun RandomAccessFile.copyRange(offset: Long, length: Long, out: OutputStream) {
        seek(offset)
        val buffer = ByteArray(256 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw TaggingException("Unexpected end of file while copying")
            out.write(buffer, 0, n)
            remaining -= n
        }
    }
}
