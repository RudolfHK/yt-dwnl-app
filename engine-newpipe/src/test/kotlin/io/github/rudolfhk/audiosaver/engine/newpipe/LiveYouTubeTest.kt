package io.github.rudolfhk.audiosaver.engine.newpipe

import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.AudioDownloadPipeline
import io.github.rudolfhk.audiosaver.core.pipeline.DirectoryAudioSink
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Talks to the real YouTube. Off by default (network, flaky by nature); run with
 *   LIVE_YOUTUBE=1 ./gradlew :engine-newpipe:test --tests '*LiveYouTubeTest*'
 * This is the canary for "YouTube changed something": run it before blaming the app.
 */
class LiveYouTubeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val client = OkHttpClient()
    private val engine by lazy { NewPipeExtractionEngine(client) }

    @Before fun onlyWhenRequested() = assumeTrue("set LIVE_YOUTUBE=1 to run", System.getenv("LIVE_YOUTUBE") == "1")

    /** "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", CC BY 3.0. */
    private val creativeCommonsVideo = VideoId.of("aqz-KE-bpKQ")

    @Test fun `resolves a public creative-commons video to an m4a stream`() = runBlocking {
        val audio = engine.resolve(creativeCommonsVideo)
        println("Resolved: $audio")
        assertTrue(audio.title.contains("Big Buck Bunny"), audio.title)
        assertEquals(AudioContainer.M4A, audio.stream.container)
        assertTrue((audio.stream.contentLength ?: 0) > 100_000)
    }

    @Test fun `full pipeline saves a tagged, non-empty file`() = runBlocking {
        val out = File(tmp.root, "Music")
        val states = mutableListOf<DownloadState>()
        AudioDownloadPipeline(engine, HttpAudioDownloader(client), DirectoryAudioSink(out), File(tmp.root, "work"))
            .run(creativeCommonsVideo) { states += it; if (it !is DownloadState.Downloading) println(it) }
        val done = assertIs<DownloadState.Done>(states.last(), "ended in ${states.last()}")
        assertEquals(null, done.taggingWarning)
        assertTrue(File(out, done.saved.displayName).length() > 100_000)
    }

    @Test fun `unavailable video id gives a specific failure`() = runBlocking {
        val states = mutableListOf<DownloadState>()
        AudioDownloadPipeline(engine, HttpAudioDownloader(client), DirectoryAudioSink(tmp.root), tmp.root)
            .run(VideoId.of("aaaaaaaaaaa")) { states += it }
        val failed = assertIs<DownloadState.Failed>(states.last())
        println("Failure as the user sees it: ${failed.reason.userTitle} - ${failed.detail}")
        assertEquals(FailureReason.UNAVAILABLE, failed.reason)
    }
}
