package io.github.rudolfhk.audiosaver

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rudolfhk.audiosaver.core.pipeline.MediaKind
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio
import io.github.rudolfhk.audiosaver.core.tag.Mp4Tagger
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import io.github.rudolfhk.audiosaver.core.video.DashMp4Muxer
import io.github.rudolfhk.audiosaver.download.MediaStoreAudioSink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Runs the video path on a real Android system (an emulator in CI) and reads the result the way the
 * phone does - MediaMetadataRetriever (what galleries show), MediaExtractor (what players demux),
 * MediaPlayer (can it play) and MediaStore (where it landed). The fixtures are 4 s DASH streams in
 * the same layout as YouTube's (fragmented MP4, 'dash' brand, sidx).
 */
@RunWith(AndroidJUnit4::class)
class VideoOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val work = File(context.cacheDir, "device-test").apply { deleteRecursively(); mkdirs() }
    private val savedUris = mutableListOf<Uri>()
    private val metadata = TrackMetadata("Device Test Tone", "Audio Saver Tests", 2026, "https://www.youtube.com/watch?v=aqz-KE-bpKQ")

    @After fun cleanUp() {
        savedUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        work.deleteRecursively()
    }

    private fun asset(name: String) = File(work, name).apply { testAssets.open(name).use { input -> outputStream().use { input.copyTo(it) } } }

    private fun mergedAndTagged(): File {
        val merged = File(work, "merged.mp4")
        DashMp4Muxer.mux(asset("dash_video.mp4"), asset("dash_audio.m4a"), merged)
        val tagged = File(work, "tagged.mp4")
        Mp4Tagger.tag(merged, tagged, metadata)
        return tagged
    }

    private fun retrieve(setSource: MediaMetadataRetriever.() -> Unit): Map<Int, String?> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setSource()
            return listOf(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
                MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO,
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                MediaMetadataRetriever.METADATA_KEY_TITLE,
                MediaMetadataRetriever.METADATA_KEY_ARTIST,
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE,
            ).associateWith { retriever.extractMetadata(it) }
        } finally {
            retriever.release()
        }
    }

    private fun assertPlayableVideo(meta: Map<Int, String?>) {
        val duration = meta[MediaMetadataRetriever.METADATA_KEY_DURATION]?.toLongOrNull()
        assertTrue("duration shown to galleries: $duration ms ($meta)", duration != null && abs(duration - 4000) < 300)
        assertEquals("has video", "yes", meta[MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO])
        assertEquals("has audio", "yes", meta[MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO])
        assertEquals("320", meta[MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH])
        assertEquals("240", meta[MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT])
        assertEquals("Device Test Tone", meta[MediaMetadataRetriever.METADATA_KEY_TITLE])
        assertEquals("Audio Saver Tests", meta[MediaMetadataRetriever.METADATA_KEY_ARTIST])
    }

    @Test fun mergedVideoIsReadLikeANormalVideo() {
        val file = mergedAndTagged()
        assertPlayableVideo(retrieve { setDataSource(file.path) })
    }

    @Test fun bothTracksDemuxWithSamplesAcrossTheWholeFile() {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mergedAndTagged().path)
            assertEquals("tracks", 2, extractor.trackCount)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            assertTrue(mimes.toString(), mimes.contains(MediaFormat.MIMETYPE_VIDEO_AVC) && mimes.contains(MediaFormat.MIMETYPE_AUDIO_AAC))
            (0 until extractor.trackCount).forEach(extractor::selectTrack)
            val samples = IntArray(2)
            val lastTimeUs = LongArray(2)
            while (extractor.sampleTrackIndex >= 0) {
                val track = extractor.sampleTrackIndex
                samples[track]++
                lastTimeUs[track] = extractor.sampleTime
                extractor.advance()
            }
            for (track in 0..1) {
                assertTrue("track $track ($mimes) samples=${samples[track]}", samples[track] > 50)
                assertTrue("track $track ends at ${lastTimeUs[track]} us", lastTimeUs[track] > 3_500_000)
            }
            // Seeking near the end must land there, not restart from the beginning.
            extractor.seekTo(3_000_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            assertTrue("seek landed at ${extractor.sampleTime}", extractor.sampleTime in 1_500_000..3_100_000)
        } finally {
            extractor.release()
        }
    }

    @Test fun mediaPlayerPreparesIt() {
        val player = MediaPlayer()
        try {
            player.setDataSource(mergedAndTagged().path)
            player.prepare()
            assertTrue("player duration ${player.duration}", abs(player.duration - 4000) < 300)
            assertTrue("video size", player.videoWidth == 320 && player.videoHeight == 240)
        } finally {
            player.release()
        }
    }

    @Test fun savedThroughMediaStoreIntoMoviesAndReadableFromThere() = runBlocking {
        val file = mergedAndTagged()
        val saved: SavedAudio = MediaStoreAudioSink(context).save(file, "Device Test Tone.mp4", "video/mp4", metadata, null, MediaKind.VIDEO)
        val uri = Uri.parse(saved.uri)
        savedUris += uri
        assertEquals(file.length(), saved.sizeBytes)
        assertEquals("Movies/AudioSaver", saved.location)
        assertTrue("saved in the Video collection: $uri", uri.toString().startsWith(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).toString()))
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("video/mp4", it.getString(0))
            assertTrue(it.getString(1).startsWith("Device Test Tone"))
        }
        assertPlayableVideo(retrieve { setDataSource(context, uri) })
    }

    @Test fun audioTagsAreReadByAndroid() = runBlocking {
        val tagged = File(work, "tagged.m4a")
        Mp4Tagger.tag(asset("dash_audio.m4a"), tagged, metadata.copy(album = "Device Album", trackNumber = 2, trackTotal = 9))
        val saved = MediaStoreAudioSink(context).save(tagged, "Device Test Tone.m4a", "audio/mp4", metadata, null, MediaKind.AUDIO)
        val uri = Uri.parse(saved.uri)
        savedUris += uri
        assertEquals("Music/AudioSaver", saved.location)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            assertEquals("Device Test Tone", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            assertEquals("Audio Saver Tests", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            assertEquals("Device Album", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).orEmpty().startsWith("2"))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            assertTrue("audio duration $duration", duration != null && abs(duration - 4000) < 300)
        } finally {
            retriever.release()
        }
    }
}
