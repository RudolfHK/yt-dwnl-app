package io.github.rudolfhk.audiosaver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.collection.CollectionListing
import io.github.rudolfhk.audiosaver.core.engine.CollectionItem
import io.github.rudolfhk.audiosaver.core.pipeline.BatchItemState
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.ItemStatus

/**
 * The mix/album/playlist part of the screen: loading -> pick songs -> batch progress -> summary.
 * While a batch exists its per-song status replaces the checkbox list.
 */
internal fun LazyListScope.collectionSection(state: MainUiState, actions: MainActions) {
    val batch = state.batch
    if (batch != null) {
        item { BatchCard(batch, actions) }
        items(batch.items, key = { "b-" + it.entry.item.videoId.value }) { BatchRow(it) }
        return
    }
    when (val c = state.collection) {
        CollectionUi.Hidden -> Unit
        is CollectionUi.Loading -> item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.loading_collection, c.ref.kind.label.lowercase()), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        is CollectionUi.Failed -> item {
            ErrorCard(title = c.reason.userTitle, detail = c.detail) {
                Button(onClick = actions::onReloadCollection) { Text(stringResource(R.string.retry)) }
            }
        }
        is CollectionUi.Loaded -> {
            val listing = c.listing
            item { ListingHeader(listing, actions) }
            items(listing.items, key = { it.videoId.value }) { item ->
                SongRow(item, checked = item.videoId in listing.selected, onToggle = { actions.onToggle(item.videoId) })
            }
            item { LoadMoreFooter(c, actions) }
        }
    }
}

@Composable
private fun ListingHeader(listing: CollectionListing, actions: MainActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PhaseHeader(listing.title, subtitle = listOfNotNull(listing.kind.label, listing.uploader).joinToString(" · "))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.selected_count, listing.selectedCount, listing.items.size),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { actions.onSelectAll(!listing.allSelected) }) {
                Text(stringResource(if (listing.allSelected) R.string.select_none else R.string.select_all))
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SongRow(item: CollectionItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.available, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && item.available, onCheckedChange = { onToggle() }, enabled = item.available)
        SongText(item, Modifier.weight(1f), dimmed = !item.available)
    }
}

@Composable
private fun SongText(item: CollectionItem, modifier: Modifier = Modifier, dimmed: Boolean = false, status: String? = null) {
    val color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Text(item.title, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val details = listOfNotNull(
            item.uploader,
            item.durationSeconds?.let(::formatDuration),
            if (!item.available) stringResource(R.string.not_downloadable) else null,
            status,
        ).joinToString(" · ")
        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun LoadMoreFooter(loaded: CollectionUi.Loaded, actions: MainActions) {
    val listing = loaded.listing
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        loaded.loadMoreError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        when {
            loaded.loadingMore -> CircularProgressIndicator(Modifier.padding(8.dp))
            listing.canLoadMore -> OutlinedButton(onClick = actions::onLoadMore) { Text(stringResource(R.string.load_more)) }
            listing.items.size >= CollectionListing.MAX_ITEMS ->
                Text(stringResource(R.string.list_capped, CollectionListing.MAX_ITEMS), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BatchCard(batch: BatchState, actions: MainActions) {
    val hasFailures = batch.finished && batch.failedCount > 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (hasFailures) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!batch.finished) {
                val active = batch.items.getOrNull(batch.activeIndex)
                val current = (active?.status as? ItemStatus.Active)?.progress
                val position = (batch.activeIndex.takeIf { it >= 0 } ?: batch.processedCount) + 1
                PhaseHeader(
                    stringResource(R.string.batch_progress, position.coerceAtMost(batch.total), batch.total),
                    subtitle = active?.entry?.item?.title ?: batch.title,
                )
                // Overall bar: finished songs plus the share of the current one that's known.
                val currentFraction = (current as? DownloadState.Downloading)?.fraction ?: 0f
                LinearProgressIndicator(
                    progress = { ((batch.processedCount + currentFraction) / batch.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                current?.let { Text(phaseText(it), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = actions::onCancel) { Text(stringResource(R.string.cancel_all)) }
            } else {
                PhaseHeader(batch.title, subtitle = stringResource(R.string.batch_summary_full, batch.savedCount, batch.failedCount, batch.cancelledCount))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (batch.failedCount > 0) {
                        Button(onClick = actions::onRetryFailed) { Text(stringResource(R.string.retry_failed, batch.failedCount)) }
                    }
                    batch.items.firstNotNullOfOrNull { (it.status as? ItemStatus.Saved)?.saved }?.let { saved ->
                        OutlinedButton(onClick = { actions.onOpenFolder(saved) }) { Text(stringResource(R.string.open_folder)) }
                    }
                }
                TextButton(onClick = actions::onDismissBatch) { Text(stringResource(R.string.back_to_list)) }
            }
        }
    }
}

@Composable
private fun BatchRow(row: BatchItemState) {
    val status = row.status
    val text = when (status) {
        ItemStatus.Queued -> stringResource(R.string.status_queued)
        is ItemStatus.Active -> phaseText(status.progress)
        is ItemStatus.Saved -> "✓ " + (status.warning ?: status.saved.displayName)
        is ItemStatus.Failed -> "✗ ${status.reason.userTitle}: ${status.detail}"
        ItemStatus.Cancelled -> stringResource(R.string.status_cancelled)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (status is ItemStatus.Active) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        SongText(row.entry.item, Modifier.weight(1f), dimmed = status is ItemStatus.Cancelled, status = text)
    }
}

@Composable
private fun phaseText(state: DownloadState): String = when (state) {
    is DownloadState.Resolving -> stringResource(R.string.phase_resolving)
    is DownloadState.Downloading -> state.fraction?.let { stringResource(R.string.percent, (it * 100).toInt()) }
        ?: stringResource(R.string.progress_unknown, formatBytes(state.bytesDownloaded))
    is DownloadState.Saving -> stringResource(R.string.phase_saving)
    else -> ""
}

@Composable
private fun ErrorCard(title: String, detail: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            SelectionContainer {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            actions()
        }
    }
}

internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
