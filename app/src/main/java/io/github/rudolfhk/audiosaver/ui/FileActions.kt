package io.github.rudolfhk.audiosaver.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.net.toUri
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio
import java.util.Locale

/** Hand-offs to other apps. Playback is deliberately not done in-app. */
internal object FileActions {

    fun play(context: Context, saved: SavedAudio) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(saved.uri.toUri(), saved.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, intent) { context.getString(R.string.no_player, saved.mimeType) }
    }

    fun share(context: Context, saved: SavedAudio) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(saved.mimeType)
            .putExtra(Intent.EXTRA_STREAM, saved.uri.toUri())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, Intent.createChooser(send, saved.displayName)) { "" }
    }

    /**
     * There's no standard "show this folder" intent. Most devices' system Files app (DocumentsUI)
     * handles VIEW on an external-storage directory document, so try that and fall back to a message.
     */
    fun openFolder(context: Context, saved: SavedAudio) {
        val folderDocument = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${saved.location}",
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folderDocument, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, intent) { context.getString(R.string.no_file_manager, saved.location) }
    }

    private fun launch(context: Context, intent: Intent, onMissing: () -> String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            onMissing().takeIf { it.isNotEmpty() }?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        } catch (e: SecurityException) {
            onMissing().takeIf { it.isNotEmpty() }?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.getDefault(), "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}
