package io.github.rudolfhk.audiosaver

import android.app.Application
import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.pipeline.AudioDownloadPipeline
import io.github.rudolfhk.audiosaver.download.DownloadController
import io.github.rudolfhk.audiosaver.download.MediaStoreAudioSink
import io.github.rudolfhk.audiosaver.engine.newpipe.NewPipeExtractionEngine
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AudioSaverApp : Application() {

    /** Manual dependency wiring - the app is small enough not to need a DI framework. */
    val controller: DownloadController by lazy {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // The one line to change when swapping the YouTube extraction library.
        val engine: ExtractionEngine = NewPipeExtractionEngine(http)

        DownloadController(
            AudioDownloadPipeline(
                engine = engine,
                downloader = HttpAudioDownloader(http),
                sink = MediaStoreAudioSink(this),
                workDir = File(cacheDir, "downloads"),
            ),
        )
    }
}

val android.content.Context.audioSaverApp: AudioSaverApp get() = applicationContext as AudioSaverApp
