package io.github.rudolfhk.audiosaver.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.rudolfhk.audiosaver.BuildConfig
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.engine.newpipe.NewPipeExtractionEngine

const val GITHUB_URL = "https://github.com/RudolfHK/yt-dwnl-app"

/** A labelled action in a [ScreenTopBar]: a real button, so it's obvious it can be tapped. */
class TopBarAction(val label: String, @DrawableRes val icon: Int, val onClick: () -> Unit)

/**
 * Header of the secondary screens (History, Advanced settings): back as an arrow icon button on
 * the left, the title centred, an optional action as a tonal button on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(title: String, onBack: (() -> Unit)?, action: TopBarAction? = null) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = {
            if (action != null) {
                FilledTonalButton(
                    onClick = action.onClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(painterResource(action.icon), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(action.label)
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** "Are you sure?" for destructive actions; the confirm button is in the error colour. */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Personal use only": a compact warning strip. The full text lives in the About dialog. */
@Composable
fun PersonalUseBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.notice_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** ⓘ: what this app is - version, build, engine, libraries, licence, source code. */
@Composable
fun AboutDialog(appVersion: String, onOpenGitHub: () -> Unit, onDismiss: () -> Unit) {
    val year = BuildConfig.BUILD_DATE.take(4)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.info_close)) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.app_name))
                    Text(
                        stringResource(R.string.about_version, appVersion, BuildConfig.BUILD_DATE),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium)
                SectionLabel(stringResource(R.string.about_engine))
                LibraryLine("NewPipeExtractor", NewPipeExtractionEngine.EXTRACTOR_VERSION, "GPL-3.0")
                SectionLabel(stringResource(R.string.info_libs_title))
                LibraryLine("OkHttp", BuildConfig.V_OKHTTP, "Apache-2.0")
                LibraryLine("Kotlin", BuildConfig.V_KOTLIN, "Apache-2.0")
                LibraryLine("kotlinx.coroutines", BuildConfig.V_COROUTINES, "Apache-2.0")
                LibraryLine("kotlinx.serialization", BuildConfig.V_SERIALIZATION, "Apache-2.0")
                LibraryLine("Jetpack Compose", BuildConfig.V_COMPOSE, "Apache-2.0")
                LibraryLine("Material 3", BuildConfig.V_MATERIAL3, "Apache-2.0")
                LibraryLine("AndroidX Core · Activity · Lifecycle", "${BuildConfig.V_ANDROIDX_CORE} · ${BuildConfig.V_ACTIVITY} · ${BuildConfig.V_LIFECYCLE}", "Apache-2.0")
                LibraryLine("jump3r (LAME, MP3 export)", BuildConfig.V_JUMP3R, "LGPL-2.1")
                LibraryLine("desugar_jdk_libs", BuildConfig.V_DESUGAR, "GPL-2.0 + CE")
                Text(stringResource(R.string.about_own_code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SectionLabel(stringResource(R.string.notice_title))
                Text(stringResource(R.string.notice_body), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.about_copyright, year), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenGitHub) {
                    Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.about_github))
                }
            }
        },
    )
}

/** ?: how a download works and why some fail. */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.info_close)) } },
        icon = { Icon(painterResource(R.drawable.ic_help), contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text(stringResource(R.string.help_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.info_how_title))
                stringArrayResource(R.array.info_how_steps).forEachIndexed { i, step -> ListItem("${i + 1}.", step) }
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.info_errors_title))
                Text(stringResource(R.string.info_errors_intro), style = MaterialTheme.typography.bodyMedium)
                stringArrayResource(R.array.info_errors).forEach { ListItem("•", it) }
                Text(stringResource(R.string.info_errors_report), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

/** The launcher icon's layers (painterResource can't load the adaptive-icon XML itself). */
@Composable
fun AppIcon(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(28))) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.matchParentSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.matchParentSize().scale(1.25f))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun LibraryLine(name: String, version: String, license: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text("$version · $license", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListItem(marker: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(marker, style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewBanner() {
    AudioSaverTheme { PersonalUseBanner(Modifier.padding(16.dp)) }
}
