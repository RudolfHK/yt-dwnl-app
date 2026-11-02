package io.github.rudolfhk.audiosaver.core.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The download history, newest first, kept in one JSON-lines file in app-private storage.
 *
 * Appending a line per finished download keeps writes cheap and crash-safe: a half-written last
 * line (power loss, process killed) is skipped on the next load instead of losing the history.
 * Only the newest [maxEntries] are kept; the file is compacted when it grows well past that.
 */
class HistoryLog(private val file: File, private val maxEntries: Int = 1000) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private var loaded = false
    private var linesOnDisk = 0

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Newest first. Empty until [load] has run. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    suspend fun load() = mutex.withLock { ensureLoaded() }

    suspend fun add(entry: HistoryEntry) = mutex.withLock {
        ensureLoaded()
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(HistoryEntry.serializer(), entry) + "\n")
        }
        linesOnDisk++
        _entries.value = (listOf(entry) + _entries.value).take(maxEntries)
        if (linesOnDisk > maxEntries + maxEntries / 5) rewrite()
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete() }
        linesOnDisk = 0
        loaded = true
        _entries.value = emptyList()
    }

    /** The newest entry in which this video was saved (any format), for the "already saved" hint. */
    fun lastSaved(videoId: String): HistoryEntry? =
        _entries.value.firstOrNull { it.videoId == videoId && it.status != HistoryStatus.FAILED }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val lines = withContext(Dispatchers.IO) { if (file.exists()) file.readLines() else emptyList() }
        linesOnDisk = lines.size
        _entries.value = lines
            .mapNotNull { line -> line.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString(HistoryEntry.serializer(), it) }.getOrNull() } }
            .asReversed()
            .take(maxEntries)
        loaded = true
    }

    private suspend fun rewrite() {
        val keep = _entries.value
        withContext(Dispatchers.IO) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(keep.asReversed().joinToString("") { json.encodeToString(HistoryEntry.serializer(), it) + "\n" })
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        linesOnDisk = keep.size
    }
}
