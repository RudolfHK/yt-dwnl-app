package io.github.rudolfhk.audiosaver.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.collection.CollectionListing
import io.github.rudolfhk.audiosaver.core.engine.CollectionItem
import io.github.rudolfhk.audiosaver.core.engine.CollectionPage
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.PageToken
import io.github.rudolfhk.audiosaver.core.link.LinkParseResult
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.link.YouTubeLinkParser
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio
import io.github.rudolfhk.audiosaver.core.pipeline.VideoResult
import io.github.rudolfhk.audiosaver.core.video.VideoQuality

/** Everything the screen shows, gathered so the composable has one input instead of ten. */
data class MainUiState(
    val input: String,
    val parsed: LinkParseResult,
    val target: Target,
    val justThisSong: Boolean,
    val download: DownloadState,
    val collection: CollectionUi,
    val batch: BatchState?,
    val saveVideo: Boolean = false,
    val videoQuality: VideoQuality = VideoQuality.BEST,
) {
    val busy: Boolean get() = download.isActive || batch?.isActive == true
}

interface MainActions {
    fun onInputChanged(text: String)
    fun onDownload()
    fun onCancel()
    fun onRetry(videoId: VideoId)
    fun onPlay(saved: SavedAudio)
    fun onShare(saved: SavedAudio)
    fun onOpenFolder(saved: SavedAudio)
    fun onJustThisSong(value: Boolean)
    fun onToggle(videoId: VideoId)
    fun onSelectAll(all: Boolean)
    fun onLoadMore()
    fun onReloadCollection()
    fun onRetryFailed()
    fun onDismissBatch()
    fun onSaveVideo(value: Boolean)
    fun onVideoQuality(quality: VideoQuality)
    fun onRetryVideo(videoId: VideoId)
}

@Composable
fun MainScreen(state: MainUiState, actions: MainActions) {
    val context = LocalContext.current
    val isCollection = state.target is Target.Collection
    val loadedListing = (state.collection as? CollectionUi.Loaded)?.listing
    val canDownloadSingle = state.target is Target.Single && !state.busy
    var showInfo by rememberSaveable { mutableStateOf(false) }

    if (showInfo) InfoDialog(onDismiss = { showInfo = false })

    Scaffold(
        bottomBar = {
            // Pinned, so "Download 25 songs" stays reachable at the bottom of a long mix.
            if (isCollection && loadedListing != null && state.batch == null) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = actions::onDownload,
                        enabled = loadedListing.selectedCount > 0 && !state.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(stringResource(R.string.download_selected, loadedListing.selectedCount))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
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
            }

            item {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = actions::onInputChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.link_label)) },
                    placeholder = { Text(stringResource(R.string.link_placeholder)) },
                    singleLine = true,
                    enabled = !state.busy,
                    isError = state.input.isNotBlank() && state.parsed is LinkParseResult.Invalid,
                    supportingText = { ValidationText(state.input, state.parsed) },
                    trailingIcon = {
                        if (state.input.isEmpty()) {
                            TextButton(onClick = { clipboardText(context)?.let(actions::onInputChanged) }, enabled = !state.busy) {
                                Text(stringResource(R.string.paste))
                            }
                        } else {
                            TextButton(onClick = { actions.onInputChanged("") }, enabled = !state.busy) {
                                Text(stringResource(R.string.clear))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (canDownloadSingle) actions.onDownload() }),
                )
            }

            val collectionLink = (state.parsed as? LinkParseResult.Collection)?.ref
            if (collectionLink?.seedVideoId != null) {
                item { ModeChooser(collectionLink.kind.label, state.justThisSong, enabled = !state.busy, onChange = actions::onJustThisSong) }
            }

            if (isCollection) {
                collectionSection(state, actions)
            } else {
                item {
                    VideoOption(
                        enabled = !state.busy,
                        checked = state.saveVideo,
                        quality = state.videoQuality,
                        onChecked = actions::onSaveVideo,
                        onQuality = actions::onVideoQuality,
                    )
                }
                item {
                    Button(onClick = actions::onDownload, enabled = canDownloadSingle, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.saveVideo) R.string.download_with_video else R.string.download))
                    }
                }
                item {
                    StatusCard(state.download, actions)
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                LegalNotice()
            }
        }
    }
}

@Composable
private fun ModeChooser(kindLabel: String, justThisSong: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !justThisSong,
            onClick = { onChange(false) },
            enabled = enabled,
            label = { Text(stringResource(R.string.whole_collection, kindLabel.lowercase())) },
        )
        FilterChip(
            selected = justThisSong,
            onClick = { onChange(true) },
            enabled = enabled,
            label = { Text(stringResource(R.string.just_this_song)) },
        )
    }
}

@Composable
private fun ValidationText(input: String, parsed: LinkParseResult) {
    when {
        parsed is LinkParseResult.Valid ->
            Text(stringResource(R.string.valid_link, parsed.videoId.value), color = MaterialTheme.colorScheme.primary)
        parsed is LinkParseResult.Collection ->
            Text(stringResource(R.string.valid_collection, parsed.ref.kind.label), color = MaterialTheme.colorScheme.primary)
        input.isNotBlank() && parsed is LinkParseResult.Invalid -> Text(parsed.message)
        else -> Text(stringResource(R.string.link_hint))
    }
}

