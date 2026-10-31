package io.github.rudolfhk.audiosaver.core.video

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class MuxException(message: String) : IOException(message)

/**
 * Merges a video-only and an audio-only *fragmented* MP4 - the two DASH streams YouTube serves -
 * into one playable MP4, without decoding or re-encoding anything and without FFmpeg.
 *
 * Both inputs are `ftyp, moov(one trak), [sidx], (moof, mdat)*, [mfra]`. The output is
 * `ftyp, moov(video trak #1, audio trak #2, mvex), (moof, mdat)*` with the fragments of both
 * tracks interleaved by decode time, which is what a player reading front to back expects:
 *
 *  - both `trak` boxes are copied as they are, with track ids renumbered and real durations
 *    written into `tkhd`/`mdhd` (DASH inputs carry 0 there), plus `mvhd` and `mehd`, so galleries
 *    and players show the length;
 *  - each `moof`+`mdat` pair is copied verbatim except `mfhd.sequence_number`, `tfhd.track_ID`
 *    and, for fragments that use absolute offsets, `tfhd.base_data_offset`. `trun` offsets are
 *    relative to their `moof` and stay valid because every `mdat` is written right after its
 *    `moof` again;
 *  - per-track indexes (`sidx`, `mfra`) are dropped: they describe one track's layout and would be
 *    wrong for the merged file. Seeking works from the fragments themselves.
 */
object DashMp4Muxer {

    private const val MAX_MOOV_BYTES = 16 * 1024 * 1024
    private const val MAX_MOOF_BYTES = 16 * 1024 * 1024
    private const val VIDEO_TRACK = 1
    private const val AUDIO_TRACK = 2

    fun mux(video: File, audio: File, output: File) {
        RandomAccessFile(video, "r").use { v ->
            RandomAccessFile(audio, "r").use { a ->
                val videoTrack = readTrack(v, "video", "vide")
                val audioTrack = readTrack(a, "audio", "soun")
                write(videoTrack, audioTrack, output)
            }
        }
    }

    // ---------------------------------------------------------------- reading

    private class Box(val type: String, val offset: Long, val headerSize: Int, val size: Long)

    private class Fragment(
        val moof: ByteArray,
        val moofOffset: Long,
        val mdatOffset: Long,
        val mdatSize: Long,
        val startSeconds: Double,
        val durationUnits: Long,
    )

    private class Track(
        val raf: RandomAccessFile,
        val trak: ByteArray,
        val trex: ByteArray,
        val movieHeader: ByteArray,
        val mediaTimescale: Long,
        val fragments: List<Fragment>,
    ) {
        val durationUnits: Long get() = fragments.sumOf { it.durationUnits }
        val durationSeconds: Double get() = durationUnits.toDouble() / mediaTimescale
    }

