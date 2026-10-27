package io.github.rudolfhk.audiosaver.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.rudolfhk.audiosaver.MainActivity
import io.github.rudolfhk.audiosaver.R
import io.github.rudolfhk.audiosaver.core.pipeline.BatchState
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.core.pipeline.ItemStatus
import io.github.rudolfhk.audiosaver.ui.formatBytes

internal class DownloadNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel))
                .setDescription(context.getString(R.string.notification_channel_description))
                .build(),
        )
    }

    fun progress(state: DownloadState?): Notification {
        val builder = base()
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.cancel), cancelIntent())
        when (state) {
            is DownloadState.Downloading -> {
                builder.setContentTitle(state.title)
                val fraction = state.fraction
                if (fraction != null && state.totalBytes != null) {
                    builder.setProgress(1000, (fraction * 1000).toInt(), false)
                        .setContentText(
                            context.getString(
                                R.string.progress_known,
                                (fraction * 100).toInt(),
                                formatBytes(state.bytesDownloaded),
                                formatBytes(state.totalBytes!!),
                            ),
                        )
                } else {
                    builder.setProgress(0, 0, true)
                        .setContentText(context.getString(R.string.progress_unknown, formatBytes(state.bytesDownloaded)))
                }
            }
            is DownloadState.Saving -> builder.setContentTitle(state.title)
                .setContentText(context.getString(R.string.phase_saving))
                .setProgress(0, 0, true)
            else -> builder.setContentTitle(context.getString(R.string.phase_resolving)).setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun updateProgress(state: DownloadState) = notify(PROGRESS_ID, progress(state))

    fun result(state: DownloadState) {
        val notification = when (state) {
            is DownloadState.Done -> base()
                .setContentTitle(state.saved.displayName)
                .setContentText(context.getString(R.string.notification_done, state.saved.location))
                .setAutoCancel(true)
                .build()
            is DownloadState.Failed -> base()
                .setContentTitle(context.getString(R.string.notification_failed))
                .setContentText("${state.reason.userTitle}: ${state.detail}")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${state.reason.userTitle}\n${state.detail}"))
                .setAutoCancel(true)
                .build()
            else -> return // cancelled by the user, or nothing happened: no need to tell them
        }
        notify(RESULT_ID, notification)
    }

    /** "Song 3 of 12": overall bar = finished songs plus the current song's fraction. */
    fun batchProgress(state: BatchState?): Notification {
        val builder = base()
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.cancel_all), cancelIntent())
        if (state == null || state.total == 0) {
            return builder.setContentTitle(context.getString(R.string.phase_resolving)).setProgress(0, 0, true).build()
        }
        val active = state.items.getOrNull(state.activeIndex)
        val current = (active?.status as? ItemStatus.Active)?.progress
        val currentFraction = (current as? DownloadState.Downloading)?.fraction ?: 0f
        val overall = ((state.processedCount + currentFraction) / state.total * 1000).toInt()
        val position = (state.activeIndex.takeIf { it >= 0 } ?: state.processedCount) + 1
        return builder
            .setContentTitle(context.getString(R.string.batch_progress, position.coerceAtMost(state.total), state.total))
            .setContentText(active?.entry?.item?.title ?: state.title)
            .setSubText(state.title)
            .setProgress(1000, overall, false)
            .build()
    }

    fun updateBatchProgress(state: BatchState) = notify(PROGRESS_ID, batchProgress(state))

    fun batchResult(state: BatchState) {
        if (state.savedCount == 0 && state.failedCount == 0) return // cancelled before anything happened
        val summary = context.getString(R.string.batch_summary, state.savedCount, state.failedCount)
        notify(
            RESULT_ID,
            base()
                .setContentTitle(state.title)
                .setContentText(summary)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun base() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(openAppIntent())

    @SuppressLint("MissingPermission") // checked right here
    private fun notify(id: Int, notification: Notification) {
        // A declined permission only hides the notification; the download itself continues.
        // (Before API 33 the permission doesn't exist and checkSelfPermission would report "denied".)
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(id, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        context,
        1,
        DownloadService.cancelIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "downloads"
        const val PROGRESS_ID = 1
        private const val RESULT_ID = 2
    }
}
