package io.github.rudolfhk.audiosaver.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import io.github.rudolfhk.audiosaver.core.pipeline.ImageCropper
import io.github.rudolfhk.audiosaver.core.tag.CoverArt
import io.github.rudolfhk.audiosaver.core.tag.CoverGeometry
import java.io.ByteArrayOutputStream

/** Thumbnail -> JPEG covers: a centred square for songs, the 16:9 picture for videos (letterbox bars skipped). */
class BitmapCoverCropper : ImageCropper {

    override fun cropSquare(image: ByteArray, maxSize: Int): CoverArt? = withBitmap(image) { source ->
        val rect = CoverGeometry.squareCrop(source.width, source.height) { y -> isDarkRow(source, y) }
        val size = minOf(rect.size, maxSize)
        render(source, Rect(rect.left, rect.top, rect.left + rect.size, rect.top + rect.size), size, size)
    }

    /** For the MP4: the whole picture in its own shape (bars removed), at most [maxWidth] wide. */
    override fun cropPicture(image: ByteArray, maxWidth: Int): CoverArt? = withBitmap(image) { source ->
        val area = CoverGeometry.pictureArea(source.width, source.height) { y -> isDarkRow(source, y) }
        val width = minOf(area.width, maxWidth)
        val height = maxOf(1, area.height * width / area.width)
        render(source, Rect(area.left, area.top, area.left + area.width, area.top + area.height), width, height)
    }

    private inline fun withBitmap(image: ByteArray, block: (Bitmap) -> CoverArt?): CoverArt? {
        val source = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return null
        return try {
            block(source)
        } finally {
            source.recycle()
        }
    }

    private fun render(source: Bitmap, from: Rect, width: Int, height: Int): CoverArt? {
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(target).drawBitmap(source, from, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            val out = ByteArrayOutputStream()
            if (!target.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            return CoverArt(out.toByteArray(), width, height)
        } finally {
            target.recycle()
        }
    }

    /** A row of a letterbox bar: every sampled pixel close to black. */
    private fun isDarkRow(bitmap: Bitmap, y: Int): Boolean {
        val step = maxOf(1, bitmap.width / 32)
        var x = 0
        while (x < bitmap.width) {
            val c = bitmap.getPixel(x, y)
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            if (r > DARK || g > DARK || b > DARK) return false
            x += step
        }
        return true
    }

    private companion object {
        const val JPEG_QUALITY = 90
        const val DARK = 24
    }
}
