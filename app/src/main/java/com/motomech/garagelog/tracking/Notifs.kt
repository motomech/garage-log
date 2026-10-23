package com.motomech.garagelog.tracking

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.motomech.garagelog.R
import com.motomech.garagelog.data.Level
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.docStatus
import com.motomech.garagelog.data.vehicle
import com.motomech.garagelog.data.markNotified
import com.motomech.garagelog.data.vehicleReminders
import com.motomech.garagelog.ui.MainActivity
import java.util.concurrent.TimeUnit

object Notifs {
    const val CH_TRIP = "trip"
    const val CH_REMIND = "reminders"
    const val CH_ALERT = "alerts"
    const val ID_TRIP = 1
    const val ID_TRIP_DONE = 2
    const val ID_START_FAILED = 3
    /** Intent action MainActivity handles by opening Log fill-up. */
    const val ACTION_LOG_FUEL = "com.motomech.garagelog.LOG_FUEL"
    const val ID_BACKUP_FAILED = 4
    private const val ID_DOC_BASE = 100_000
    private const val ID_REMINDER_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_TRIP, "Trip in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a trip is being measured"
            },
            NotificationChannel(CH_REMIND, "Service and document reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Service due soon or overdue, registration or insurance about to expire"
            },
            NotificationChannel(CH_ALERT, "Trips and tracking", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Trip finished, or tracking needs your attention"
            },
        ))
    }

    fun openAppIntent(context: Context, extra: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (extra != null) action = extra
        }
        return PendingIntent.getActivity(context, extra.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun post(context: Context, id: Int, channel: String, title: String, text: String, extra: String? = null) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, extra))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /** Posts a notification for each reminder that has newly become due soon or overdue. */
    fun checkReminders(context: Context) {
        Repo.init(context)
        val d = Repo.current
        d.vehicles.forEach { v ->
            d.vehicleReminders(v.id).forEach { (r, st) ->
                if (st.level == Level.OK) return@forEach
                val key = "${r.id}:${r.lastOdo}:${r.lastDate}:${st.level}"
                if (key in Repo.current.notified) return@forEach
                Repo.update { it.markNotified(key) }
                post(
                    context, ID_REMINDER_BASE + r.id.toInt(), CH_REMIND,
                    r.type + if (st.level == Level.BAD) " is overdue" else " due soon",
                    "${v.label} · ${st.text}",
                )
            }
        }
        // Documents with an expiry date: once when 30 days are left, once when it has expired.
        Repo.current.docs.forEach { doc ->
            if (!doc.remind) return@forEach
            val (level, text) = docStatus(doc) ?: return@forEach
            if (level == Level.OK) return@forEach
            val key = "doc:${doc.id}:${doc.expires}:$level"
            if (key in Repo.current.notified) return@forEach
            Repo.update { it.markNotified(key) }
            val v = Repo.current.vehicle(doc.vid)
            post(
                context, ID_DOC_BASE + doc.id.toInt(), CH_REMIND,
                doc.label + if (level == Level.BAD) " has expired" else " expires soon",
                listOfNotNull(v?.label, text).joinToString(" · "),
            )
        }
    }

    fun scheduleDaily(context: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Once a day, so date-based reminders fire even on days the vehicle isn't driven. */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Notifs.checkReminders(applicationContext)
        return Result.success()
    }
}
