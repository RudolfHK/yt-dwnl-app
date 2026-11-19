package io.github.rudolfhk.audiosaver.core.mp3

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import io.github.rudolfhk.audiosaver.core.audio.PcmSink
import java.io.IOException
import java.io.OutputStream

/**
 * Encodes PCM to MP3 frames with LAME (jump3r, a pure-Java port - no native code on Android).
 *
 * Constant 192 kbps: old car stereos and players handle CBR best, and without a VBR header
 * (which this low-level API doesn't write) variable-bitrate files show wrong durations there.
 * The modules are wired by hand, exactly as jump3r's own LameEncoder does, because that class
 * depends on javax.sound, which Android doesn't have.
 */
class Mp3Encoder(private val out: OutputStream, private val bitrateKbps: Int = 192, private val quality: Int = 5) : PcmSink {

    private val lame = Lame()
    private lateinit var flags: LameGlobalFlags
    private var channels = 0
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var buffer = ByteArray(0)

    override fun start(sampleRate: Int, channels: Int) {
        if (channels !in 1..2) throw IOException("MP3 export supports mono and stereo, not $channels channels")
        this.channels = channels
        val gainAnalysis = GainAnalysis()
        val bitStream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbrTag = VBRTag()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val mpgInterface = Interface()
        val common = Common()
        lame.setModules(gainAnalysis, bitStream, presets, quantizePvt, quantize, vbrTag, version, id3, mpg)
        bitStream.setModules(gainAnalysis, mpg, version, vbrTag)
        id3.setModules(bitStream, version)
        presets.setModules(lame)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbrTag.setModules(lame, bitStream, version)
        GetAudio().setModules(parse, mpg)
        parse.setModules(version, id3, presets)
        mpg.setModules(mpgInterface, common)
        mpgInterface.setModules(vbrTag, common)

        flags = lame.lame_init()
        flags.num_channels = channels
        flags.in_samplerate = sampleRate
        flags.out_samplerate = sampleRate
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        flags.VBR = VbrMode.vbr_off
        flags.brate = bitrateKbps
        flags.quality = quality
        flags.write_id3tag_automatic = false
        flags.findReplayGain = false
        if (lame.lame_init_params(flags) < 0) throw IOException("LAME rejected $sampleRate Hz x $channels channels")
    }

    override fun write(samples: ShortArray, count: Int) {
        val frames = count / channels
        if (frames == 0) return
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
            buffer = ByteArray((1.25 * frames + 7200).toInt())
        }
        for (f in 0 until frames) {
            // LAME's int API takes samples scaled to 32 bits.
            left[f] = samples[f * channels].toInt() shl 16
            right[f] = if (channels == 2) samples[f * channels + 1].toInt() shl 16 else left[f]
        }
        val n = lame.lame_encode_buffer_int(flags, left, right, frames, buffer, 0, buffer.size)
        if (n < 0) throw IOException("LAME encoding failed ($n)")
        out.write(buffer, 0, n)
    }

    /** Writes the last frames; call once after the final [write]. */
    fun finish() {
        if (buffer.size < 7200) buffer = ByteArray(7200)
        val n = lame.lame_encode_flush(flags, buffer, 0, buffer.size)
        if (n > 0) out.write(buffer, 0, n)
        lame.lame_close(flags)
    }
}
