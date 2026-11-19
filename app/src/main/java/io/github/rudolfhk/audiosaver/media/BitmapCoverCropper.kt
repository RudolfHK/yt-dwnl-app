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

/** Thumbnail -> centred square JPEG (letterbox bars skipped), at most [maxSize] pixels wide. */
class BitmapCoverCropper : ImageCropper {

    override fun cropSquare(image: ByteArray, maxSize: Int): CoverArt? {
        val source = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return null
        try {
            val rect = CoverGeometry.squareCrop(source.width, source.height) { y -> isDarkRow(source, y) }
            val size = minOf(rect.size, maxSize)
            val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                Canvas(square).drawBitmap(
                    source,
                    Rect(rect.left, rect.top, rect.left + rect.size, rect.top + rect.size),
                    Rect(0, 0, size, size),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                val out = ByteArrayOutputStream()
                if (!square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
                return CoverArt(out.toByteArray(), size, size)
            } finally {
                square.recycle()
            }
        } finally {
            source.recycle()
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
