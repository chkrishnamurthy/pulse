package com.fitbittrack.pulse.training

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fitbittrack.pulse.reminders.ReminderLogic
import com.fitbittrack.pulse.reminders.postNotice
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Once a day: the workout planned for today, and a word when the last backup is old. Quiet hours hold both back. */
class TrainingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = TrainingGraph.prefs(ctx).settings.first()
        if (!TrainingReminder.wanted(s)) return Result.success()
        if (!ReminderLogic.inQuiet(LocalDateTime.now().toLocalTime(), UiPrefsStore(ctx).reminders.first())) runCatching { send(ctx, s) }
        TrainingReminder.schedule(ctx, s)
        return Result.success()
    }

    private suspend fun send(ctx: Context, s: TrainingSettings) {
        val dao = TrainingGraph.repository(ctx).dao
        val today = LocalDate.now().toString()
        if (s.reminderOn) {
            val p = dao.plannedBetween(today, today).first().filter { it.state == PlannedState.Planned }
            if (p.isNotEmpty() && dao.activeSessionNow() == null) postNotice(ctx, 70, p.joinToString(" and ") { it.title } + " today", "Tap to open Pulse and see it.")
        }
        val last = dao.finished().first().firstOrNull()
        if (last != null && TrainingReminder.backupDue(s, last.changedMs, Instant.now())) postNotice(ctx, 71, "Save a copy of your training", "Your workouts are only on this phone. Open You, Training, Backup.")
    }
}

object TrainingReminder {
    private const val NAME = "pulse-training-reminder"
    val BACKUP_EVERY: Duration = Duration.ofDays(14)

    fun wanted(s: TrainingSettings) = s.reminderOn || s.backupReminder

    /** Something new was logged and the last copy is older than two weeks. Asked again only every two weeks from the last copy. */
    fun backupDue(s: TrainingSettings, lastChangeMs: Long, now: Instant): Boolean {
        if (!s.backupReminder) return false
        val last = s.lastBackupMs ?: return true.takeIf { Duration.between(Instant.ofEpochMilli(lastChangeMs), now) < BACKUP_EVERY && now.epochSecond / 86_400 % 14 == 0L } ?: false
        val days = Duration.between(Instant.ofEpochMilli(last), now).toDays()
        return lastChangeMs > last && days >= 14 && days % 14 == 0L
    }

    fun schedule(context: Context, s: TrainingSettings) {
        val wm = WorkManager.getInstance(context)
        if (!wanted(s)) { wm.cancelUniqueWork(NAME); return }
        val wait = ReminderLogic.delayUntil(LocalDateTime.now(), s.reminderAt)
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<TrainingReminderWorker>().setInitialDelay(wait.toMillis(), TimeUnit.MILLISECONDS).build())
    }
}
