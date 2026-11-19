package io.github.rudolfhk.audiosaver.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.rudolfhk.audiosaver.core.audio.PcmDecoder
import io.github.rudolfhk.audiosaver.core.audio.PcmSink
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes the downloaded M4A (AAC) or WebM (Opus) with the phone's own decoders, to 16-bit PCM,
 * for MP3 export and loudness/silence analysis. Synchronous; the pipeline calls it off the main
 * thread and checks for cancellation between steps.
 */
class MediaCodecPcmDecoder : PcmDecoder {

    override fun decode(file: File, sink: PcmSink) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in ${file.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // Ask for 16-bit output explicitly; some decoders default to float on newer Android.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IOException("No decoder for $mime on this phone: ${e.message}", e)
            }
            codec.configure(format, null, null, 0)
            codec.start()
            pump(extractor, codec, sink)
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // MediaCodec reports most problems as IllegalStateException / CodecException.
            throw IOException("Decoding failed: ${e.message ?: e::class.java.simpleName}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, sink: PcmSink) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var started = false
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var samples = ShortArray(0)
        var idleRounds = 0
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    sink.start(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    started = true
                }
                out >= 0 -> {
                    idleRounds = 0
                    if (!started) {
                        val f = codec.getOutputFormat(out)
                        sink.start(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                        started = true
                    }
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(out)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val count: Int
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floats = buffer.asFloatBuffer()
                            count = floats.remaining()
                            if (samples.size < count) samples = ShortArray(count)
                            for (i in 0 until count) {
                                samples[i] = (floats.get(i).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            count = shorts.remaining()
                            if (samples.size < count) samples = ShortArray(count)
                            shorts.get(samples, 0, count)
                        }
                        sink.write(samples, count)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                // A decoder that stops answering would otherwise spin here forever.
                inputDone && ++idleRounds > MAX_IDLE_ROUNDS -> throw IOException("Decoder stopped producing output")
            }
            if (Thread.currentThread().isInterrupted) throw IOException("Interrupted")
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_ROUNDS = 500 // 5 s without output after the input ended
    }
}
