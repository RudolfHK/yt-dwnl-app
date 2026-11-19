package io.github.rudolfhk.audiosaver.core.tag

import io.github.rudolfhk.audiosaver.core.engine.Thumbnail

/** Where to cut a video thumbnail to get a square album cover. Pure arithmetic; the pixels are platform code. */
object CoverGeometry {

    data class Rect(val left: Int, val top: Int, val size: Int)

    /** The largest thumbnail: YouTube's maxres is 1280x720 and has no letterbox bars. */
    fun best(thumbnails: List<Thumbnail>): Thumbnail? = thumbnails.maxByOrNull { it.width.toLong() * it.height }

    /**
     * Centre square of the picture area. YouTube's smaller thumbnails are 4:3 with black bars above
     * and below a 16:9 picture; [isDarkRow] lets those bars be skipped so they don't end up in the
     * cover. ("Topic" uploads put the album art in the middle, so the centre square *is* the cover.)
     */
    fun squareCrop(width: Int, height: Int, isDarkRow: (y: Int) -> Boolean): Rect {
        var top = 0
        var bottom = height
        while (top < height / 3 && isDarkRow(top)) top++
        while (bottom > height * 2 / 3 && isDarkRow(bottom - 1)) bottom--
        // Only treat it as letterboxing if both bars exist and are similar; otherwise it's the picture.
        if (top == 0 || height - bottom == 0 || kotlin.math.abs(top - (height - bottom)) > height / 20) {
            top = 0
            bottom = height
        }
        val pictureHeight = bottom - top
        val size = minOf(width, pictureHeight)
        return Rect(left = (width - size) / 2, top = top + (pictureHeight - size) / 2, size = size)
    }
}
