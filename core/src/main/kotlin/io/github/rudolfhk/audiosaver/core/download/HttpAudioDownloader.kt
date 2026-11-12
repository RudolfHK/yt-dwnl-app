package io.github.rudolfhk.audiosaver.core.download

import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.RemoteFile
import io.github.rudolfhk.audiosaver.core.engine.RequestVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    suspend fun download(source: RemoteFile, target: File, listener: DownloadProgressListener): Long =
        withContext(Dispatchers.IO) {
            target.outputStream().buffered(BUFFER_SIZE).use { out ->
                val known = source.contentLength?.takeIf { it > 0 }
                val variants = Variants(source)
                val written = if (known != null) {
                    downloadRanged(source, variants, known, out, listener)
                } else {
                    downloadWhole(source, variants, out, listener)
                }
                out.flush()
                written
            }
        }

    /**
     * Walks through the source's request variants: the current one is used until the host refuses
     * it, then the next one is tried for the same bytes. Refusals don't count as network failures.
     */
    private class Variants(source: RemoteFile) {
        private val all = source.requestVariants.ifEmpty { listOf(RequestVariant("default", source.requestHeaders)) }
        private var index = 0
        private var requestNumber = 0
        val current: RequestVariant get() = all[index]
        val tried: String get() = all.take(index + 1).joinToString { it.name }

        /** Moves to the next variant; false when there is none left. */
        fun next(): Boolean = (index + 1 < all.size).also { if (it) index++ }

        fun nextRequestNumber() = requestNumber++
    }

    /** The host refused this way of asking (or ignored the range): try the next variant. */
    private class VariantRejected(val failure: AudioSaveException) : IOException(failure.detail)

    private suspend fun downloadRanged(
        source: RemoteFile,
        variants: Variants,
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
            val variant = variants.current
            try {
                execute(request(source, variant, variants, from, end)) { code, body ->
                    val expected = end - from + 1
                    val limit = when {
                        code == 206 -> expected
                        code == 200 && variant.rangeAsQueryParameter -> {
                            // The range came in the URL, so a plain 200 is normal - but only if the host
                            // honoured it. A full-file answer would silently write the wrong bytes.
                            if (body.contentLength > 0 && body.contentLength != expected) {
                                throw VariantRejected(
                                    AudioSaveException(FailureReason.DOWNLOAD_FAILED, "Stream host ignored the range parameter"),
                                )
                            }
                            expected
                        }
                        // Server ignored the Range header and sent the whole file; only usable when starting at 0.
                        code == 200 -> if (from == 0L) total else throw IOException("Server stopped honouring Range requests")
                        code in REJECTED -> throw VariantRejected(httpFailure(code))
                        else -> throw httpFailure(code)
                    }
                    copy(body, out, limit) { copied ->
                        done = from + copied
                        listener.onProgress(done, total)
                    }
                }
                if (done == from) throw IOException("Empty response for bytes $from-$end")
                failuresInARow = 0
            } catch (e: VariantRejected) {
                if (!variants.next()) throw refusedByAll(e.failure, variants)
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

    private suspend fun downloadWhole(source: RemoteFile, variants: Variants, out: OutputStream, listener: DownloadProgressListener): Long {
        while (true) {
            try {
                return execute(request(source, variants.current, variants, null, null)) { code, body ->
                    if (code in REJECTED) throw VariantRejected(httpFailure(code))
                    if (code != 200) throw httpFailure(code)
                    val headerLength = body.contentLength.takeIf { it > 0 }
                    listener.onProgress(0, headerLength)
                    val received = copy(body, out, headerLength ?: Long.MAX_VALUE) { listener.onProgress(it, headerLength) }
                    if (headerLength != null && received != headerLength) {
                        throw AudioSaveException(FailureReason.DOWNLOAD_FAILED, "Received $received of $headerLength bytes")
                    }
                    received
                }
            } catch (e: VariantRejected) {
                if (!variants.next()) throw refusedByAll(e.failure, variants)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw networkFailure(e)
            }
        }
    }

    private fun refusedByAll(last: AudioSaveException, variants: Variants) =
        AudioSaveException(last.reason, "${last.detail} (tried: ${variants.tried})", last)

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

    /** Builds the request for bytes [from]..[to] (or the whole file when both are null) in the given style. */
    private fun request(source: RemoteFile, variant: RequestVariant, variants: Variants, from: Long?, to: Long?): Request {
        val url = source.url.toHttpUrl().newBuilder().apply {
            if (variant.requestNumberParameter) addQueryParameter("rn", variants.nextRequestNumber().toString())
            if (variant.rangeAsQueryParameter && from != null) addQueryParameter("range", "$from-$to")
        }.build()
        return Request.Builder().url(url).apply {
            variant.headers.forEach { (name, value) -> header(name, value) }
            if (!variant.rangeAsQueryParameter && from != null) header("Range", "bytes=$from-$to")
            variant.postBody?.let { post(it.toRequestBody()) }
        }.build()
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

        /** Answers that mean "not like this" rather than "not at all": worth trying the next variant. */
        private val REJECTED = setOf(400, 401, 403)
        private const val BUFFER_SIZE = 64 * 1024
        private const val RETRY_DELAY_MS = 750L
    }
}
