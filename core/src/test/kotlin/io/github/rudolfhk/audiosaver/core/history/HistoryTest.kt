package io.github.rudolfhk.audiosaver.core.history

import io.github.rudolfhk.audiosaver.core.engine.CollectionItem
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.CollectionKind
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.BatchEntry
import io.github.rudolfhk.audiosaver.core.pipeline.BatchItemState
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadOptions
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.ItemStatus
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio
import io.github.rudolfhk.audiosaver.core.pipeline.TrackContext
import io.github.rudolfhk.audiosaver.core.pipeline.VideoResult
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val id = VideoId.of("aqz-KE-bpKQ")
    private var now = 1_000L
    private val recorder = HistoryRecorder { now++ }
    private val audioFile = SavedAudio("Blender - Big Buck Bunny.m4a", "Music/AudioSaver", "content://media/1", "audio/mp4", 3_000_000)
    private val videoFile = SavedAudio("Blender - Big Buck Bunny.mp4", "Movies/AudioSaver", "content://media/2", "video/mp4", 30_000_000)

    private fun progress(options: DownloadOptions) = listOf(
        DownloadState.Resolving(id),
        DownloadState.Downloading(id, "Big Buck Bunny", 0, 100, uploader = "Blender"),
        DownloadState.Downloading(id, "Big Buck Bunny", 50, 100, uploader = "Blender"),
        DownloadState.Saving(id, "Big Buck Bunny"),
    ).map { recorder.onSingleState(id, options, it) }

    // ---------------------------------------------------------------- recorder: singles

    @Test fun `progress is never recorded, the finished download is recorded once with title and artist`() {
        val options = DownloadOptions.AUDIO_ONLY
        assertTrue(progress(options).all { it == null })
        val entry = assertNotNull(recorder.onSingleState(id, options, DownloadState.Done(id, audioFile, null)))
        assertEquals("Big Buck Bunny", entry.title)
        assertEquals("Blender", entry.artist)
        assertEquals(HistoryStatus.SAVED, entry.status)
        assertEquals("Blender - Big Buck Bunny.m4a", entry.audio?.saved?.name)
        assertNull(entry.video, "video wasn't requested")
    }

    @Test fun `audio plus failed video is a partial success`() {
        val options = DownloadOptions(video = VideoQuality.P720)
        progress(options)
        val entry = recorder.onSingleState(
            id, options, DownloadState.Done(id, audioFile, null, VideoResult.Failed(FailureReason.STORAGE, "Not enough free space")),
        )!!
        assertEquals(HistoryStatus.PARTIAL, entry.status)
        assertEquals(FailureReason.STORAGE, entry.video?.reason)
        assertEquals("P720", entry.videoQuality)
        assertEquals(VideoQuality.P720, entry.requestedQuality)
    }

    @Test fun `video only saved`() {
        val options = DownloadOptions.videoOnly(VideoQuality.BEST)
        progress(options)
        val entry = recorder.onSingleState(id, options, DownloadState.Done(id, null, null, VideoResult.Saved(videoFile, 1920, 1080, 30, null)))!!
        assertEquals(HistoryStatus.SAVED, entry.status)
        assertNull(entry.audio)
        assertEquals("1080p", entry.video?.saved?.quality)
    }

    @Test fun `failures keep the reason for every requested format, even before a title was known`() {
        val options = DownloadOptions(video = VideoQuality.BEST)
        recorder.onSingleState(id, options, DownloadState.Resolving(id))
        val entry = recorder.onSingleState(id, options, DownloadState.Failed(id, FailureReason.UNAVAILABLE, "Video unavailable"))!!
        assertEquals(HistoryStatus.FAILED, entry.status)
        assertEquals("Video aqz-KE-bpKQ", entry.title)
        assertEquals(FailureReason.UNAVAILABLE, entry.audio?.reason)
        assertEquals("Video unavailable", entry.video?.failureDetail)
    }

    @Test fun `a cancelled download is not history`() {
        progress(DownloadOptions.AUDIO_ONLY)
        assertNull(recorder.onSingleState(id, DownloadOptions.AUDIO_ONLY, DownloadState.Cancelled(id)))
    }

    // ---------------------------------------------------------------- recorder: batches

    private fun batch(vararg statuses: ItemStatus, finished: Boolean = false) = BatchState(
        "Mix - Blender", CollectionKind.MIX,
        statuses.mapIndexed { i, status ->
            BatchItemState(BatchEntry(CollectionItem(VideoId.of("v" + "$i".padStart(10, '0')), "Song $i", "Artist $i", 60), TrackContext.NONE), status)
        },
        finished,
    )

    @Test fun `each batch song is recorded once, when it finishes`() {
        val saved = ItemStatus.Saved(audioFile, null)
        val failed = ItemStatus.Failed(FailureReason.UNAVAILABLE, "Video unavailable")
        val active = ItemStatus.Active(DownloadState.Downloading(VideoId.of("v0000000001"), "Song 1", 1, 2))
        assertEquals(emptyList(), recorder.onBatchState(1, batch(ItemStatus.Queued, ItemStatus.Queued)))
        val first = recorder.onBatchState(1, batch(saved, active))
        assertEquals(listOf("Song 0"), first.map { it.title })
        assertEquals("Mix - Blender", first.single().collection)
        assertEquals(emptyList(), recorder.onBatchState(1, batch(saved, active)), "repeated progress updates add nothing")
        val second = recorder.onBatchState(1, batch(saved, failed, finished = true))
        assertEquals(listOf(HistoryStatus.FAILED), second.map { it.status })
    }

    @Test fun `cancelled batch songs are not recorded, a retry run records again`() {
        val saved = ItemStatus.Saved(audioFile, null)
        assertEquals(1, recorder.onBatchState(1, batch(saved, ItemStatus.Cancelled, ItemStatus.Cancelled, finished = true)).size)
        assertEquals(1, recorder.onBatchState(2, batch(saved)).size, "new run id: same index is a new download")
    }

    // ---------------------------------------------------------------- log

    private fun entry(n: Int, status: HistoryStatus = HistoryStatus.SAVED) = HistoryEntry(
        timeMillis = n.toLong(),
        videoId = "v" + "$n".padStart(10, '0'),
        title = "Song $n",
        audio = if (status == HistoryStatus.SAVED) FileOutcome.saved(audioFile) else FileOutcome.failed(FailureReason.NETWORK, "timeout"),
    )

    @Test fun `survives a restart, newest first`() = runBlocking {
        val file = File(tmp.root, "history.jsonl")
        HistoryLog(file).apply { load(); add(entry(1)); add(entry(2, HistoryStatus.FAILED)) }
        val reloaded = HistoryLog(file).apply { load() }
        assertEquals(listOf("Song 2", "Song 1"), reloaded.entries.value.map { it.title })
        assertEquals(FailureReason.NETWORK, reloaded.entries.value.first().audio?.reason)
        assertEquals("Blender - Big Buck Bunny.m4a", reloaded.entries.value.last().audio?.saved?.name)
    }

    @Test fun `a corrupt or half-written line is skipped, not fatal`() = runBlocking {
        val file = File(tmp.root, "history.jsonl")
        HistoryLog(file).apply { load(); add(entry(1)) }
        file.appendText("{\"timeMillis\":2,\"videoId\":\"x\",\"tit")
        file.appendText("\nnot json at all\n")
        val reloaded = HistoryLog(file).apply { load(); add(entry(3)) }
        assertEquals(listOf("Song 3", "Song 1"), reloaded.entries.value.map { it.title })
        assertEquals(listOf("Song 3", "Song 1"), HistoryLog(file).apply { load() }.entries.value.map { it.title })
    }

    @Test fun `old entries beyond the cap are dropped and the file is compacted`() = runBlocking {
        val file = File(tmp.root, "history.jsonl")
        val log = HistoryLog(file, maxEntries = 10).apply { load() }
        repeat(25) { log.add(entry(it)) }
        assertEquals(10, log.entries.value.size)
        assertEquals("Song 24", log.entries.value.first().title)
        assertTrue(file.readLines().size <= 12, "file not compacted: ${file.readLines().size} lines")
        assertEquals(log.entries.value, HistoryLog(file, maxEntries = 10).apply { load() }.entries.value)
    }

    @Test fun `clear empties memory and disk`() = runBlocking {
        val file = File(tmp.root, "history.jsonl")
        val log = HistoryLog(file).apply { load(); add(entry(1)) }
        log.clear()
        assertTrue(log.entries.value.isEmpty())
        assertTrue(HistoryLog(file).apply { load() }.entries.value.isEmpty())
    }

    @Test fun `already saved lookup ignores failures`() = runBlocking {
        val log = HistoryLog(File(tmp.root, "h.jsonl")).apply { load(); add(entry(1)); add(entry(2, HistoryStatus.FAILED)) }
        assertEquals("Song 1", log.lastSaved(entry(1).videoId)?.title)
        assertNull(log.lastSaved(entry(2).videoId))
        assertNull(log.lastSaved("nothing"))
    }

    @Test fun `unknown failure reasons from older versions read back as UNKNOWN`() {
        assertEquals(FailureReason.UNKNOWN, FileOutcome(failureReason = "REASON_FROM_THE_FUTURE").reason)
    }

    // ---------------------------------------------------------------- retry

    private val failedAudio = FileOutcome.failed(FailureReason.NETWORK, "timeout")
    private val failedVideo = FileOutcome.failed(FailureReason.STORAGE, "full")
    private fun h(audio: FileOutcome?, video: FileOutcome?, quality: String? = "P480") =
        HistoryEntry(1, id.value, "t", audio = audio, video = video, videoQuality = quality.takeIf { video != null })

    @Test fun `retry asks again only for what failed`() {
        assertEquals(DownloadOptions.videoOnly(VideoQuality.P480), h(FileOutcome.saved(audioFile), failedVideo).retryOptions(), "partial: never a second audio copy")
        assertEquals(DownloadOptions(saveAudio = true, video = VideoQuality.P480), h(failedAudio, failedVideo).retryOptions())
        assertEquals(DownloadOptions.AUDIO_ONLY, h(failedAudio, null).retryOptions())
        assertEquals(DownloadOptions.videoOnly(VideoQuality.BEST), h(null, failedVideo, quality = null).retryOptions())
    }
}
