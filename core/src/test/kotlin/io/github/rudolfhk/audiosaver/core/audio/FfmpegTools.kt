package io.github.rudolfhk.audiosaver.core.audio

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Test helpers around the ffmpeg CLI, the independent reference the audio code is checked against. */
object FfmpegTools {
    val available: Boolean by lazy { runCatching { run("ffmpeg", "-version").first == 0 && run("ffprobe", "-version").first == 0 }.getOrDefault(false) }

    fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(180, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    fun make(file: File, vararg args: String): File {
        val (code, log) = run("ffmpeg", "-v", "error", "-y", *args, file.absolutePath)
        check(code == 0) { log }
        return file
    }

    /** ffmpeg's own EBU R128 integrated loudness (LUFS) of [file]. */
    fun integratedLoudness(file: File): Double {
        val (_, log) = run("ffmpeg", "-nostats", "-i", file.absolutePath, "-af", "ebur128", "-f", "null", "-")
        val summary = log.substringAfterLast("Integrated loudness:")
        return Regex("""I:\s+(-?[0-9.]+) LUFS""").find(summary)!!.groupValues[1].toDouble()
    }

    fun probe(file: File, entries: String): List<String> =
        run("ffprobe", "-v", "error", "-show_entries", entries, "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath)
            .second.lines().map(String::trim).filter(String::isNotEmpty)

    fun probeTags(file: File, entries: String = "format_tags:stream_tags"): Map<String, String> =
        run("ffprobe", "-v", "error", "-show_entries", entries, "-of", "default=noprint_wrappers=1", file.absolutePath)
            .second.lines().filter { it.startsWith("TAG:") }
            .associate { it.removePrefix("TAG:").substringBefore('=').lowercase() to it.substringAfter('=') }

    fun decodedMd5(file: File, stream: String = "a"): String =
        run("ffmpeg", "-v", "error", "-i", file.absolutePath, "-map", "0:$stream", "-f", "md5", "-").second.trim()

    fun decodeErrors(file: File): String = run("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "null", "-").second.trim()

    fun durationSeconds(file: File): Double = probe(file, "format=duration").single().toDouble()

    /** Decoded interleaved 16-bit samples at the file's own rate and channel count. */
    fun decodedPcm(file: File, streamSpecifier: String = "a:0"): ShortArray {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-map", "0:$streamSpecifier", "-f", "s16le", "-acodec", "pcm_s16le", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.use { it.readBytes() }
        p.waitFor()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    /**
     * Why two files that should decode identically don't: sample counts, the frame offset at
     * which they line up (if any), and what ffprobe sees of the padding. For assertion messages.
     */
    fun explainDecodeDifference(expected: File, actual: File, channels: Int = 2): String {
        val a = decodedPcm(expected)
        val b = decodedPcm(actual)
        val framesA = a.size / channels
        val framesB = b.size / channels
        // Find k with b[i] == a[i + k] over a window from the middle (k > 0: actual starts later in the audio).
        val window = minOf(4800, framesA / 4, framesB / 4)
        val mid = framesB / 2
        val offset = (-4000..4000).firstOrNull { k ->
            (0 until window).all { i ->
                val ia = mid + i + k
                ia in 0 until framesA && (0 until channels).all { c -> b[(mid + i) * channels + c] == a[ia * channels + c] }
            }
        }
        fun padding(file: File) = run(
            "ffprobe", "-v", "error", "-show_entries", "stream=start_pts,start_time,duration_ts,initial_padding,codec_name",
            "-of", "compact", file.absolutePath,
        ).second.trim().replace('\n', ' ')
        return "decoded frames: expected=$framesA actual=$framesB (diff ${framesB - framesA}); " +
            "aligned at offset ${offset ?: "none in ±4000"}; " +
            "expected: ${padding(expected)}; actual: ${padding(actual)}; ffmpeg ${run("ffmpeg", "-version").second.lineSequence().first()}"
    }

    /** What a player actually outputs: decoded samples (encoder delay / pre-skip removed) at 48 kHz. */
    fun decodedSeconds(file: File, streamSpecifier: String = "a:0"): Double {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-map", "0:$streamSpecifier", "-ac", "1", "-ar", "48000", "-f", "s16le", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        var bytes = 0L
        p.inputStream.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                bytes += n
            }
        }
        p.waitFor()
        return bytes / 2 / 48000.0
    }
}

/** [PcmDecoder] for JVM tests: ffmpeg decodes to 16-bit PCM, at the file's own rate and channel count. */
class FfmpegPcmDecoder : PcmDecoder {
    override fun decode(file: File, sink: PcmSink) {
        val info = FfmpegTools.probe(file, "stream=sample_rate,channels")
        if (info.size < 2) throw IOException("ffprobe could not read $file: $info")
        val sampleRate = info[0].toInt()
        val channels = info[1].toInt()
        sink.start(sampleRate, channels)
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "s16le", "-acodec", "pcm_s16le", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.inputStream.use { input ->
            val bytes = ByteArray(64 * 1024)
            val shorts = ShortArray(bytes.size / 2)
            var carry = 0
            while (true) {
                val n = input.read(bytes, carry, bytes.size - carry)
                if (n < 0) break
                val total = carry + n
                val usable = total - total % (2 * channels)
                ByteBuffer.wrap(bytes, 0, usable).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, usable / 2)
                sink.write(shorts, usable / 2)
                carry = total - usable
                System.arraycopy(bytes, usable, bytes, 0, carry)
            }
        }
        if (p.waitFor() != 0) throw IOException("ffmpeg failed to decode $file")
    }
}
