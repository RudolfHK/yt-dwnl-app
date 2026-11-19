package io.github.rudolfhk.audiosaver.settings

import android.content.Context
import io.github.rudolfhk.audiosaver.core.audio.TimeRange
import io.github.rudolfhk.audiosaver.core.pipeline.AudioFormat
import io.github.rudolfhk.audiosaver.core.pipeline.Processing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything on the "Advanced settings" screen. Defaults are what a new install gets. */
data class Settings(
    val audioFormat: AudioFormat = AudioFormat.M4A,
    val coverArt: Boolean = true,
    val smartTitles: Boolean = true,
    val replayGain: Boolean = false,
    val trimSilence: Boolean = false,
    /** Shows start/end fields for single links. */
    val manualTrim: Boolean = false,
    val reviewTags: Boolean = false,
    val onlyNewSongs: Boolean = true,
    val parallelChunks: Boolean = false,
    val updateCheck: Boolean = true,
) {
    /** What the pipeline does for a single link; [trim] comes from the start/end fields. */
    fun processing(trim: TimeRange = TimeRange.ALL) = Processing(
        audioFormat = audioFormat,
        coverArt = coverArt,
        smartTitles = smartTitles,
        replayGain = replayGain,
        trimSilence = trimSilence,
        trim = if (manualTrim) trim else TimeRange.ALL,
        reviewTags = reviewTags,
        parallelChunks = parallelChunks,
    )
}

/** Persists [Settings] in SharedPreferences and publishes changes. One instance per process. */
class AdvancedSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("advanced", Context.MODE_PRIVATE)
    private val defaults = Settings()

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString(K_FORMAT, next.audioFormat.name)
            .putBoolean(K_COVER, next.coverArt)
            .putBoolean(K_TITLES, next.smartTitles)
            .putBoolean(K_GAIN, next.replayGain)
            .putBoolean(K_SILENCE, next.trimSilence)
            .putBoolean(K_TRIM, next.manualTrim)
            .putBoolean(K_REVIEW, next.reviewTags)
            .putBoolean(K_ONLY_NEW, next.onlyNewSongs)
            .putBoolean(K_PARALLEL, next.parallelChunks)
            .putBoolean(K_UPDATES, next.updateCheck)
            .apply()
    }

    fun reset() = update { Settings() }

    private fun read() = Settings(
        audioFormat = prefs.getString(K_FORMAT, null)?.let { name -> AudioFormat.entries.firstOrNull { it.name == name } }
            ?: defaults.audioFormat,
        coverArt = prefs.getBoolean(K_COVER, defaults.coverArt),
        smartTitles = prefs.getBoolean(K_TITLES, defaults.smartTitles),
        replayGain = prefs.getBoolean(K_GAIN, defaults.replayGain),
        trimSilence = prefs.getBoolean(K_SILENCE, defaults.trimSilence),
        manualTrim = prefs.getBoolean(K_TRIM, defaults.manualTrim),
        reviewTags = prefs.getBoolean(K_REVIEW, defaults.reviewTags),
        onlyNewSongs = prefs.getBoolean(K_ONLY_NEW, defaults.onlyNewSongs),
        parallelChunks = prefs.getBoolean(K_PARALLEL, defaults.parallelChunks),
        updateCheck = prefs.getBoolean(K_UPDATES, defaults.updateCheck),
    )

    private companion object {
        const val K_FORMAT = "audio_format"
        const val K_COVER = "cover_art"
        const val K_TITLES = "smart_titles"
        const val K_GAIN = "replay_gain"
        const val K_SILENCE = "trim_silence"
        const val K_TRIM = "manual_trim"
        const val K_REVIEW = "review_tags"
        const val K_ONLY_NEW = "only_new_songs"
        const val K_PARALLEL = "parallel_chunks"
        const val K_UPDATES = "update_check"
    }
}
