package com.fitbittrack.pulse.reminders

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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fitbittrack.pulse.MainActivity
import com.fitbittrack.pulse.PulseGraph
import com.fitbittrack.pulse.R
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

private const val CHANNEL = "reminders"

/** True when Pulse may post notifications on this phone. */
fun notificationsAllowed(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Posts a notification, only when the user allowed notifications. Returns whether it was posted. */
@Suppress("MissingPermission")
internal fun postNotice(context: Context, id: Int, title: String, text: String): Boolean {
    if (!notificationsAllowed(context)) return false
    val nm = NotificationManagerCompat.from(context)
    if (Build.VERSION.SDK_INT >= 26) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Goal, bedtime, morning and band reminders" },
        )
    }
    val open = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    nm.notify(
        id,
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build(),
    )
    return true
}

/** Sends one timed reminder, then books the same one for tomorrow. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = runCatching { ReminderKind.valueOf(inputData.getString(KEY_KIND).orEmpty()) }.getOrNull() ?: return Result.failure()
        val ctx = applicationContext
        val store = UiPrefsStore(ctx)
        val prefs = store.reminders.first()
        if (ReminderLogic.isOn(kind, prefs)) {
            // Quiet hours hold back every reminder, so choose a time outside them.
            if (!ReminderLogic.inQuiet(LocalDateTime.now().toLocalTime(), prefs)) runCatching { send(ctx, kind, store) }
            Reminders.schedule(ctx, kind, prefs)
        }
        return Result.success()
    }

    private suspend fun send(ctx: Context, kind: ReminderKind, store: UiPrefsStore) {
        val repo = PulseGraph.repository(ctx)
        val today = LocalDate.now()
        val goals = repo.goals.first()
        val sum = repo.daySummary(today).first()
        val sleep = repo.sleep(today.minusDays(1), today).first().filter { it.isMainSleep }.maxOfOrNull { it.minutesAsleep }?.toInt()
        when (kind) {
            ReminderKind.Evening -> {
                val day = GoalCalendar.day(today, sum, sleep, goals, store.personal.first().calendarGoals)
                ReminderLogic.eveningText(day, store.prefs.first().units)?.let { postNotice(ctx, 1, "Your goals today", it) }
            }
            ReminderKind.Bedtime -> postNotice(ctx, 2, "Bedtime soon", "Wind down. A steady bedtime helps you reach your sleep goal.")
            ReminderKind.Morning -> postNotice(ctx, 3, "Good morning", ReminderLogic.morningText(sleep, sum?.restingHeartRate, goals.dailySteps))
        }
    }

    companion object { const val KEY_KIND = "kind" }
}

/** Checks the band every hour: low battery and "not synced". At most one of each per day. */
class BandCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = UiPrefsStore(ctx)
        val prefs = store.reminders.first()
        if (!prefs.anyBand || ReminderLogic.inQuiet(LocalDateTime.now().toLocalTime(), prefs)) return Result.success()
        val status = PulseGraph.repository(ctx).syncStatus.first()
        val today = LocalDate.now().toEpochDay()
        if (ReminderLogic.lowBattery(status.battery?.level, prefs) && store.alertDay("low") != today &&
            postNotice(ctx, 4, "Band battery low", "Your band is at ${status.battery?.level}%. Charge it when you can.")
        ) store.setAlertDay("low", today)
        if (ReminderLogic.stale(status.lastTrackerSync?.toEpochMilli(), System.currentTimeMillis(), prefs) && store.alertDay("stale") != today &&
            postNotice(ctx, 5, "Band has not synced", "No new band data for over $STALE_HOURS hours. Open the Google Health app to sync it.")
        ) store.setAlertDay("stale", today)
        return Result.success()
    }
}

object Reminders {
    private fun name(kind: ReminderKind) = "pulse-reminder-${kind.name}"
    private const val BAND = "pulse-band-check"

    fun schedule(context: Context, kind: ReminderKind, prefs: ReminderPrefs) {
        val wm = WorkManager.getInstance(context)
        if (!ReminderLogic.isOn(kind, prefs)) { wm.cancelUniqueWork(name(kind)); return }
        val delay = ReminderLogic.delayUntil(LocalDateTime.now(), ReminderLogic.timeOf(kind, prefs))
        wm.enqueueUniqueWork(
            name(kind), ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ReminderWorker.KEY_KIND to kind.name)).build(),
        )
    }

    /** Makes the booked work match the settings. Safe to call on every app start and after every change. */
    fun apply(context: Context, prefs: ReminderPrefs) {
        ReminderKind.entries.forEach { schedule(context, it, prefs) }
        val wm = WorkManager.getInstance(context)
        if (prefs.anyBand) {
            wm.enqueueUniquePeriodicWork(BAND, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<BandCheckWorker>(1, TimeUnit.HOURS).build())
        } else wm.cancelUniqueWork(BAND)
    }
}
