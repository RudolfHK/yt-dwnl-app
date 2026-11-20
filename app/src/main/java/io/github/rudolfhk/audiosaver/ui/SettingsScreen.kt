package io.github.rudolfhk.audiosaver.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.pipeline.AudioFormat
import io.github.rudolfhk.audiosaver.settings.Settings

/**
 * Everything optional lives here, so the main screen stays "paste, download". Settings are grouped
 * by what they affect; anything that differs from the defaults is highlighted, so it's always clear
 * what was changed and what Reset would undo.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    buildDescription: String,
    onChange: ((Settings) -> Settings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val defaults = Settings()
    val changed = settings != defaults
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset_title),
            text = stringResource(R.string.settings_reset_text),
            confirmLabel = stringResource(R.string.settings_reset_confirm),
            onConfirm = { confirmReset = false; onReset() },
            onDismiss = { confirmReset = false },
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onClose,
                action = if (changed) TopBarAction(stringResource(R.string.settings_reset), R.drawable.ic_restore) { confirmReset = true } else null,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        stringResource(if (changed) R.string.settings_intro_changed else R.string.settings_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                item {
                    SettingsCard(R.drawable.ic_music, R.string.settings_section_audio, R.string.settings_section_audio_sub) {
                        FormatPicker(settings.audioFormat) { format -> onChange { it.copy(audioFormat = format) } }
                    }
                }
                item {
                    SettingsCard(R.drawable.ic_cut, R.string.settings_section_edit, R.string.settings_section_edit_sub) {
                        Toggle(R.string.settings_trim, R.string.settings_trim_hint, settings.manualTrim, defaults.manualTrim) { v -> onChange { it.copy(manualTrim = v) } }
                        Divider()
                        Toggle(R.string.settings_silence, R.string.settings_silence_hint, settings.trimSilence, defaults.trimSilence) { v -> onChange { it.copy(trimSilence = v) } }
                        Divider()
                        Toggle(R.string.settings_replaygain, R.string.settings_replaygain_hint, settings.replayGain, defaults.replayGain) { v -> onChange { it.copy(replayGain = v) } }
                    }
                }
                item {
                    SettingsCard(R.drawable.ic_label, R.string.settings_section_tags, R.string.settings_section_tags_sub) {
                        Toggle(R.string.settings_cover, R.string.settings_cover_hint, settings.coverArt, defaults.coverArt) { v -> onChange { it.copy(coverArt = v) } }
                        Divider()
                        Toggle(R.string.settings_titles, R.string.settings_titles_hint, settings.smartTitles, defaults.smartTitles) { v -> onChange { it.copy(smartTitles = v) } }
                        Divider()
                        Toggle(R.string.settings_review, R.string.settings_review_hint, settings.reviewTags, defaults.reviewTags) { v -> onChange { it.copy(reviewTags = v) } }
                    }
                }
                item {
                    SettingsCard(R.drawable.ic_queue_music, R.string.settings_section_collections, R.string.settings_section_collections_sub) {
                        Toggle(R.string.settings_only_new, R.string.settings_only_new_hint, settings.onlyNewSongs, defaults.onlyNewSongs) { v -> onChange { it.copy(onlyNewSongs = v) } }
                    }
                }
                item {
                    SettingsCard(R.drawable.ic_bolt, R.string.settings_section_downloads, R.string.settings_section_downloads_sub) {
                        Toggle(R.string.settings_parallel, R.string.settings_parallel_hint, settings.parallelChunks, defaults.parallelChunks) { v -> onChange { it.copy(parallelChunks = v) } }
                        Divider()
                        Toggle(R.string.settings_updates, R.string.settings_updates_hint, settings.updateCheck, defaults.updateCheck) { v -> onChange { it.copy(updateCheck = v) } }
                    }
                }
                item {
                    Text(
                        buildDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** One group: icon badge, title and a line saying what the group is about, then its controls. */
@Composable
private fun SettingsCard(@DrawableRes icon: Int, @StringRes title: Int, @StringRes subtitle: Int, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(10.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatPicker(selected: AudioFormat, onSelect: (AudioFormat) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AudioFormat.entries.forEachIndexed { i, format ->
                SegmentedButton(
                    selected = format == selected,
                    onClick = { onSelect(format) },
                    shape = SegmentedButtonDefaults.itemShape(i, AudioFormat.entries.size),
                ) {
                    Text(
                        when (format) {
                            AudioFormat.M4A -> "M4A"
                            AudioFormat.OPUS -> "Opus"
                            AudioFormat.MP3 -> "MP3"
                        },
                    )
                }
            }
        }
        // What the chosen format means, right below the choice.
        val (headline, detail) = when (selected) {
            AudioFormat.M4A -> R.string.settings_format_m4a_title to R.string.settings_format_m4a
            AudioFormat.OPUS -> R.string.settings_format_opus_title to R.string.settings_format_opus
            AudioFormat.MP3 -> R.string.settings_format_mp3_title to R.string.settings_format_mp3
        }
        Surface(
            color = if (selected == Settings().audioFormat) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(headline), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** A switch row. When it differs from the default, the row is tinted and marked "Changed". */
@Composable
private fun Toggle(@StringRes title: Int, @StringRes hint: Int, checked: Boolean, default: Boolean, onChange: (Boolean) -> Unit) {
    val changed = checked != default
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (changed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                if (changed) ChangedBadge()
            }
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChangedBadge() {
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape) {
        Text(
            stringResource(R.string.settings_changed),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Preview(showBackground = true, heightDp = 1500)
@Composable
private fun PreviewSettings() {
    AudioSaverTheme { SettingsScreen(Settings(audioFormat = AudioFormat.OPUS, replayGain = true), "1.0 · NewPipeExtractor 65cabc2", {}, {}, {}) }
}

@Preview(showBackground = true, heightDp = 1500, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewSettingsDark() {
    AudioSaverTheme(dark = true) { SettingsScreen(Settings(), "1.0 · NewPipeExtractor 65cabc2", {}, {}, {}) }
}
