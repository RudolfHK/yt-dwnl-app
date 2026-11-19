package io.github.rudolfhk.audiosaver.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What this build contains, compared against GitHub. */
data class BuildInfo(
    /** This app's version name, e.g. "1.2" or "1.2.0". */
    val appVersion: String,
    /** Human-readable, e.g. "65cabc2 (dev, 2026-10-05)". */
    val extractorVersion: String,
    /** Date of the built-in NewPipeExtractor; a release published after it is newer. */
    val extractorDate: LocalDate,
)

data class Release(val tag: String, val name: String, val url: String, val publishedAt: Instant)

/**
 * Result of a check. [appUpdate]: a newer build of this app exists. [newerExtractor]: NewPipeExtractor
 * has a release newer than the one built in - when YouTube breaks things, that's usually the fix,
 * and it reaches the phone only with a new app build.
 */
data class UpdateStatus(val appUpdate: Release?, val newerExtractor: Release?) {
    val anything: Boolean get() = appUpdate != null || newerExtractor != null

    /** One line for the card, or null when everything is current. */
    fun summary(build: BuildInfo): String? = when {
        appUpdate != null && newerExtractor != null ->
            "Audio Saver ${appUpdate.tag} is available. NewPipeExtractor ${newerExtractor.tag} is out too " +
                "(this build has ${build.extractorVersion})."
        appUpdate != null -> "Audio Saver ${appUpdate.tag} is available (you have ${build.appVersion})."
        newerExtractor != null ->
            "NewPipeExtractor ${newerExtractor.tag} is out; this build has ${build.extractorVersion}. " +
                "If YouTube downloads break, a new app build with it will likely fix them."
        else -> null
    }

    /** Appended to "YouTube changed something" errors, where an update is the real fix. */
    fun brokenExtractorHint(): String? = when {
        appUpdate != null -> "A newer app build (${appUpdate.tag}) is available and may fix this."
        newerExtractor != null -> "NewPipeExtractor ${newerExtractor.tag} is out; an app build with it may fix this."
        else -> null
    }
}

/**
 * Asks GitHub's public API (api.github.com only, no account, nothing about the user sent) for the
 * latest release of this app and of NewPipeExtractor. A missing release list (HTTP 404: none
 * published yet) counts as "nothing newer"; other failures throw [IOException] so the caller can
 * retry later and keep showing the last result.
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val build: BuildInfo,
    private val baseUrl: String = "https://api.github.com",
    private val appRepo: String = APP_REPO,
    private val extractorRepo: String = EXTRACTOR_REPO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        val app = latest(appRepo)?.takeIf { Versions.isNewer(it.tag, build.appVersion) }
        val extractorCutoff = build.extractorDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val extractor = latest(extractorRepo)?.takeIf { it.publishedAt >= extractorCutoff }
        UpdateStatus(app, extractor)
    }

    @Serializable
    private class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        @SerialName("html_url") val url: String,
        @SerialName("published_at") val publishedAt: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    private fun latest(repo: String): Release? {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AudioSaver-update-check")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub answered HTTP ${response.code} for $repo")
            val release = try {
                json.decodeFromString(GitHubRelease.serializer(), response.body.string())
            } catch (e: IllegalArgumentException) {
                throw IOException("Unexpected answer from GitHub for $repo: ${e.message}", e)
            }
            if (release.draft || release.prerelease) return null
            val published = release.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            return Release(release.tag, release.name ?: release.tag, release.url, published)
        }
    }

    companion object {
        const val APP_REPO = "RudolfHK/yt-dwnl-app"
        const val EXTRACTOR_REPO = "TeamNewPipe/NewPipeExtractor"

        /** At most once a day; GitHub allows 60 anonymous requests an hour and we use two. */
        const val INTERVAL_MS = 24L * 60 * 60 * 1000

        fun isDue(lastCheckMillis: Long, nowMillis: Long): Boolean =
            lastCheckMillis <= 0 || nowMillis - lastCheckMillis >= INTERVAL_MS || nowMillis < lastCheckMillis
    }
}

/** Compares "v1.2.10" style version names numerically; non-numeric parts are ignored. */
object Versions {
    fun parse(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
