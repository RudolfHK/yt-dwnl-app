package io.github.rudolfhk.audiosaver.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.LinkParseResult
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.link.YouTubeLinkParser
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio

@Composable
fun MainScreen(
    input: String,
    parsed: LinkParseResult,
    download: DownloadState,
    onInputChanged: (String) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: (VideoId) -> Unit,
    onPlay: (SavedAudio) -> Unit,
    onShare: (SavedAudio) -> Unit,
    onOpenFolder: (SavedAudio) -> Unit,
) {
    val context = LocalContext.current
    val canDownload = parsed is LinkParseResult.Valid && !download.isActive
    var showInfo by rememberSaveable { mutableStateOf(false) }

    if (showInfo) InfoDialog(onDismiss = { showInfo = false })

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showInfo = true }) {
                    Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.info_button))
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.link_label)) },
                placeholder = { Text(stringResource(R.string.link_placeholder)) },
                singleLine = true,
                enabled = !download.isActive,
                isError = input.isNotBlank() && parsed is LinkParseResult.Invalid,
                supportingText = { ValidationText(input, parsed) },
                trailingIcon = {
                    if (input.isEmpty()) {
                        TextButton(onClick = { clipboardText(context)?.let(onInputChanged) }, enabled = !download.isActive) {
                            Text(stringResource(R.string.paste))
                        }
                    } else {
                        TextButton(onClick = { onInputChanged("") }, enabled = !download.isActive) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (canDownload) onDownload() }),
            )

            Button(onClick = onDownload, enabled = canDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.download))
            }

            StatusCard(download, onCancel, onRetry, onPlay, onShare, onOpenFolder)

            Spacer(Modifier.height(8.dp))
            LegalNotice()
        }
    }
}

@Composable
private fun ValidationText(input: String, parsed: LinkParseResult) {
    when {
        parsed is LinkParseResult.Valid ->
            Text(stringResource(R.string.valid_link, parsed.videoId.value), color = MaterialTheme.colorScheme.primary)
        input.isNotBlank() && parsed is LinkParseResult.Invalid -> Text(parsed.message)
        else -> Text("youtube.com/watch?v=…, youtu.be/…, /shorts/…, or share from the YouTube app")
    }
}

@OptIn(ExperimentalLayoutApi::class) // FlowRow; a no-op on Compose versions where it is stable
@Composable
private fun StatusCard(
    state: DownloadState,
    onCancel: () -> Unit,
    onRetry: (VideoId) -> Unit,
    onPlay: (SavedAudio) -> Unit,
    onShare: (SavedAudio) -> Unit,
    onOpenFolder: (SavedAudio) -> Unit,
) {
    if (state is DownloadState.Idle) return
    val isError = state is DownloadState.Failed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isError) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state) {
                is DownloadState.Resolving -> {
                    PhaseHeader(stringResource(R.string.phase_resolving), subtitle = state.videoId.value)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    CancelRow(onCancel)
                }

                is DownloadState.Downloading -> {
                    PhaseHeader(stringResource(R.string.phase_downloading), subtitle = state.title)
                    val fraction = state.fraction
                    if (fraction != null) {
                        // Real percentage only when the size is actually known...
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.progress_known,
                                (fraction * 100).toInt(),
                                formatBytes(state.bytesDownloaded),
                                formatBytes(state.totalBytes ?: 0),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        // ...otherwise an honest indeterminate bar, never a made-up number.
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.progress_unknown, formatBytes(state.bytesDownloaded)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    CancelRow(onCancel)
                }

                is DownloadState.Saving -> {
                    PhaseHeader(stringResource(R.string.phase_saving), subtitle = state.title)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                is DownloadState.Done -> {
                    PhaseHeader("✓ " + stringResource(R.string.saved_title), subtitle = state.saved.displayName)
                    Text(
                        stringResource(R.string.saved_location, state.saved.location, formatBytes(state.saved.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.taggingWarning?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPlay(state.saved) }) { Text(stringResource(R.string.play)) }
                        OutlinedButton(onClick = { onShare(state.saved) }) { Text(stringResource(R.string.share)) }
                        OutlinedButton(onClick = { onOpenFolder(state.saved) }) { Text(stringResource(R.string.open_folder)) }
                    }
                }

                is DownloadState.Failed -> {
                    Text(
                        state.reason.userTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    // Selectable so the real extractor message can be copied into a bug report.
                    SelectionContainer {
                        Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onRetry(state.videoId) }) { Text(stringResource(R.string.retry)) }
                        if (!state.reason.retryable) {
                            Text(
                                "Retrying is unlikely to help.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                is DownloadState.Cancelled -> {
                    Text(stringResource(R.string.cancelled))
                    OutlinedButton(onClick = { onRetry(state.videoId) }) { Text(stringResource(R.string.retry)) }
                }

                DownloadState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun PhaseHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CancelRow(onCancel: () -> Unit) {
    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun LegalNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.notice_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.notice_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.info_close)) } },
        title = { Text(stringResource(R.string.info_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.info_how_title), style = MaterialTheme.typography.titleSmall)
                stringArrayResource(R.array.info_how_steps).forEachIndexed { i, step ->
                    InfoListItem("${i + 1}.", step)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.info_errors_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.info_errors_intro), style = MaterialTheme.typography.bodyMedium)
                stringArrayResource(R.array.info_errors).forEach { InfoListItem("•", it) }
                Text(stringResource(R.string.info_errors_report), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.info_libs_title), style = MaterialTheme.typography.titleSmall)
                stringArrayResource(R.array.info_libs).forEach { InfoListItem("•", it) }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.info_license), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun InfoListItem(marker: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(marker, style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

@Preview(showBackground = true)
@Composable
private fun PreviewDownloading() {
    val id = VideoId.of("dQw4w9WgXcQ")
    AudioSaverTheme {
        MainScreen(
            input = "https://youtu.be/dQw4w9WgXcQ",
            parsed = YouTubeLinkParser.parse("https://youtu.be/dQw4w9WgXcQ"),
            download = DownloadState.Downloading(id, "Never Gonna Give You Up", 2_400_000, 3_400_000),
            onInputChanged = {}, onDownload = {}, onCancel = {}, onRetry = {}, onPlay = {}, onShare = {}, onOpenFolder = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewFailed() {
    AudioSaverTheme {
        MainScreen(
            input = "https://youtu.be/aaaaaaaaaaa",
            parsed = YouTubeLinkParser.parse("https://youtu.be/aaaaaaaaaaa"),
            download = DownloadState.Failed(VideoId.of("aaaaaaaaaaa"), FailureReason.UNAVAILABLE, "Got error ERROR: \"Video unavailable\""),
            onInputChanged = {}, onDownload = {}, onCancel = {}, onRetry = {}, onPlay = {}, onShare = {}, onOpenFolder = {},
        )
    }
}
