package io.github.rudolfhk.audiosaver

import android.app.Application
import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.history.HistoryLog
import io.github.rudolfhk.audiosaver.core.pipeline.AudioDownloadPipeline
import io.github.rudolfhk.audiosaver.core.pipeline.BatchDownloader
import io.github.rudolfhk.audiosaver.download.DownloadController
import io.github.rudolfhk.audiosaver.download.MediaStoreAudioSink
import io.github.rudolfhk.audiosaver.engine.newpipe.NewPipeExtractionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

        val pipeline = AudioDownloadPipeline(
            engine = engine,
            downloader = HttpAudioDownloader(http),
            sink = MediaStoreAudioSink(this),
            workDir = File(cacheDir, "downloads"),
        )
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // App-private and excluded from backup (allowBackup=false): the history never leaves the phone.
        val history = HistoryLog(File(filesDir, "history.jsonl")).also { log -> appScope.launch { log.load() } }
        DownloadController(engine, pipeline, BatchDownloader(pipeline), history, appScope)
    }
}

val android.content.Context.audioSaverApp: AudioSaverApp get() = applicationContext as AudioSaverApp
