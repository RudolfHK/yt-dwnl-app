package io.github.rudolfhk.audiosaver

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rudolfhk.audiosaver.core.audio.AudioAnalyzer
import io.github.rudolfhk.audiosaver.core.audio.PcmSink
import io.github.rudolfhk.audiosaver.core.audio.TimeRange
import io.github.rudolfhk.audiosaver.core.mp3.Mp3Exporter
import io.github.rudolfhk.audiosaver.core.opus.WebmOpusRemuxer
import io.github.rudolfhk.audiosaver.core.pipeline.MediaKind
import io.github.rudolfhk.audiosaver.core.tag.CoverArt
import io.github.rudolfhk.audiosaver.core.tag.Mp4Tagger
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import io.github.rudolfhk.audiosaver.core.video.DashMp4Muxer
import io.github.rudolfhk.audiosaver.download.MediaStoreAudioSink
import io.github.rudolfhk.audiosaver.media.BitmapCoverCropper
import io.github.rudolfhk.audiosaver.media.MediaCodecPcmDecoder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The Android halves of the advanced audio options, on a real system: MediaCodec decoding (used
 * for MP3, loudness and silence), the Bitmap cover cropper, and whether Android's own readers
 * (MediaMetadataRetriever, MediaExtractor, MediaPlayer, MediaStore) accept the Opus/MP3/M4A files
 * with covers. Fixtures: 1 s silence + 4 s pink noise + 1 s silence, as YouTube-style DASH M4A
 * (44.1 kHz AAC) and WebM Opus; a 4:3 thumbnail with black letterbox bars.
 */
