package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Plain-filesystem [AudioSink] for JVM tests and tooling; the Android app uses MediaStore instead. */
class DirectoryAudioSink(private val directory: File) : AudioSink {
    override suspend fun save(file: File, displayName: String, mimeType: String, metadata: TrackMetadata): SavedAudio =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val target = uniqueTarget(displayName)
            file.copyTo(target)
            SavedAudio(target.name, directory.path, target.toURI().toString(), mimeType, target.length())
        }

    private fun uniqueTarget(displayName: String): File {
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "")
        var candidate = File(directory, displayName)
        var n = 1
        while (candidate.exists()) candidate = File(directory, "$base (${n++})${if (ext.isEmpty()) "" else ".$ext"}")
        return candidate
    }
}
