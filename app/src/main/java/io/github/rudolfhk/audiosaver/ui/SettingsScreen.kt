package io.github.rudolfhk.audiosaver.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.pipeline.AudioFormat
import io.github.rudolfhk.audiosaver.settings.Settings

/** Everything optional lives here, so the main screen stays "paste, download". */
@Composable
fun SettingsScreen(
    settings: Settings,
    buildDescription: String,
    onChange: ((Settings) -> Settings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClose) { Text(stringResource(R.string.back)) }
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onReset) { Text(stringResource(R.string.settings_reset)) }
                    }
                }

                item { Section(R.string.settings_section_audio) }
                item {
                    Column(Modifier.selectableGroup()) {
                        Text(stringResource(R.string.settings_format), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
                        AudioFormat.entries.forEach { format ->
                            FormatOption(format, selected = settings.audioFormat == format) { onChange { it.copy(audioFormat = format) } }
                        }
                    }
                }
                item { Toggle(R.string.settings_trim, R.string.settings_trim_hint, settings.manualTrim) { v -> onChange { it.copy(manualTrim = v) } } }
                item { Toggle(R.string.settings_silence, R.string.settings_silence_hint, settings.trimSilence) { v -> onChange { it.copy(trimSilence = v) } } }
                item { Toggle(R.string.settings_replaygain, R.string.settings_replaygain_hint, settings.replayGain) { v -> onChange { it.copy(replayGain = v) } } }

                item { Section(R.string.settings_section_tags) }
                item { Toggle(R.string.settings_cover, R.string.settings_cover_hint, settings.coverArt) { v -> onChange { it.copy(coverArt = v) } } }
                item { Toggle(R.string.settings_titles, R.string.settings_titles_hint, settings.smartTitles) { v -> onChange { it.copy(smartTitles = v) } } }
                item { Toggle(R.string.settings_review, R.string.settings_review_hint, settings.reviewTags) { v -> onChange { it.copy(reviewTags = v) } } }

                item { Section(R.string.settings_section_collections) }
                item { Toggle(R.string.settings_only_new, R.string.settings_only_new_hint, settings.onlyNewSongs) { v -> onChange { it.copy(onlyNewSongs = v) } } }

                item { Section(R.string.settings_section_downloads) }
                item { Toggle(R.string.settings_parallel, R.string.settings_parallel_hint, settings.parallelChunks) { v -> onChange { it.copy(parallelChunks = v) } } }
                item { Toggle(R.string.settings_updates, R.string.settings_updates_hint, settings.updateCheck) { v -> onChange { it.copy(updateCheck = v) } } }
                item {
                    Text(
                        buildDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FormatOption(format: AudioFormat, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(
            stringResource(
                when (format) {
                    AudioFormat.M4A -> R.string.settings_format_m4a
                    AudioFormat.OPUS -> R.string.settings_format_opus
                    AudioFormat.MP3 -> R.string.settings_format_mp3
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Toggle(@StringRes title: Int, @StringRes hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun PreviewSettings() {
    AudioSaverTheme { SettingsScreen(Settings(), "1.0 · 65cabc2", {}, {}, {}) }
}
