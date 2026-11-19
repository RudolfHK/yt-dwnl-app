package io.github.rudolfhk.audiosaver.core.tag

import io.github.rudolfhk.audiosaver.core.engine.Thumbnail
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverGeometryTest {
    @Test fun `16 to 9 thumbnail gives the centre square`() {
        assertEquals(CoverGeometry.Rect(280, 0, 720), CoverGeometry.squareCrop(1280, 720) { false })
    }

    @Test fun `letterboxed 4 to 3 thumbnail skips the black bars`() {
        // 480x360 with a 270 px tall picture in the middle: 45 px bars top and bottom.
        val rect = CoverGeometry.squareCrop(480, 360) { y -> y < 45 || y >= 315 }
        assertEquals(CoverGeometry.Rect(105, 45, 270), rect)
    }

    @Test fun `a dark picture is not mistaken for letterboxing`() {
        // Dark sky on top only: not symmetric bars, so the whole height is the picture.
        assertEquals(CoverGeometry.Rect(60, 0, 360), CoverGeometry.squareCrop(480, 360) { y -> y < 100 })
    }

    @Test fun `largest thumbnail wins`() {
        val best = CoverGeometry.best(listOf(Thumbnail("hq", 480, 360), Thumbnail("maxres", 1280, 720), Thumbnail("default", 120, 90)))
        assertEquals("maxres", best?.url)
    }
}
