package pl.fehu.imageresizer.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import pl.fehu.imageresizer.R

/**
 * Builds the foreground progress notification and the end-of-job summary notification
 * (application-plan.md §7.2). Main-safe; notification APIs don't require a particular dispatcher.
 */
class JobNotifications(private val context: Context) {

    init {
        ensureChannel()
    }

    fun foregroundInfo(done: Int, total: Int, cancelIntent: PendingIntent?): ForegroundInfo {
        val notification = progressNotification(done, total, cancelIntent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    fun updateProgress(done: Int, total: Int, cancelIntent: PendingIntent?) {
        notifyIfPermitted(PROGRESS_NOTIFICATION_ID, progressNotification(done, total, cancelIntent))
    }

    fun showSummary(succeeded: Int, failed: Int, skipped: Int, cancelled: Boolean) {
        val text = if (cancelled) {
            context.getString(R.string.notification_summary_cancelled_text, succeeded, failed, skipped)
        } else {
            context.getString(R.string.notification_summary_text, succeeded, failed, skipped)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_summary_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        notifyIfPermitted(SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun progressNotification(done: Int, total: Int, cancelIntent: PendingIntent?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_progress_title))
            .setContentText(context.getString(R.string.notification_progress_text, done, total))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total.coerceAtLeast(1)), total <= 0)
        if (cancelIntent != null) {
            builder.addAction(0, context.getString(R.string.notification_action_cancel), cancelIntent)
        }
        return builder.build()
    }

    private fun notifyIfPermitted(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_jobs),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_jobs_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "resize_jobs"
        const val PROGRESS_NOTIFICATION_ID = 1001
        const val SUMMARY_NOTIFICATION_ID = 1002
    }
}