    private fun readTrack(raf: RandomAccessFile, label: String, expectedHandler: String): Track {
        val boxes = topLevelBoxes(raf, label)
        val moovBox = boxes.singleOrNull { it.type == "moov" } ?: throw MuxException("The $label stream has no moov box")
        if (moovBox.size > MAX_MOOV_BYTES) throw MuxException("The $label stream's moov is unreasonably large")
        val moov = raf.readAt(moovBox.offset + moovBox.headerSize, (moovBox.size - moovBox.headerSize).toInt())

        val traks = children(moov, 0, moov.size).filter { it.type == "trak" }
        if (traks.size != 1) throw MuxException("The $label stream has ${traks.size} tracks, expected exactly 1")
        val trakBox = traks.single()
        val trak = moov.copyOfRange(trakBox.offset.toInt(), (trakBox.offset + trakBox.size).toInt())
        val handler = findPath(trak, "mdia", "hdlr")?.let { String(trak, it + 8, 4, Charsets.ISO_8859_1) }
        if (handler != expectedHandler) throw MuxException("The $label stream's track is '$handler', not '$expectedHandler'")
        val mdhd = findPath(trak, "mdia", "mdhd") ?: throw MuxException("The $label stream has no mdhd")
        val mediaTimescale = u32(trak, mdhd + (if (trak[mdhd] == 1.toByte()) 20 else 12))
        if (mediaTimescale <= 0) throw MuxException("The $label stream has timescale 0")

        val mvexBox = children(moov, 0, moov.size).firstOrNull { it.type == "mvex" }
            ?: throw MuxException("The $label stream is not a fragmented MP4 (no mvex) - can't merge it")
        val trexBox = children(moov, (mvexBox.offset + mvexBox.headerSize).toInt(), (mvexBox.offset + mvexBox.size).toInt())
            .firstOrNull { it.type == "trex" } ?: throw MuxException("The $label stream has no trex")
        val trex = moov.copyOfRange(trexBox.offset.toInt(), (trexBox.offset + trexBox.size).toInt())
        val trexDefaultDuration = u32(trex, 8 + 12)

        val mvhdBox = children(moov, 0, moov.size).firstOrNull { it.type == "mvhd" } ?: throw MuxException("The $label stream has no mvhd")
        val mvhd = moov.copyOfRange(mvhdBox.offset.toInt(), (mvhdBox.offset + mvhdBox.size).toInt())

        val fragments = mutableListOf<Fragment>()
        var runningUnits = 0L
        for ((i, box) in boxes.withIndex()) {
            if (box.type != "moof") continue
            val mdat = boxes.getOrNull(i + 1)?.takeIf { it.type == "mdat" }
                ?: throw MuxException("A fragment of the $label stream isn't followed by its media data")
            if (box.size > MAX_MOOF_BYTES) throw MuxException("A fragment header of the $label stream is unreasonably large")
            val moof = raf.readAt(box.offset, box.size.toInt())
            val timing = fragmentTiming(moof, trexDefaultDuration, label)
            val start = timing.baseDecodeTime ?: runningUnits
            fragments += Fragment(moof, box.offset, mdat.offset, mdat.size, start.toDouble() / mediaTimescale, timing.durationUnits)
            runningUnits = start + timing.durationUnits
        }
        if (fragments.isEmpty()) throw MuxException("The $label stream contains no media fragments")
        return Track(raf, trak, trex, mvhd, mediaTimescale, fragments)
    }

    private class Timing(val baseDecodeTime: Long?, val durationUnits: Long)

    private fun fragmentTiming(moof: ByteArray, trexDefaultDuration: Long, label: String): Timing {
        val trafs = children(moof, headerSize(moof), moof.size).filter { it.type == "traf" }
        if (trafs.size != 1) throw MuxException("A fragment of the $label stream has ${trafs.size} track runs, expected 1")
        val traf = trafs.single()
        var defaultDuration = trexDefaultDuration
        var base: Long? = null
        var duration = 0L
        for (child in children(moof, (traf.offset + traf.headerSize).toInt(), (traf.offset + traf.size).toInt())) {
            val body = (child.offset + child.headerSize).toInt()
            when (child.type) {
                "tfhd" -> {
                    val flags = u32(moof, body) and 0xFFFFFF
                    var pos = body + 8
                    if (flags and 0x01L != 0L) pos += 8
                    if (flags and 0x02L != 0L) pos += 4
                    if (flags and 0x08L != 0L) defaultDuration = u32(moof, pos)
                }
                "tfdt" -> base = if (moof[body] == 1.toByte()) u64(moof, body + 4) else u32(moof, body + 4)
                "trun" -> {
                    val flags = u32(moof, body) and 0xFFFFFF
                    val count = u32(moof, body + 4)
                    if (flags and 0x100L == 0L) {
                        duration += count * defaultDuration
                    } else {
                        var pos = body + 8
                        if (flags and 0x01L != 0L) pos += 4
                        if (flags and 0x04L != 0L) pos += 4
                        val stride = listOf(0x100L, 0x200L, 0x400L, 0x800L).count { flags and it != 0L } * 4
                        repeat(count.toInt()) {
                            duration += u32(moof, pos)
                            pos += stride
                        }
                    }
                }
            }
        }
        return Timing(base, duration)
    }

