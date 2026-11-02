package io.github.rudolfhk.audiosaver.ui

import android.content.Context
import io.github.rudolfhk.audiosaver.core.video.VideoQuality

/** Which files a single link produces (audio, video or both) and the video quality, remembered between launches. */
class FormatPreferences(context: Context) {
    // Same file and keys as before, so the earlier "also save video" choice carries over.
    private val prefs = context.applicationContext.getSharedPreferences("video", Context.MODE_PRIVATE)

    var audio: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO, value).apply()

    var video: Boolean
        get() = prefs.getBoolean(KEY_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO, value).apply()

    var quality: VideoQuality
        get() = VideoQuality.fromName(prefs.getString(KEY_QUALITY, null))
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    private companion object {
        const val KEY_AUDIO = "audio"
        const val KEY_VIDEO = "enabled"
        const val KEY_QUALITY = "quality"
    }
}
