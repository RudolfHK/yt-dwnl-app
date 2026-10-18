package io.github.rudolfhk.audiosaver.core.download

import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

fun interface DownloadProgressListener {
    /** [totalBytes] is null when the size is genuinely unknown - show an indeterminate bar then. */
    fun onProgress(bytesDownloaded: Long, totalBytes: Long?)
}

/**
 * Streams an audio URL to a file. When the size is known up front it downloads in HTTP Range
 * chunks: a dropped connection resumes from the last byte received, and it is what YouTube's stream hosts serve at full speed
 * (one huge un-ranged request gets throttled). Cancellation is checked between every buffer read
 * and also aborts the in-flight HTTP call immediately.
 */
class HttpAudioDownloader(
    private val client: OkHttpClient,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    private val maxAttemptsPerChunk: Int = 3,
) {

    suspend fun download(source: AudioStreamSource, target: File, listener: DownloadProgressListener): Long =
        withContext(Dispatchers.IO) {
            target.outputStream().buffered(BUFFER_SIZE).use { out ->
                val known = source.contentLength?.takeIf { it > 0 }
                val written = if (known != null) {
                    downloadRanged(source, known, out, listener)
                } else {
                    downloadWhole(source, out, listener)
                }
                out.flush()
                written
            }
        }

    private suspend fun downloadRanged(
        source: AudioStreamSource,
        total: Long,
        out: OutputStream,
        listener: DownloadProgressListener,
    ): Long {
        var done = 0L
        var failuresInARow = 0
        listener.onProgress(0, total)
        while (done < total) {
            val end = minOf(done + chunkSize, total) - 1
            val from = done
            try {
                val request = requestBuilder(source).header("Range", "bytes=$from-$end").build()
                execute(request) { code, body ->
                    val limit = when (code) {
                        206 -> end - from + 1
                        // Server ignored Range and sent the whole file; only usable when starting at 0.
                        200 -> if (from == 0L) total else throw IOException("Server stopped honouring Range requests")
                        else -> throw httpFailure(code)
                    }
                    copy(body, out, limit) { copied ->
                        done = from + copied
                        listener.onProgress(done, total)
                    }
                }
                if (done == from) throw IOException("Empty response for bytes $from-$end")
                failuresInARow = 0
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                // Bytes already written stay written; the next attempt resumes from `done`.
                if (++failuresInARow >= maxAttemptsPerChunk) throw networkFailure(e)
                delay(RETRY_DELAY_MS * failuresInARow)
            }
        }
        if (done != total) {
            throw AudioSaveException(FailureReason.DOWNLOAD_FAILED, "Received $done of $total bytes")
        }
        return done
    }

    private suspend fun downloadWhole(source: AudioStreamSource, out: OutputStream, listener: DownloadProgressListener): Long {
        val request = requestBuilder(source).build()
        return try {
            execute(request) { code, body ->
                if (code != 200) throw httpFailure(code)
                val headerLength = body.contentLength.takeIf { it > 0 }
                listener.onProgress(0, headerLength)
                val received = copy(body, out, headerLength ?: Long.MAX_VALUE) { listener.onProgress(it, headerLength) }
                if (headerLength != null && received != headerLength) {
                    throw AudioSaveException(FailureReason.DOWNLOAD_FAILED, "Received $received of $headerLength bytes")
                }
                received
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw networkFailure(e)
        }
    }

    private class Body(val stream: InputStream, val contentLength: Long)

    private suspend fun <T> execute(request: Request, handle: suspend (code: Int, body: Body) -> T): T {
        val call: Call = client.newCall(request)
        return cancelOnCancellation(call) {
            call.execute().use { response ->
                handle(response.code, Body(response.body.byteStream(), response.body.contentLength()))
            }
        }
    }

    /** Copies at most [limit] bytes, reporting the running count; returns bytes copied. */
    private suspend fun copy(body: Body, out: OutputStream, limit: Long, onProgress: (Long) -> Unit): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (copied < limit) {
            currentCoroutineContext().ensureActive()
            val n = body.stream.read(buffer, 0, minOf(buffer.size.toLong(), limit - copied).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            copied += n
            onProgress(copied)
        }
        return copied
    }

    private fun requestBuilder(source: AudioStreamSource): Request.Builder =
        Request.Builder().url(source.url).apply {
            source.requestHeaders.forEach { (name, value) -> header(name, value) }
        }

    private fun httpFailure(code: Int): AudioSaveException {
        val hint = when (code) {
            403 -> " - the stream URL was refused (expired, or YouTube blocked this client). Retrying re-resolves it."
            404, 410 -> " - the stream no longer exists."
            429 -> " - too many requests; wait a bit before retrying."
            else -> ""
        }
        val reason = if (code == 429) FailureReason.RATE_LIMITED else FailureReason.DOWNLOAD_FAILED
        return AudioSaveException(reason, "Stream host answered HTTP $code$hint")
    }

    private fun networkFailure(e: IOException): AudioSaveException =
        AudioSaveException(
            FailureReason.NETWORK,
            "${e::class.java.simpleName}: ${e.message ?: "connection problem"}",
            e,
        )

    companion object {
        const val DEFAULT_CHUNK_SIZE = 2L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val RETRY_DELAY_MS = 750L
    }
}
