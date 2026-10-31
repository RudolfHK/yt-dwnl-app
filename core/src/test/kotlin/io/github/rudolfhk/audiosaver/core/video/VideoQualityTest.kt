package io.github.rudolfhk.audiosaver.core.video

import io.github.rudolfhk.audiosaver.core.engine.VideoStreamSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoQualityTest {

    private fun s(height: Int, fps: Int = 30, codec: String? = "avc1.640028", bitrate: Int = height * 1000) =
        VideoStreamSource("https://x/$height-$fps-$codec", height * 16 / 9, height, fps, codec, bitrate, height * 10_000L)

    // What YouTube's visionOS client returns for a typical video (see the README).
    private val youtube = listOf(
        s(1080), s(1080, codec = "av01.0.08M.08"), s(1080, codec = "vp9"),
        s(720), s(720, fps = 60), s(720, codec = "av01.0.05M.08"),
        s(480), s(360), s(240), s(144),
    )

    @Test fun `best is the tallest h264 up to 1080p, never av1 or vp9`() {
        val chosen = VideoQuality.BEST.pick(youtube + s(1440, codec = "vp9") + s(2160, codec = "av01.0.12M.08"))!!
        assertEquals(1080, chosen.height)
        assertEquals("avc1.640028", chosen.codec)
    }

    @Test fun `a ceiling picks the tallest fitting stream, preferring 60fps`() {
        assertEquals(720 to 60, VideoQuality.P720.pick(youtube)!!.let { it.height to it.fps })
        assertEquals(480, VideoQuality.P480.pick(youtube)!!.height)
        assertEquals(360, VideoQuality.P360.pick(youtube)!!.height)
    }

    @Test fun `if everything is taller than asked, take the smallest`() {
        assertEquals(720, VideoQuality.P360.pick(listOf(s(1080), s(720)))!!.height)
    }

    @Test fun `no h264 means no video`() {
        assertNull(VideoQuality.BEST.pick(listOf(s(1080, codec = "vp9"), s(720, codec = "av01.0.05M.08"))))
        assertNull(VideoQuality.BEST.pick(emptyList()))
    }

    @Test fun `stored names round-trip, unknown falls back to best`() {
        assertEquals(VideoQuality.P480, VideoQuality.fromName("P480"))
        assertEquals(VideoQuality.BEST, VideoQuality.fromName(null))
        assertEquals(VideoQuality.BEST, VideoQuality.fromName("8K"))
    }
}
