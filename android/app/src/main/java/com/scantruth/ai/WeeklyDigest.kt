package com.scantruth.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** The "Weekly Health Scan Digest" setting: a weekly reminder notification (Sundays, 9:00). */
object WeeklyDigest {
    private const val WORK_NAME = "weekly-scan-digest"
    internal const val CHANNEL_ID = "weekly_digest"

    fun notificationsAllowed(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.of(9, 0))
        if (!next.isAfter(now)) next = next.plusWeeks(1)

        val request = PeriodicWorkRequestBuilder<WeeklyDigestWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class WeeklyDigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!WeeklyDigest.notificationsAllowed(context)) return Result.success()

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    WeeklyDigest.CHANNEL_ID,
                    "Weekly scan digest",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, WeeklyDigest.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Your weekly scan digest")
            .setContentText(
                "Open ScanTruthAI to review this week's scanned products against your health goals."
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(WEEKLY_DIGEST_NOTIFICATION_ID, notification)
        return Result.success()
    }

    private companion object {
        const val WEEKLY_DIGEST_NOTIFICATION_ID = 7001
    }
}
