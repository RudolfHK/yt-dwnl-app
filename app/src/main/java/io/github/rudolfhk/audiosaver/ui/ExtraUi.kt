package io.github.rudolfhk.audiosaver.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.audio.TimeCodes
import io.github.rudolfhk.audiosaver.core.pipeline.ProcessingStep
import io.github.rudolfhk.audiosaver.core.pipeline.TagProposal
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import io.github.rudolfhk.audiosaver.core.update.UpdateStatus
import androidx.compose.ui.unit.Dp

/** Content column width on tablets and in landscape: wider lines are hard to read. */
internal val CONTENT_MAX_WIDTH: Dp = 720.dp

/** Width from which the history sits next to the main screen instead of replacing it. */
internal val TWO_PANE_MIN_WIDTH: Dp = 840.dp

@get:StringRes
internal val ProcessingStep.label: Int
    get() = when (this) {
        ProcessingStep.WAITING_FOR_TAGS -> R.string.phase_waiting_tags
        ProcessingStep.ANALYZING -> R.string.phase_analyzing
        ProcessingStep.ENCODING_MP3 -> R.string.phase_encoding_mp3
        ProcessingStep.REMUXING_OPUS -> R.string.phase_remuxing_opus
    }

/** The start/end fields of "Cut start and end" for a single link. */
@Composable
internal fun TrimFields(start: String, end: String, error: String?, enabled: Boolean, onStart: (String) -> Unit, onEnd: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.trim_title), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            OutlinedTextField(
                start, onStart, Modifier.weight(1f),
                enabled = enabled, singleLine = true, isError = error != null,
                label = { Text(stringResource(R.string.trim_start)) }, placeholder = { Text("0:00") }, keyboardOptions = keyboard,
            )
            OutlinedTextField(
                end, onEnd, Modifier.weight(1f),
                enabled = enabled, singleLine = true, isError = error != null,
                label = { Text(stringResource(R.string.trim_end)) }, placeholder = { Text("—") }, keyboardOptions = keyboard,
            )
        }
        Text(
            error ?: stringResource(R.string.trim_hint),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Null when the fields are fine, else what's wrong. */
internal fun trimError(start: String, end: String): String? =
    try {
        TimeCodes.range(start, end)
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "Invalid time"
    }

/**
 * "Edit tags before saving". Shown while the download runs; Save hands the edited tags to the
 * pipeline, Cancel cancels the download. Dismissing by tapping outside is disabled on purpose:
 * it would be unclear whether that saves or cancels.
 */
@Composable
internal fun TagReviewDialog(proposal: TagProposal, onSave: (TrackMetadata) -> Unit, onCancelDownload: () -> Unit) {
    val md = proposal.metadata
    var title by rememberSaveable(proposal) { mutableStateOf(md.title) }
    var artist by rememberSaveable(proposal) { mutableStateOf(md.artist.orEmpty()) }
    var album by rememberSaveable(proposal) { mutableStateOf(md.album.orEmpty()) }
    val titleOk = title.isNotBlank()
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.review_video_title, proposal.videoTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
                OutlinedTextField(
                    title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = words,
                    label = { Text(stringResource(R.string.review_field_title)) },
                    isError = !titleOk,
                    supportingText = if (titleOk) null else { { Text(stringResource(R.string.review_title_required)) } },
                )
                OutlinedTextField(
                    artist, { artist = it }, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = words,
                    label = { Text(stringResource(R.string.review_field_artist)) },
                )
                OutlinedTextField(
                    album, { album = it }, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = words,
                    label = { Text(stringResource(R.string.review_field_album)) },
                )
                Text(stringResource(R.string.review_downloading), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = titleOk,
                onClick = {
                    onSave(
                        md.copy(
                            title = title.trim(),
                            artist = artist.trim().ifEmpty { null },
                            album = album.trim().ifEmpty { null },
                        ),
                    )
                },
            ) { Text(stringResource(R.string.review_save)) }
        },
        dismissButton = { TextButton(onClick = onCancelDownload) { Text(stringResource(R.string.review_cancel)) } },
    )
}

@Composable
internal fun UpdateCard(status: UpdateStatus, summary: String, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (status.appUpdate ?: status.newerExtractor)?.url?.takeIf { it.isNotBlank() }?.let { url ->
                    OutlinedButton(onClick = { onOpen(url) }) { Text(stringResource(R.string.update_open)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_dismiss)) }
            }
        }
    }
}