    // ---------------------------------------------------------------- writing

    private fun write(video: Track, audio: Track, output: File) {
        val movieTimescale = u32(video.movieHeader, headerSize(video.movieHeader) + (if (video.movieHeader[8] == 1.toByte()) 20 else 12))
            .takeIf { it > 0 } ?: 1000L
        val movieDurationSeconds = maxOf(video.durationSeconds, audio.durationSeconds)
        val movieDuration = (movieDurationSeconds * movieTimescale).toLong()

        val moovPayload = ByteArrayOutputStream()
        moovPayload.write(patchMvhd(video.movieHeader.copyOf(), movieDuration))
        moovPayload.write(patchTrak(video.trak.copyOf(), VIDEO_TRACK, (video.durationSeconds * movieTimescale).toLong(), video.durationUnits))
        moovPayload.write(patchTrak(audio.trak.copyOf(), AUDIO_TRACK, (audio.durationSeconds * movieTimescale).toLong(), audio.durationUnits))
        val mehd = box("mehd", byteArrayOf(1, 0, 0, 0) + ByteBuffer.allocate(8).putLong(movieDuration).array())
        moovPayload.write(box("mvex", mehd + patchTrex(video.trex.copyOf(), VIDEO_TRACK) + patchTrex(audio.trex.copyOf(), AUDIO_TRACK)))
        val moov = box("moov", moovPayload.toByteArray())
        val ftyp = box("ftyp", "isom".latin1() + byteArrayOf(0, 0, 2, 0) + listOf("isom", "iso6", "iso2", "avc1", "mp41").joinToString("").latin1())

        // Interleave by decode time; on a tie, video first (it carries the keyframes players seek to).
        val ordered = (video.fragments.map { it to video } + audio.fragments.map { it to audio })
            .sortedWith(compareBy<Pair<Fragment, Track>> { it.first.startSeconds }.thenBy { if (it.second === video) 0 else 1 })

        BufferedOutputStream(output.outputStream(), 256 * 1024).use { out ->
            out.write(ftyp)
            out.write(moov)
            var position = (ftyp.size + moov.size).toLong()
            for ((index, pair) in ordered.withIndex()) {
                val (fragment, track) = pair
                val trackId = if (track === video) VIDEO_TRACK else AUDIO_TRACK
                val moof = patchMoof(fragment.moof.copyOf(), index + 1L, trackId, fragment.moofOffset, position)
                out.write(moof)
                track.raf.copyRange(fragment.mdatOffset, fragment.mdatSize, out)
                position += moof.size + fragment.mdatSize
            }
        }
    }

    private fun patchMvhd(mvhd: ByteArray, duration: Long): ByteArray {
        val body = headerSize(mvhd)
        if (mvhd[body] == 1.toByte()) putU64(mvhd, body + 24, duration) else putU32(mvhd, body + 16, duration.coerceAtMost(0xFFFFFFFFL))
        putU32(mvhd, mvhd.size - 4, 3) // next_track_ID
        return mvhd
    }

    private fun patchTrak(trak: ByteArray, trackId: Int, movieDuration: Long, mediaDuration: Long): ByteArray {
        val tkhd = findPath(trak, "tkhd") ?: throw MuxException("Track has no tkhd")
        if (trak[tkhd] == 1.toByte()) {
            putU32(trak, tkhd + 20, trackId.toLong())
            putU64(trak, tkhd + 28, movieDuration)
        } else {
            putU32(trak, tkhd + 12, trackId.toLong())
            putU32(trak, tkhd + 20, movieDuration.coerceAtMost(0xFFFFFFFFL))
        }
        val mdhd = findPath(trak, "mdia", "mdhd") ?: throw MuxException("Track has no mdhd")
        if (trak[mdhd] == 1.toByte()) putU64(trak, mdhd + 24, mediaDuration) else putU32(trak, mdhd + 16, mediaDuration.coerceAtMost(0xFFFFFFFFL))
        return trak
    }

