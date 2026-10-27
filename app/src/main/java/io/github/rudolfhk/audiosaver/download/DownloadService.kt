package io.github.rudolfhk.audiosaver.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.rudolfhk.audiosaver.audioSaverApp
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.ItemStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that runs one single-song download or one batch from a mix/album. Being in the foreground is what keeps a multi-minute download alive when the user leaves the app
 * or the screen turns off; the notification mirrors the in-app progress and offers Cancel.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var timedOut = false
    private lateinit var notifications: DownloadNotifications

    override fun onCreate() {
        super.onCreate()
        notifications = DownloadNotifications(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = audioSaverApp.controller
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel() ?: stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)?.takeIf { VideoId.isValid(it) }?.let { VideoId.of(it) }
                // Every startForegroundService() must be answered by startForeground() within seconds,
                // even for a request we're about to ignore, or the system kills the app.
                ServiceCompat.startForeground(
                    this,
                    DownloadNotifications.PROGRESS_ID,
                    notifications.progress(controller.state.value.takeIf { it.isActive } ?: videoId?.let { DownloadState.Resolving(it) }),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                if (job?.isActive == true) return START_NOT_STICKY
                if (videoId == null) {
                    finish(controller.state.value)
                    return START_NOT_STICKY
                }
                timedOut = false
                job = scope.launch {
                    try {
                        controller.pipeline.run(videoId) { reported ->
                            val state = if (reported is DownloadState.Cancelled && timedOut) {
                                DownloadState.Failed(
                                    videoId,
                                    FailureReason.DOWNLOAD_FAILED,
                                    TIME_LIMIT_MESSAGE,
                                )
                            } else {
                                reported
                            }
                            controller.publish(state)
                            if (state.isActive) notifications.updateProgress(state)
                        }
                    } finally {
                        finish(controller.state.value)
                    }
                }.also(controller::attach)
            }
            ACTION_START_BATCH -> {
                ServiceCompat.startForeground(
                    this,
                    DownloadNotifications.PROGRESS_ID,
                    notifications.batchProgress(controller.batch.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                if (job?.isActive == true) return START_NOT_STICKY
                val request = controller.takePendingBatch()
                if (request == null) {
                    finish(controller.state.value)
                    return START_NOT_STICKY
                }
                timedOut = false
                job = scope.launch {
                    try {
                        controller.batchDownloader.run(request) { reported ->
                            val state = if (reported.finished && timedOut) withTimeLimitReason(reported) else reported
                            controller.publishBatch(state)
                            if (!state.finished) notifications.updateBatchProgress(state)
                        }
                    } finally {
                        finishBatch(controller.batch.value)
                    }
                }.also(controller::attach)
            }
            else -> stopSelf()
        }
        // If the OS kills us anyway, don't restart with a null intent: the user can just retry.
        return START_NOT_STICKY
    }

    private fun finish(finalState: DownloadState) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.result(finalState)
        stopSelf()
    }

    private fun finishBatch(finalState: BatchState?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        finalState?.let(notifications::batchResult)
        stopSelf()
    }

    /** Songs the OS cut off are failures with a reason, not something the user cancelled. */
    private fun withTimeLimitReason(state: BatchState): BatchState = state.copy(
        items = state.items.map {
            if (it.status is ItemStatus.Cancelled) {
                it.copy(status = ItemStatus.Failed(FailureReason.DOWNLOAD_FAILED, TIME_LIMIT_MESSAGE))
            } else {
                it
            }
        },
    )

    /** Android 15+: dataSync services get ~6 h/day. A single song never gets close, but honour it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        job?.cancel() ?: finish(audioSaverApp.controller.state.value)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "io.github.rudolfhk.audiosaver.START"
        private const val ACTION_START_BATCH = "io.github.rudolfhk.audiosaver.START_BATCH"
        private const val TIME_LIMIT_MESSAGE =
            "Android stopped the background download (foreground-service time limit reached)."
        const val ACTION_CANCEL = "io.github.rudolfhk.audiosaver.CANCEL"
        private const val EXTRA_VIDEO_ID = "videoId"

        fun startIntent(context: Context, videoId: VideoId): Intent =
            Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VIDEO_ID, videoId.value)

        /** The batch itself is handed over through [DownloadController]; it can be too big for an Intent. */
        fun startBatchIntent(context: Context): Intent =
            Intent(context, DownloadService::class.java).setAction(ACTION_START_BATCH)

        fun cancelIntent(context: Context): Intent =
            Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
    }
}
