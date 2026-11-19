package io.github.rudolfhk.audiosaver.update

import android.content.Context
import io.github.rudolfhk.audiosaver.core.update.BuildInfo
import io.github.rudolfhk.audiosaver.core.update.Release
import io.github.rudolfhk.audiosaver.core.update.UpdateChecker
import io.github.rudolfhk.audiosaver.core.update.UpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Instant

/**
 * Runs the update check at most once a day (when enabled) and remembers the last answer, so the
 * card and the error hint survive restarts and work offline. Dismissing the card hides it until a
 * different release shows up.
 */
class UpdateManager(
    context: Context,
    http: OkHttpClient,
    val build: BuildInfo,
    private val scope: CoroutineScope,
) {
    private val prefs = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val checker = UpdateChecker(http, build)

    private val _status = MutableStateFlow(load())

    /** The last known result (possibly from an earlier day). */
    val status: StateFlow<UpdateStatus?> = _status.asStateFlow()

    private val _dismissedKey = MutableStateFlow(prefs.getString(K_DISMISSED, null))
    val dismissedKey: StateFlow<String?> = _dismissedKey.asStateFlow()

    @Volatile private var running = false

    fun checkIfDue(enabled: Boolean, now: Long = System.currentTimeMillis()) {
        if (!enabled || running || !UpdateChecker.isDue(prefs.getLong(K_LAST, 0), now)) return
        running = true
        scope.launch {
            try {
                val result = checker.check()
                save(result, now)
                _status.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or GitHub unhappy: keep the last answer and try again in an hour or so.
                prefs.edit().putLong(K_LAST, now - UpdateChecker.INTERVAL_MS + RETRY_MS).apply()
            } finally {
                running = false
            }
        }
    }

    fun dismiss(status: UpdateStatus) {
        val key = keyOf(status)
        _dismissedKey.value = key
        prefs.edit().putString(K_DISMISSED, key).apply()
    }

    /** When the user turns the check off, forget what it found. */
    fun clear() {
        _status.value = null
        prefs.edit().clear().apply()
    }

    private fun save(status: UpdateStatus, now: Long) {
        prefs.edit().apply {
            putLong(K_LAST, now)
            put("app", status.appUpdate)
            put("extractor", status.newerExtractor)
        }.apply()
    }

    private fun android.content.SharedPreferences.Editor.put(prefix: String, release: Release?) {
        if (release == null) {
            remove("$prefix.tag"); remove("$prefix.name"); remove("$prefix.url"); remove("$prefix.published")
        } else {
            putString("$prefix.tag", release.tag)
            putString("$prefix.name", release.name)
            putString("$prefix.url", release.url)
            putLong("$prefix.published", release.publishedAt.toEpochMilli())
        }
    }

    private fun read(prefix: String): Release? {
        val tag = prefs.getString("$prefix.tag", null) ?: return null
        return Release(
            tag,
            prefs.getString("$prefix.name", tag) ?: tag,
            prefs.getString("$prefix.url", "") ?: "",
            Instant.ofEpochMilli(prefs.getLong("$prefix.published", 0)),
        )
    }

    private fun load(): UpdateStatus? {
        if (!prefs.contains(K_LAST)) return null
        // A stored app release that this build already is (the user updated) isn't news any more.
        val app = read("app")?.takeIf { io.github.rudolfhk.audiosaver.core.update.Versions.isNewer(it.tag, build.appVersion) }
        return UpdateStatus(app, read("extractor"))
    }

    companion object {
        private const val K_LAST = "last_check"
        private const val K_DISMISSED = "dismissed"
        private const val RETRY_MS = 60L * 60 * 1000

        fun keyOf(status: UpdateStatus) = "${status.appUpdate?.tag}|${status.newerExtractor?.tag}"
    }
}
