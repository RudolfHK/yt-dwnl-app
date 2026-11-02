package io.github.rudolfhk.audiosaver.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.history.FileOutcome
import io.github.rudolfhk.audiosaver.core.history.HistoryEntry
import io.github.rudolfhk.audiosaver.core.history.HistoryStatus
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio

interface HistoryActions {
    fun onClose()
    fun onPlay(saved: SavedAudio)
    fun onOpenFolder(saved: SavedAudio)
    fun onRetry(entry: HistoryEntry)
    fun onClear()
}

private enum class HistoryFilter { ALL, SAVED, FAILED }

/** Every finished download, newest first: what was saved where, and what failed and why. */
@Composable
fun HistoryScreen(entries: List<HistoryEntry>, actions: HistoryActions) {
    var filterName by rememberSaveable { mutableStateOf(HistoryFilter.ALL.name) }
    val filter = HistoryFilter.valueOf(filterName)
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val failedCount = entries.count { it.status != HistoryStatus.SAVED }
    val shown = when (filter) {
        HistoryFilter.ALL -> entries
        HistoryFilter.SAVED -> entries.filter { it.status == HistoryStatus.SAVED }
        // "Partly saved" belongs here: something the user asked for is missing.
        HistoryFilter.FAILED -> entries.filter { it.status != HistoryStatus.SAVED }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_text)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; actions.onClear() }) { Text(stringResource(R.string.history_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = actions::onClose) { Text(stringResource(R.string.back)) }
                    Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear)) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == HistoryFilter.ALL, { filterName = HistoryFilter.ALL.name }, label = { Text(stringResource(R.string.history_all, entries.size)) })
                    FilterChip(
                        filter == HistoryFilter.SAVED,
                        { filterName = HistoryFilter.SAVED.name },
                        label = { Text(stringResource(R.string.history_saved, entries.size - failedCount)) },
                    )
                    FilterChip(filter == HistoryFilter.FAILED, { filterName = HistoryFilter.FAILED.name }, label = { Text(stringResource(R.string.history_failed, failedCount)) })
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        stringResource(if (entries.isEmpty()) R.string.history_empty else R.string.history_empty_filter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { "${it.timeMillis}-${it.videoId}-${it.collection}" }) { HistoryRow(it, actions) }
            item {
                Text(stringResource(R.string.history_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(entry: HistoryEntry, actions: HistoryActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(entry.artist, entry.collection, formatDate(entry.timeMillis)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.audio?.let { OutcomeLine(stringResource(R.string.format_audio_short), it) }
            entry.video?.let { OutcomeLine(stringResource(R.string.format_video_short), it) }
            val savedFiles = listOfNotNull(entry.audio?.saved, entry.video?.saved)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                savedFiles.firstOrNull()?.let { first ->
                    OutlinedButton(onClick = { actions.onPlay(first.toSavedAudio()) }) { Text(stringResource(R.string.play)) }
                    OutlinedButton(onClick = { actions.onOpenFolder(first.toSavedAudio()) }) { Text(stringResource(R.string.open_folder)) }
                }
                if (entry.status != HistoryStatus.SAVED) {
                    OutlinedButton(onClick = { actions.onRetry(entry) }) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

@Composable
private fun OutcomeLine(label: String, outcome: FileOutcome) {
    val saved = outcome.saved
    if (saved != null) {
        Text(
            "✓ $label · " + listOfNotNull(saved.quality, saved.name, saved.location, formatBytes(saved.sizeBytes)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        val reason = outcome.reason ?: FailureReason.UNKNOWN
        SelectionContainer {
            Text(
                "✗ $label · ${reason.userTitle}: ${outcome.failureDetail.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "14:03", "Yesterday", "3 Nov" - in the phone's language and 12/24 h setting. */
@Composable
internal fun formatDate(timeMillis: Long): String {
    val context = LocalContext.current
    return DateUtils.getRelativeDateTimeString(context, timeMillis, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun PreviewHistory() {
    val now = System.currentTimeMillis()
    val saved = io.github.rudolfhk.audiosaver.core.history.SavedFile("Blender - Big Buck Bunny.m4a", "Music/AudioSaver", "content://x", "audio/mp4", 3_400_000)
    val entries = listOf(
        HistoryEntry(now - 60_000, "aqz-KE-bpKQ", "Big Buck Bunny", "Blender", audio = FileOutcome(saved = saved)),
        HistoryEntry(
            now - 3_600_000, "aqz-KE-bpKQ", "Big Buck Bunny", "Blender",
            audio = FileOutcome(saved = saved), video = FileOutcome.failed(FailureReason.STORAGE, "Not enough free space"), videoQuality = "P720",
        ),
        HistoryEntry(now - 86_400_000, "aaaaaaaaaaa", "Video aaaaaaaaaaa", audio = FileOutcome.failed(FailureReason.UNAVAILABLE, "Video unavailable")),
    )
    AudioSaverTheme {
        HistoryScreen(entries, object : HistoryActions {
            override fun onClose() = Unit
            override fun onPlay(saved: SavedAudio) = Unit
            override fun onOpenFolder(saved: SavedAudio) = Unit
            override fun onRetry(entry: HistoryEntry) = Unit
            override fun onClear() = Unit
        })
    }
}
