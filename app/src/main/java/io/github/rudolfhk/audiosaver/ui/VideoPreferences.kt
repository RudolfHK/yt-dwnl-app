package io.github.rudolfhk.audiosaver.ui

import android.content.Context
import io.github.rudolfhk.audiosaver.core.video.VideoQuality

/** The "also save video" switch and quality, remembered between launches. */
class VideoPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("video", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var quality: VideoQuality
        get() = VideoQuality.fromName(prefs.getString(KEY_QUALITY, null))
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_QUALITY = "quality"
    }
}
