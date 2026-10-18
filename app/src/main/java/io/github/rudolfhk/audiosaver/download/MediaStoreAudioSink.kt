package io.github.rudolfhk.audiosaver.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.rudolfhk.audiosaver.core.pipeline.AudioSink
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves into the shared Music collection (Music/AudioSaver) via MediaStore - no storage permission
 * needed on API 29+. The entry is created with IS_PENDING=1 so other apps never see a half-written
 * file, and on any failure or cancel the entry is deleted again.
 */
class MediaStoreAudioSink(context: Context) : AudioSink {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun save(file: File, displayName: String, mimeType: String, metadata: TrackMetadata): SavedAudio =
        withContext(Dispatchers.IO) {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val pending = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, pending)
                ?: throw IOException("MediaStore refused to create \"$displayName\" in $RELATIVE_PATH")
            try {
                copyInto(uri, file)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                readBack(uri, mimeType)
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
                throw e
            }
        }

    private suspend fun copyInto(uri: Uri, file: File) {
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't open $uri for writing")
        out.use { sink ->
            file.inputStream().use { source ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                }
            }
        }
    }

    /** Report what actually landed: MediaStore may have renamed the file to avoid a clash. */
    private fun readBack(uri: Uri, mimeType: String): SavedAudio {
        // Size straight from the stored file, not from the (lazily updated) SIZE column.
        val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            ?: throw IOException("Saved file can't be opened: $uri")
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return SavedAudio(
                    displayName = cursor.getString(0),
                    location = cursor.getString(1)?.trimEnd('/') ?: RELATIVE_PATH,
                    uri = uri.toString(),
                    mimeType = mimeType,
                    sizeBytes = size,
                )
            }
        }
        throw IOException("Saved file is missing from MediaStore: $uri")
    }

    companion object {
        const val FOLDER = "AudioSaver"
        val RELATIVE_PATH: String = "${Environment.DIRECTORY_MUSIC}/$FOLDER"
    }
}