@OptIn(ExperimentalLayoutApi::class) // FlowRow; a no-op on Compose versions where it is stable
@Composable
private fun StatusCard(state: DownloadState, actions: MainActions) {
    val onCancel = actions::onCancel
    val onRetry = actions::onRetry
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
                    PhaseHeader(
                        stringResource(if (state.withVideo) R.string.phase_downloading_video else R.string.phase_downloading),
                        subtitle = state.title,
                    )
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

                is DownloadState.Merging -> {
                    PhaseHeader(stringResource(R.string.phase_merging), subtitle = state.title)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    CancelRow(onCancel)
                }

                is DownloadState.Done -> {
                    state.saved?.let { saved ->
                        PhaseHeader("✓ " + stringResource(R.string.saved_title), subtitle = saved.displayName)
                        Text(
                            stringResource(R.string.saved_location, saved.location, formatBytes(saved.sizeBytes)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.taggingWarning?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        FileButtons(saved, actions)
                    }
                    when (val video = state.video) {
                        is VideoResult.Saved -> {
                            if (state.saved != null) HorizontalDivider()
                            PhaseHeader("✓ " + stringResource(R.string.video_saved_title, video.height), subtitle = video.saved.displayName)
                            Text(
                                stringResource(R.string.saved_location, video.saved.location, formatBytes(video.saved.sizeBytes)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            video.warning?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            FileButtons(video.saved, actions)
                        }
                        is VideoResult.Failed -> {
                            if (state.saved != null) HorizontalDivider()
                            Text(
                                stringResource(R.string.video_failed_title, video.reason.userTitle),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            SelectionContainer { Text(video.detail, style = MaterialTheme.typography.bodySmall) }
                            OutlinedButton(onClick = { actions.onRetryVideo(state.videoId) }) { Text(stringResource(R.string.retry_video)) }
                        }
                        null -> Unit
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileButtons(saved: SavedAudio, actions: MainActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onPlay(saved) }) { Text(stringResource(R.string.play)) }
        OutlinedButton(onClick = { actions.onShare(saved) }) { Text(stringResource(R.string.share)) }
        OutlinedButton(onClick = { actions.onOpenFolder(saved) }) { Text(stringResource(R.string.open_folder)) }
    }
}

/** "Also save video (MP4)" with the quality ceiling. Only shown for single videos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoOption(
    enabled: Boolean,
    checked: Boolean,
    quality: VideoQuality,
    onChecked: (Boolean) -> Unit,
    onQuality: (VideoQuality) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.save_video), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.save_video_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        if (checked) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoQuality.entries.forEach { option ->
                    FilterChip(
                        selected = option == quality,
                        onClick = { onQuality(option) },
                        enabled = enabled,
                        label = { Text(option.label) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PhaseHeader(title: String, subtitle: String) {
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

private object NoActions : MainActions {
    override fun onInputChanged(text: String) = Unit
    override fun onDownload() = Unit
    override fun onCancel() = Unit
    override fun onRetry(videoId: VideoId) = Unit
    override fun onPlay(saved: SavedAudio) = Unit
    override fun onShare(saved: SavedAudio) = Unit
    override fun onOpenFolder(saved: SavedAudio) = Unit
    override fun onJustThisSong(value: Boolean) = Unit
    override fun onToggle(videoId: VideoId) = Unit
    override fun onSelectAll(all: Boolean) = Unit
    override fun onLoadMore() = Unit
    override fun onReloadCollection() = Unit
    override fun onRetryFailed() = Unit
    override fun onDismissBatch() = Unit
    override fun onSaveVideo(value: Boolean) = Unit
    override fun onVideoQuality(quality: VideoQuality) = Unit
    override fun onRetryVideo(videoId: VideoId) = Unit
}

private fun previewState(link: String, download: DownloadState = DownloadState.Idle, collection: CollectionUi = CollectionUi.Hidden): MainUiState {
    val parsed = YouTubeLinkParser.parse(link)
    val target = when (parsed) {
        is LinkParseResult.Valid -> Target.Single(parsed.videoId)
        is LinkParseResult.Collection -> Target.Collection(parsed.ref)
        is LinkParseResult.Invalid -> Target.None
    }
    return MainUiState(link, parsed, target, justThisSong = false, download = download, collection = collection, batch = null)
}

@Preview(showBackground = true)
@Composable
private fun PreviewDownloading() {
    val id = VideoId.of("dQw4w9WgXcQ")
    AudioSaverTheme {
        MainScreen(
            previewState("https://youtu.be/dQw4w9WgXcQ", DownloadState.Downloading(id, "Never Gonna Give You Up", 2_400_000, 3_400_000)),
            NoActions,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewFailed() {
    AudioSaverTheme {
        MainScreen(
            previewState(
                "https://youtu.be/aaaaaaaaaaa",
                DownloadState.Failed(VideoId.of("aaaaaaaaaaa"), FailureReason.UNAVAILABLE, "Got error ERROR: \"Video unavailable\""),
            ),
            NoActions,
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PreviewMix() {
    val link = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ"
    val ref = (YouTubeLinkParser.parse(link) as LinkParseResult.Collection).ref
    val items = listOf("dQw4w9WgXcQ", "yPYZpwSpKmA", "aaaaaaaaaaa").mapIndexed { i, id ->
        CollectionItem(VideoId.of(id), "Song number ${i + 1}", "Artist $i", 200L + i * 30, available = i != 2)
    }
    val listing = CollectionListing.of(CollectionPage(ref, "Mix - Rick Astley", null, items, null, PageToken(Unit)))
    AudioSaverTheme {
        MainScreen(previewState(link, collection = CollectionUi.Loaded(listing.toggle(VideoId.of("yPYZpwSpKmA")))), NoActions)
    }
}