    private fun patchTrex(trex: ByteArray, trackId: Int): ByteArray {
        putU32(trex, headerSize(trex) + 4, trackId.toLong())
        return trex
    }

    private fun patchMoof(moof: ByteArray, sequence: Long, trackId: Int, oldOffset: Long, newOffset: Long): ByteArray {
        for (child in children(moof, headerSize(moof), moof.size)) {
            val body = (child.offset + child.headerSize).toInt()
            when (child.type) {
                "mfhd" -> putU32(moof, body + 4, sequence)
                "traf" -> for (t in children(moof, body, (child.offset + child.size).toInt())) {
                    if (t.type != "tfhd") continue
                    val tfhd = (t.offset + t.headerSize).toInt()
                    putU32(moof, tfhd + 4, trackId.toLong())
                    if (u32(moof, tfhd) and 0x01L != 0L) { // absolute base-data-offset: move with the fragment
                        putU64(moof, tfhd + 8, u64(moof, tfhd + 8) - oldOffset + newOffset)
                    }
                }
            }
        }
        return moof
    }

    // ---------------------------------------------------------------- box helpers

    private fun topLevelBoxes(raf: RandomAccessFile, label: String): List<Box> {
        val boxes = mutableListOf<Box>()
        val length = raf.length()
        var pos = 0L
        while (pos < length) {
            if (length - pos < 8) throw MuxException("The $label stream is truncated")
            raf.seek(pos)
            var size = raf.readInt().toUInt().toLong()
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = raf.readLong(); header = 16
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < header || pos + size > length) throw MuxException("The $label stream is truncated or not an MP4 (box '$type' at $pos)")
            boxes += Box(type, pos, header, size)
            pos += size
        }
        if (boxes.firstOrNull()?.type != "ftyp") throw MuxException("The $label stream is not an MP4 file")
        return boxes
    }

    private fun children(buf: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var pos = start
        while (pos + 8 <= end) {
            var size = u32(buf, pos)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = u64(buf, pos + 8); header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) throw MuxException("Corrupt '$type' box")
            result += Box(type, pos.toLong(), header, size)
            pos += size.toInt()
        }
        return result
    }

    /** Offset of the body (after the header) of the box at [path] inside a box starting at 0 of [buf]. */
    private fun findPath(buf: ByteArray, vararg path: String): Int? {
        var start = headerSize(buf)
        var end = buf.size
        var body: Int? = null
        for (type in path) {
            val box = children(buf, start, end).firstOrNull { it.type == type } ?: return null
            body = (box.offset + box.headerSize).toInt()
            start = body
            end = (box.offset + box.size).toInt()
        }
        return body
    }

    private fun headerSize(box: ByteArray) = if (u32(box, 0) == 1L) 16 else 8

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.latin1()).put(payload).array()

    private fun String.latin1() = toByteArray(Charsets.ISO_8859_1)
    private fun u32(b: ByteArray, at: Int): Long = ByteBuffer.wrap(b, at, 4).int.toUInt().toLong()
    private fun u64(b: ByteArray, at: Int): Long = ByteBuffer.wrap(b, at, 8).long
    private fun putU32(b: ByteArray, at: Int, v: Long) { ByteBuffer.wrap(b, at, 4).putInt(v.toInt()) }
    private fun putU64(b: ByteArray, at: Int, v: Long) { ByteBuffer.wrap(b, at, 8).putLong(v) }

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
            if (n < 0) throw MuxException("Unexpected end of file while copying media data")
            out.write(buffer, 0, n)
            remaining -= n
        }
    }
}
