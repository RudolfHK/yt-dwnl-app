package io.github.rudolfhk.audiosaver.engine.newpipe

import io.github.rudolfhk.audiosaver.core.download.CallGroup
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** NewPipeExtractor's HTTP abstraction, backed by the app's shared OkHttpClient. */
internal class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        val call = client.newCall(builder.build())
        val group = activeCallGroup.get()
        group?.register(call)
        try {
            return execute(call, request)
        } finally {
            group?.unregister(call)
        }
    }

    private fun execute(call: Call, request: Request): Response {
        call.execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested (HTTP 429)", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }

    companion object {
        /**
         * Extraction runs synchronously on the calling thread, so the engine publishes the current
         * operation's [CallGroup] here and every request made on its behalf joins it - that's what
         * lets a cancel abort a request NewPipeExtractor is blocked on.
         */
        val activeCallGroup = ThreadLocal<CallGroup?>()

        /** Same desktop UA the NewPipe app uses; the extractor overrides it per-client where needed. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
    }
}