@RunWith(AndroidJUnit4::class)
class AudioProcessingOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val work = File(context.cacheDir, "processing-test").apply { deleteRecursively(); mkdirs() }
    private val savedUris = mutableListOf<Uri>()
    private val decoder = MediaCodecPcmDecoder()

    @After fun cleanUp() {
        savedUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        work.deleteRecursively()
    }

    private fun asset(name: String) = File(work, name).apply { testAssets.open(name).use { input -> outputStream().use { input.copyTo(it) } } }

    private fun cover(): CoverArt = BitmapCoverCropper().cropSquare(testAssets.open("letterboxed_thumb.jpg").readBytes(), 720)!!

    private fun metadata(cover: CoverArt? = null) =
        TrackMetadata("Device Noise", "Audio Saver Tests", 2026, "https://www.youtube.com/watch?v=aqz-KE-bpKQ", cover = cover)

    private class Counting : PcmSink {
        var rate = 0
        var channels = 0
        var samples = 0L
        override fun start(sampleRate: Int, channels: Int) { rate = sampleRate; this.channels = channels }
        override fun write(samples: ShortArray, count: Int) { this.samples += count }
        val seconds get() = samples.toDouble() / channels / rate
    }

    @Test fun mediaCodecDecodesAacAndOpus() {
        for ((name, rate) in listOf("padded_audio.m4a" to 44_100, "padded_audio.webm" to 48_000)) {
            val sink = Counting()
            decoder.decode(asset(name), sink)
            assertEquals(name, rate, sink.rate)
            assertEquals(name, 2, sink.channels)
            assertTrue("$name decoded ${sink.seconds} s", abs(sink.seconds - 6.0) < 0.1)
        }
    }

    @Test fun loudnessAndSilenceMatchFfmpegThroughMediaCodec() {
        // ffmpeg ebur128 on the same files: -27.8 LUFS (AAC), -27.1 LUFS (Opus).
        for ((name, lufs) in listOf("padded_audio.m4a" to -27.8, "padded_audio.webm" to -27.1)) {
            val analyzer = AudioAnalyzer()
            decoder.decode(asset(name), analyzer)
            val gain = analyzer.replayGain()!!
            assertTrue("$name: ${gain.loudnessLufs} vs $lufs", abs(gain.loudnessLufs - lufs) < 0.5)
            val audible = analyzer.audibleRange()
            assertTrue("$name start ${audible.startMs}", audible.startMs in 700L..1000L)
            assertTrue("$name end ${audible.endMs}", audible.endMs!! in 5000L..5300L)
        }
    }

    @Test fun coverCropSkipsLetterboxBarsAndIsSquare() {
        val art = cover()
        assertEquals(art.width, art.height)
        assertEquals(270, art.width) // the 480x270 picture between the bars, centre square
        val bitmap = BitmapFactory.decodeByteArray(art.jpeg, 0, art.jpeg.size)
        assertEquals(270, bitmap.width)
        // Top row is picture, not a black bar.
        val px = bitmap.getPixel(art.width / 2, 2)
        assertTrue("top row is black: ${Integer.toHexString(px)}", (px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF) > 60)
    }

    private fun embeddedPicture(file: File): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.embeddedPicture
        } finally {
            r.release()
        }
    }

    private fun mimeOf(file: File): String? {
        val e = MediaExtractor()
        return try {
            e.setDataSource(file.path)
            (0 until e.trackCount).map { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.firstOrNull { it?.startsWith("audio/") == true }
        } finally {
            e.release()
        }
    }

    private fun playableSeconds(file: File): Double {
        val player = MediaPlayer()
        return try {
            player.setDataSource(file.path)
            player.prepare()
            player.duration / 1000.0
        } finally {
            player.release()
        }
    }

    @Test fun m4aWithCoverAndTrim() {
        val trimmed = File(work, "trimmed.m4a")
        DashMp4Muxer.trimAudio(asset("padded_audio.m4a"), trimmed, TimeRange(1_000, 5_000))
        val tagged = File(work, "tagged.m4a")
        Mp4Tagger.tag(trimmed, tagged, metadata(cover()))
        assertNotNull("cover visible to Android", embeddedPicture(tagged))
        assertEquals("audio/mp4a-latm", mimeOf(tagged))
        val seconds = playableSeconds(tagged)
        assertTrue("trimmed to $seconds s", abs(seconds - 4.0) < 0.1)
        val sink = Counting()
        decoder.decode(tagged, sink)
        assertTrue("decodes to ${sink.seconds} s", abs(sink.seconds - 4.0) < 0.1)
    }

    @Test fun opusIsPlayableAndSavedAsOpus() {
        val opus = File(work, "song.opus")
        WebmOpusRemuxer.remux(asset("padded_audio.webm"), opus, metadata(cover()))
        assertEquals("audio/opus", mimeOf(opus))
        assertTrue(abs(playableSeconds(opus) - 6.0) < 0.15)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(opus.path)
            assertEquals("Device Noise", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            assertEquals("Audio Saver Tests", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        } finally {
            r.release()
        }
        assertSavedName(opus, "Audio Saver Tests - Device Noise.opus", "audio/ogg")
    }

    @Test fun mp3ExportWithCoverIsPlayableAndSavedAsMp3() {
        val mp3 = File(work, "song.mp3")
        Mp3Exporter.export(asset("padded_audio.m4a"), decoder, mp3, metadata(cover()), TimeRange(1_000, 5_000))
        assertEquals("audio/mpeg", mimeOf(mp3))
        assertNotNull("ID3 cover visible to Android", embeddedPicture(mp3))
        assertTrue(abs(playableSeconds(mp3) - 4.0) < 0.15)
        assertSavedName(mp3, "Audio Saver Tests - Device Noise.mp3", "audio/mpeg")
    }

    /** MediaStore must keep our extension (it may append one it thinks matches the MIME type). */
    private fun assertSavedName(file: File, name: String, mime: String) {
        val saved = runBlocking { MediaStoreAudioSink(context).save(file, name, mime, metadata(), "device-test", MediaKind.AUDIO) }
        savedUris += Uri.parse(saved.uri)
        assertEquals(name, saved.displayName)
        assertEquals(file.length(), saved.sizeBytes)
    }
}
