package io.github.rudolfhk.audiosaver

import android.app.Application
import io.github.rudolfhk.audiosaver.core.download.HttpAudioDownloader
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.history.HistoryLog
import io.github.rudolfhk.audiosaver.core.pipeline.AudioDownloadPipeline
import io.github.rudolfhk.audiosaver.core.pipeline.BatchDownloader
import io.github.rudolfhk.audiosaver.core.pipeline.Toolkit
import io.github.rudolfhk.audiosaver.core.update.BuildInfo
import io.github.rudolfhk.audiosaver.download.DownloadController
import io.github.rudolfhk.audiosaver.download.TagReviewQueue
import io.github.rudolfhk.audiosaver.media.BitmapCoverCropper
import io.github.rudolfhk.audiosaver.media.MediaCodecPcmDecoder
import io.github.rudolfhk.audiosaver.settings.AdvancedSettings
import io.github.rudolfhk.audiosaver.update.UpdateManager
import io.github.rudolfhk.audiosaver.download.MediaStoreAudioSink
import io.github.rudolfhk.audiosaver.engine.newpipe.NewPipeExtractionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDate
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

        val reviews = TagReviewQueue()
        val pipeline = AudioDownloadPipeline(
            engine = engine,
            downloader = HttpAudioDownloader(http),
            sink = MediaStoreAudioSink(this),
            workDir = File(cacheDir, "downloads"),
            toolkit = Toolkit(decoder = MediaCodecPcmDecoder(), cropper = BitmapCoverCropper(), reviewer = reviews),
        )
        // App-private and excluded from backup (allowBackup=false): the history never leaves the phone.
        val history = HistoryLog(File(filesDir, "history.jsonl")).also { log -> appScope.launch { log.load() } }
        DownloadController(engine, pipeline, BatchDownloader(pipeline), history, reviews, appScope)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: AdvancedSettings by lazy { AdvancedSettings(this) }

    val updates: UpdateManager by lazy {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "0"
        val build = BuildInfo(
            appVersion = version,
            extractorVersion = NewPipeExtractionEngine.EXTRACTOR_VERSION,
            extractorDate = LocalDate.parse(NewPipeExtractionEngine.EXTRACTOR_DATE),
        )
        UpdateManager(this, OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(), build, appScope)
    }
}

val android.content.Context.audioSaverApp: AudioSaverApp get() = applicationContext as AudioSaverApp
