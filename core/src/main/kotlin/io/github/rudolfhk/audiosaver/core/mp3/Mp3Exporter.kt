package io.github.rudolfhk.audiosaver.core.mp3

import io.github.rudolfhk.audiosaver.core.audio.PcmDecoder
import io.github.rudolfhk.audiosaver.core.audio.TimeRange
import io.github.rudolfhk.audiosaver.core.audio.TrimmingSink
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import java.io.File

/** Decodes [source], re-encodes it as MP3 and writes `ID3v2 + MP3 frames + ID3v1` to [output]. */
object Mp3Exporter {
    fun export(source: File, decoder: PcmDecoder, output: File, metadata: TrackMetadata, range: TimeRange = TimeRange.ALL) {
        output.outputStream().buffered(256 * 1024).use { out ->
            out.write(Id3Writer.v2(metadata))
            val encoder = Mp3Encoder(out)
            decoder.decode(source, if (range.isWholeTrack) encoder else TrimmingSink(range, encoder))
            encoder.finish()
            out.write(Id3Writer.v1(metadata))
        }
    }
}
