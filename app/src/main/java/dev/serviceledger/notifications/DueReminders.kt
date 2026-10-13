package dev.serviceledger.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.serviceledger.LedgerApplication
import dev.serviceledger.MainActivity
import dev.serviceledger.R
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object DueReminders {
    const val CHANNEL = "service-follow-ups"
    const val WORK = "daily-service-follow-ups"
    const val NOTIFICATION_ID = 101

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notification_channel_description) },
        )
    }

    fun allowed(context: Context): Boolean {
        val permission = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun enable(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DueWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class DueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("reminders", false)) return Result.success()
        if (!DueReminders.allowed(applicationContext)) {
            Log.w("DueWorker", "Notifications are disabled; the in-app due list remains available.")
            return Result.failure(workDataOf("error" to "Notifications are disabled in Android settings."))
        }
        val application = applicationContext as LedgerApplication
        val count = try {
            application.repository.snapshot().dueOnOrBefore(LocalDate.now().toEpochDay()).size
        } catch (error: SQLiteException) {
            Log.e("DueWorker", "Unable to read due services.", error)
            return if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to "Could not read service records. Open the app to retry."))
        }
        if (count == 0) {
            applicationContext.getSystemService(NotificationManager::class.java).cancel(DueReminders.NOTIFICATION_ID)
            return Result.success()
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra("show_due", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, DueReminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_ledger)
            .setContentTitle(applicationContext.getString(R.string.notification_title))
            .setContentText(applicationContext.resources.getQuantityString(R.plurals.notification_due, count, count))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            applicationContext.getSystemService(NotificationManager::class.java).notify(DueReminders.NOTIFICATION_ID, notification)
            Result.success()
        } catch (error: SecurityException) {
            Log.e("DueWorker", "Notification permission was revoked.", error)
            Result.failure(workDataOf("error" to "Notification permission was revoked."))
        }
    }
}
