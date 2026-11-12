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
 * into one ordinary MP4, without decoding or re-encoding anything and without FFmpeg.
 *
 * Inputs: `ftyp, moov(one trak), [sidx], (moof, mdat)*, [mfra]`. Output: a regular, non-fragmented
 * MP4 - `ftyp, moov(video trak #1, audio trak #2), mdat` - with complete sample tables. That is
 * the layout every player, gallery and TV understands. (An earlier version kept the input
 * fragments and interleaved them; Android's own MP4 extractor stops at the first fragment that
 * doesn't contain its track, so on phones the video ended after one second.)
 *
 *  - every fragment's `trun` is read into per-sample duration, size, composition offset and
 *    keyframe flag, honouring `tfhd`/`trex` defaults and all three ways of addressing the data;
 *  - each `trak` is copied with its `stbl` rebuilt from those samples (`stts`, `ctts` when needed,
 *    `stss` when not every sample is a keyframe, `stsc`, `stsz`, `stco`/`co64`), real durations in
 *    `tkhd`/`mdhd`/`mvhd`, and zero-length edit-list entries (meaning "all" in DASH) filled in;
 *  - the sample data is copied run by run into one `mdat`, the runs of both tracks interleaved by
 *    decode time so playback never has to jump far in the file; `moov` comes first, so the file
 *    starts playing before it's fully read.
 */
object DashMp4Muxer {

    private const val MAX_MOOV_BYTES = 16 * 1024 * 1024
    private const val MAX_MOOF_BYTES = 16 * 1024 * 1024
    private const val VIDEO_TRACK = 1
    private const val AUDIO_TRACK = 2
    private const val NON_SYNC_SAMPLE = 0x10000

    fun mux(video: File, audio: File, output: File) {
        RandomAccessFile(video, "r").use { v ->
            RandomAccessFile(audio, "r").use { a ->
                write(readTrack(v, "video", "vide"), readTrack(a, "audio", "soun"), output)
            }
        }
    }

    // ---------------------------------------------------------------- reading

    private class Box(val type: String, val offset: Long, val headerSize: Int, val size: Long)

    /** One `trun`: samples stored back to back in the input file starting at [dataOffset]. */
    private class Run(
        val dataOffset: Long,
        val sizes: IntArray,
        val durations: IntArray,
        val compositionOffsets: IntArray,
        val sync: BooleanArray,
        val startUnits: Long,
        val mediaTimescale: Long,
    ) {
        val byteSize: Long get() = sizes.fold(0L) { acc, s -> acc + s }
        val startSeconds: Double get() = startUnits.toDouble() / mediaTimescale
    }

    private class Track(
        val raf: RandomAccessFile,
        val trak: ByteArray,
        val movieHeader: ByteArray,
        val mediaTimescale: Long,
        val runs: List<Run>,
    ) {
        val durationUnits: Long get() = runs.sumOf { run -> run.durations.fold(0L) { acc, d -> acc + d } }
        val durationSeconds: Double get() = durationUnits.toDouble() / mediaTimescale
        val sampleCount: Int get() = runs.sumOf { it.sizes.size }
    }

    private class Defaults(val duration: Long, val size: Long, val flags: Long)

    private fun readTrack(raf: RandomAccessFile, label: String, expectedHandler: String): Track {
        val boxes = topLevelBoxes(raf, label)
        val moovBox = boxes.singleOrNull { it.type == "moov" } ?: throw MuxException("The $label stream has no moov box")
        if (moovBox.size > MAX_MOOV_BYTES) throw MuxException("The $label stream's moov is unreasonably large")
        val moov = raf.readAt(moovBox.offset + moovBox.headerSize, (moovBox.size - moovBox.headerSize).toInt())
        val moovChildren = children(moov, 0, moov.size)

        val traks = moovChildren.filter { it.type == "trak" }
        if (traks.size != 1) throw MuxException("The $label stream has ${traks.size} tracks, expected exactly 1")
        val trak = moov.copyOfRange(traks.single().offset.toInt(), (traks.single().offset + traks.single().size).toInt())
        val handler = findPath(trak, "mdia", "hdlr")?.let { String(trak, it + 8, 4, Charsets.ISO_8859_1) }
        if (handler != expectedHandler) throw MuxException("The $label stream's track is '$handler', not '$expectedHandler'")
        val mdhd = findPath(trak, "mdia", "mdhd") ?: throw MuxException("The $label stream has no mdhd")
        val mediaTimescale = u32(trak, mdhd + (if (trak[mdhd] == 1.toByte()) 20 else 12))
        if (mediaTimescale <= 0) throw MuxException("The $label stream has timescale 0")
        if (findPath(trak, "mdia", "minf", "stbl", "stsd") == null) throw MuxException("The $label stream has no sample description")

        val mvex = moovChildren.firstOrNull { it.type == "mvex" }
            ?: throw MuxException("The $label stream is not a fragmented MP4 (no mvex) - can't merge it")
        val trexBox = children(moov, (mvex.offset + mvex.headerSize).toInt(), (mvex.offset + mvex.size).toInt())
            .firstOrNull { it.type == "trex" } ?: throw MuxException("The $label stream has no trex")
        val trex = (trexBox.offset + trexBox.headerSize).toInt()
        val trexDefaults = Defaults(duration = u32(moov, trex + 12), size = u32(moov, trex + 16), flags = u32(moov, trex + 20))

        val mvhdBox = moovChildren.firstOrNull { it.type == "mvhd" } ?: throw MuxException("The $label stream has no mvhd")
        val mvhd = moov.copyOfRange(mvhdBox.offset.toInt(), (mvhdBox.offset + mvhdBox.size).toInt())

        val runs = mutableListOf<Run>()
        var nextDecodeTime = 0L
        for ((i, box) in boxes.withIndex()) {
            if (box.type != "moof") continue
            val mdat = boxes.getOrNull(i + 1)?.takeIf { it.type == "mdat" }
                ?: throw MuxException("A fragment of the $label stream isn't followed by its media data")
            if (box.size > MAX_MOOF_BYTES) throw MuxException("A fragment header of the $label stream is unreasonably large")
            val moof = raf.readAt(box.offset, box.size.toInt())
            for (run in parseMoof(moof, box.offset, trexDefaults, nextDecodeTime, mediaTimescale, label)) {
                if (run.dataOffset < mdat.offset + mdat.headerSize || run.dataOffset + run.byteSize > mdat.offset + mdat.size) {
                    throw MuxException("A fragment of the $label stream points outside its media data")
                }
                runs += run
                nextDecodeTime = run.startUnits + run.durations.fold(0L) { acc, d -> acc + d }
            }
        }
        if (runs.isEmpty()) throw MuxException("The $label stream contains no media fragments")
        return Track(raf, trak, mvhd, mediaTimescale, runs)
    }

    private fun parseMoof(moof: ByteArray, moofOffset: Long, trex: Defaults, runningTime: Long, timescale: Long, label: String): List<Run> {
        val trafs = children(moof, headerSize(moof), moof.size).filter { it.type == "traf" }
        if (trafs.size != 1) throw MuxException("A fragment of the $label stream has ${trafs.size} track runs, expected 1")
        val traf = trafs.single()
        val parts = children(moof, (traf.offset + traf.headerSize).toInt(), (traf.offset + traf.size).toInt())

        val tfhd = parts.firstOrNull { it.type == "tfhd" } ?: throw MuxException("A fragment of the $label stream has no tfhd")
        var pos = (tfhd.offset + tfhd.headerSize).toInt()
        val tfhdFlags = u32(moof, pos) and 0xFFFFFF
        pos += 8 // version/flags + track_ID
        // With a single traf, "default-base-is-moof" and "no base given" both mean: relative to the moof.
        var base = moofOffset
        if (tfhdFlags and 0x01L != 0L) { base = u64(moof, pos); pos += 8 }
        if (tfhdFlags and 0x02L != 0L) pos += 4
        var defaultDuration = trex.duration
        var defaultSize = trex.size
        var defaultFlags = trex.flags
        if (tfhdFlags and 0x08L != 0L) { defaultDuration = u32(moof, pos); pos += 4 }
        if (tfhdFlags and 0x10L != 0L) { defaultSize = u32(moof, pos); pos += 4 }
        if (tfhdFlags and 0x20L != 0L) { defaultFlags = u32(moof, pos) }

        var time = parts.firstOrNull { it.type == "tfdt" }?.let { tfdt ->
            val body = (tfdt.offset + tfdt.headerSize).toInt()
            if (moof[body] == 1.toByte()) u64(moof, body + 4) else u32(moof, body + 4)
        } ?: runningTime

        val runs = mutableListOf<Run>()
        var dataPosition = base
        for (trun in parts.filter { it.type == "trun" }) {
            var p = (trun.offset + trun.headerSize).toInt()
            val flags = u32(moof, p) and 0xFFFFFF
            val count = u32(moof, p + 4)
            if (count > 10_000_000) throw MuxException("Implausible sample count in the $label stream")
            p += 8
            if (flags and 0x01L != 0L) { dataPosition = base + ByteBuffer.wrap(moof, p, 4).int; p += 4 }
            var firstFlags: Long? = null
            if (flags and 0x04L != 0L) { firstFlags = u32(moof, p); p += 4 }
            val n = count.toInt()
            val sizes = IntArray(n)
            val durations = IntArray(n)
            val ctos = IntArray(n)
            val sync = BooleanArray(n)
            for (i in 0 until n) {
                durations[i] = (if (flags and 0x100L != 0L) u32(moof, p).also { p += 4 } else defaultDuration).toInt()
                sizes[i] = (if (flags and 0x200L != 0L) u32(moof, p).also { p += 4 } else defaultSize).toInt()
                val sampleFlags = if (flags and 0x400L != 0L) u32(moof, p).also { p += 4 } else if (i == 0 && firstFlags != null) firstFlags else defaultFlags
                if (flags and 0x800L != 0L) {
                    // Version 0 is unsigned, version 1 signed; real offsets fit an Int either way.
                    ctos[i] = ByteBuffer.wrap(moof, p, 4).int
                    p += 4
                }
                sync[i] = sampleFlags and NON_SYNC_SAMPLE.toLong() == 0L
                if (sizes[i] < 0) throw MuxException("Implausible sample size in the $label stream")
            }
            val run = Run(dataPosition, sizes, durations, ctos, sync, time, timescale)
            if (n > 0) runs += run
            dataPosition += run.byteSize
            time += durations.fold(0L) { acc, d -> acc + d }
        }
        return runs
    }

    // ---------------------------------------------------------------- writing

    private fun write(video: Track, audio: Track, output: File) {
        val movieTimescale = u32(video.movieHeader, headerSize(video.movieHeader) + (if (video.movieHeader[8] == 1.toByte()) 20 else 12))
            .takeIf { it > 0 } ?: 1000L

        // Interleave the runs of both tracks by decode time; on a tie, video first.
        val chunks = (video.runs.map { it to VIDEO_TRACK } + audio.runs.map { it to AUDIO_TRACK })
            .sortedWith(compareBy<Pair<Run, Int>> { it.first.startSeconds }.thenBy { it.second })
        val mdatPayload = chunks.sumOf { it.first.byteSize }
        val largeMdat = mdatPayload + 8 > 0xFFFFFFFFL
        val mdatHeader = if (largeMdat) 16 else 8

        val ftyp = box("ftyp", "isom".latin1() + byteArrayOf(0, 0, 2, 0) + listOf("isom", "iso2", "avc1", "mp41").joinToString("").latin1())

        fun buildMoov(mdatStart: Long): ByteArray {
            // Absolute file offset of every chunk, per track, in that track's sample order.
            val offsets = mapOf(VIDEO_TRACK to mutableListOf<Long>(), AUDIO_TRACK to mutableListOf())
            var position = mdatStart + mdatHeader
            for ((run, track) in chunks) {
                offsets.getValue(track) += position
                position += run.byteSize
            }
            val useCo64 = position > 0xFFFFFFFFL
            val movieDuration = (maxOf(video.durationSeconds, audio.durationSeconds) * movieTimescale).toLong()
            val payload = ByteArrayOutputStream()
            payload.write(patchMvhd(video.movieHeader.copyOf(), movieDuration))
            payload.write(buildTrak(video, VIDEO_TRACK, offsets.getValue(VIDEO_TRACK), useCo64, movieTimescale))
            payload.write(buildTrak(audio, AUDIO_TRACK, offsets.getValue(AUDIO_TRACK), useCo64, movieTimescale))
            return box("moov", payload.toByteArray())
        }

        // The chunk offsets don't change moov's size, so a first pass gives its size, the second the real offsets.
        val moovSize = buildMoov(0).size
        val moov = buildMoov((ftyp.size + moovSize).toLong())
        check(moov.size == moovSize)

        BufferedOutputStream(output.outputStream(), 256 * 1024).use { out ->
            out.write(ftyp)
            out.write(moov)
            if (largeMdat) {
                out.write(ByteBuffer.allocate(16).putInt(1).put("mdat".latin1()).putLong(mdatPayload + 16).array())
            } else {
                out.write(ByteBuffer.allocate(8).putInt((mdatPayload + 8).toInt()).put("mdat".latin1()).array())
            }
            for ((run, track) in chunks) {
                val source = if (track == VIDEO_TRACK) video else audio
                source.raf.copyRange(run.dataOffset, run.byteSize, out)
            }
        }
    }

    private fun buildTrak(track: Track, trackId: Int, chunkOffsets: List<Long>, co64: Boolean, movieTimescale: Long): ByteArray {
        val samples = track.runs
        val sizes = IntArray(track.sampleCount)
        val durations = IntArray(track.sampleCount)
        val ctos = IntArray(track.sampleCount)
        val syncSamples = mutableListOf<Int>()
        var i = 0
        for (run in samples) {
            for (s in run.sizes.indices) {
                sizes[i] = run.sizes[s]
                durations[i] = run.durations[s]
                ctos[i] = run.compositionOffsets[s]
                if (run.sync[s]) syncSamples += i + 1
                i++
            }
        }

        val stbl = ByteArrayOutputStream()
        val oldStbl = findBox(track.trak, "mdia", "minf", "stbl") ?: throw MuxException("Track has no stbl")
        val stsd = findBox(track.trak, "mdia", "minf", "stbl", "stsd")!!
        stbl.write(track.trak, stsd.offset.toInt(), stsd.size.toInt())
        stbl.write(fullBox("stts", 0, runLengths(durations) { count, value -> putInt(count); putInt(value) }))
        if (ctos.any { it != 0 }) {
            val version = if (ctos.any { it < 0 }) 1 else 0
            stbl.write(fullBox("ctts", version, runLengths(ctos) { count, value -> putInt(count); putInt(value) }))
        }
        if (syncSamples.size != sizes.size) {
            stbl.write(fullBox("stss", 0, entries(syncSamples.size) { k -> putInt(syncSamples[k]) }))
        }
        stbl.write(fullBox("stsc", 0, stscEntries(samples.map { it.sizes.size })))
        stbl.write(fullBox("stsz", 0, ByteBuffer.allocate(8 + 4 * sizes.size).putInt(0).putInt(sizes.size).apply { sizes.forEach { putInt(it) } }.array()))
        if (co64) {
            stbl.write(fullBox("co64", 0, entries(chunkOffsets.size, 8) { k -> putLong(chunkOffsets[k]) }))
        } else {
            stbl.write(fullBox("stco", 0, entries(chunkOffsets.size) { k -> putInt(chunkOffsets[k].toInt()) }))
        }
        val newStbl = box("stbl", stbl.toByteArray())

        // Rebuild trak -> mdia -> minf with the new stbl (container sizes change).
        val minf = findBox(track.trak, "mdia", "minf")!!
        val mdia = findBox(track.trak, "mdia")!!
        val newMinf = replaceChild(track.trak.sliceBox(minf), oldStbl.type, newStbl)
        val newMdia = replaceChild(track.trak.sliceBox(mdia), "minf", newMinf)
        val trak = replaceChild(track.trak, "mdia", newMdia)

        val movieDuration = (track.durationSeconds * movieTimescale).toLong()
        val tkhd = findPath(trak, "tkhd") ?: throw MuxException("Track has no tkhd")
        if (trak[tkhd] == 1.toByte()) {
            putU32(trak, tkhd + 20, trackId.toLong()); putU64(trak, tkhd + 28, movieDuration)
        } else {
            putU32(trak, tkhd + 12, trackId.toLong()); putU32(trak, tkhd + 20, movieDuration.coerceAtMost(0xFFFFFFFFL))
        }
        val mdhd = findPath(trak, "mdia", "mdhd")!!
        if (trak[mdhd] == 1.toByte()) putU64(trak, mdhd + 24, track.durationUnits) else putU32(trak, mdhd + 16, track.durationUnits.coerceAtMost(0xFFFFFFFFL))
        // In DASH an edit with duration 0 means "the whole track"; in a regular MP4 it would mean "nothing".
        findPath(trak, "edts", "elst")?.let { elst ->
            val version = trak[elst].toInt()
            val count = u32(trak, elst + 4).toInt()
            var p = elst + 8
            repeat(count) {
                if (version == 1) {
                    if (u64(trak, p) == 0L) putU64(trak, p, movieDuration)
                    p += 20
                } else {
                    if (u32(trak, p) == 0L) putU32(trak, p, movieDuration.coerceAtMost(0xFFFFFFFFL))
                    p += 12
                }
            }
        }
        return trak
    }

    private fun patchMvhd(mvhd: ByteArray, duration: Long): ByteArray {
        val body = headerSize(mvhd)
        if (mvhd[body] == 1.toByte()) putU64(mvhd, body + 24, duration) else putU32(mvhd, body + 16, duration.coerceAtMost(0xFFFFFFFFL))
        putU32(mvhd, mvhd.size - 4, 3) // next_track_ID
        return mvhd
    }

    // ---------------------------------------------------------------- table helpers

    /** entry_count + run-length encoded (count, value) pairs. */
    private inline fun runLengths(values: IntArray, writeEntry: ByteBuffer.(count: Int, value: Int) -> Unit): ByteArray {
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (v in values) {
            if (pairs.isNotEmpty() && pairs.last().second == v) pairs[pairs.lastIndex] = pairs.last().first + 1 to v else pairs += 1 to v
        }
        val buffer = ByteBuffer.allocate(4 + pairs.size * 8).putInt(pairs.size)
        pairs.forEach { (count, value) -> buffer.writeEntry(count, value) }
        return buffer.array()
    }

    private inline fun entries(count: Int, width: Int = 4, writeEntry: ByteBuffer.(Int) -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(4 + count * width).putInt(count)
        for (k in 0 until count) buffer.writeEntry(k)
        return buffer.array()
    }

    /** One chunk per run; consecutive chunks with the same sample count share an entry. */
    private fun stscEntries(samplesPerChunk: List<Int>): ByteArray {
        val rows = mutableListOf<Pair<Int, Int>>() // first_chunk (1-based), samples_per_chunk
        samplesPerChunk.forEachIndexed { index, n -> if (rows.lastOrNull()?.second != n) rows += index + 1 to n }
        val buffer = ByteBuffer.allocate(4 + rows.size * 12).putInt(rows.size)
        rows.forEach { (first, n) -> buffer.putInt(first).putInt(n).putInt(1) }
        return buffer.array()
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

    /** The box at [path] inside the box that starts at 0 of [buf], with offsets relative to [buf]. */
    private fun findBox(buf: ByteArray, vararg path: String): Box? {
        var start = headerSize(buf)
        var end = buf.size
        var found: Box? = null
        for (type in path) {
            val box = children(buf, start, end).firstOrNull { it.type == type } ?: return null
            found = box
            start = (box.offset + box.headerSize).toInt()
            end = (box.offset + box.size).toInt()
        }
        return found
    }

    /** Offset of the body (after the header) of the box at [path]. */
    private fun findPath(buf: ByteArray, vararg path: String): Int? =
        findBox(buf, *path)?.let { (it.offset + it.headerSize).toInt() }

    private fun ByteArray.sliceBox(box: Box) = copyOfRange(box.offset.toInt(), (box.offset + box.size).toInt())

    /** [container] (a whole box) with its first child of [type] replaced by [replacement]. */
    private fun replaceChild(container: ByteArray, type: String, replacement: ByteArray): ByteArray {
        val header = headerSize(container)
        val out = ByteArrayOutputStream()
        var replaced = false
        for (child in children(container, header, container.size)) {
            if (!replaced && child.type == type) {
                out.write(replacement)
                replaced = true
            } else {
                out.write(container, child.offset.toInt(), child.size.toInt())
            }
        }
        if (!replaced) throw MuxException("Missing '$type' box")
        return box(String(container, 4, 4, Charsets.ISO_8859_1), out.toByteArray())
    }

    private fun headerSize(box: ByteArray) = if (u32(box, 0) == 1L) 16 else 8

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.latin1()).put(payload).array()

    private fun fullBox(type: String, version: Int, payload: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

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
