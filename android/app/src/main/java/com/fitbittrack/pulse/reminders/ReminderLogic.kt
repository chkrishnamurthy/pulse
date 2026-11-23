package com.fitbittrack.pulse.reminders

import com.fitbittrack.pulse.ui.goals.DayGoals
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.screens.goalText
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** The reminders and band alerts the user chose. Times are local. */
data class ReminderPrefs(
    val eveningOn: Boolean = false,
    val evening: LocalTime = LocalTime.of(19, 0),
    val bedtimeOn: Boolean = false,
    /** The usual bedtime. The reminder comes [BEDTIME_LEAD_MIN] minutes before. */
    val bedtime: LocalTime = LocalTime.of(22, 30),
    val morningOn: Boolean = false,
    val morning: LocalTime = LocalTime.of(7, 30),
    val quietOn: Boolean = true,
    val quietFrom: LocalTime = LocalTime.of(22, 0),
    val quietTo: LocalTime = LocalTime.of(7, 0),
    val lowBatteryOn: Boolean = false,
    val lowBatteryAt: Int = 20,
    val staleOn: Boolean = false,
) {
    val anyTimed: Boolean get() = eveningOn || bedtimeOn || morningOn
    val anyBand: Boolean get() = lowBatteryOn || staleOn
}

enum class ReminderKind { Evening, Bedtime, Morning }

const val BEDTIME_LEAD_MIN = 30L

/** The band counts as not synced after this many hours. */
const val STALE_HOURS = 6L

object ReminderLogic {

    /** When a [kind] reminder is due each day. */
    fun timeOf(kind: ReminderKind, p: ReminderPrefs): LocalTime = when (kind) {
        ReminderKind.Evening -> p.evening
        ReminderKind.Bedtime -> p.bedtime.minusMinutes(BEDTIME_LEAD_MIN)
        ReminderKind.Morning -> p.morning
    }

    fun isOn(kind: ReminderKind, p: ReminderPrefs): Boolean = when (kind) {
        ReminderKind.Evening -> p.eveningOn
        ReminderKind.Bedtime -> p.bedtimeOn
        ReminderKind.Morning -> p.morningOn
    }

    /** Time to wait from [now] until [at] comes round: today if it is still ahead, else tomorrow. Never zero. */
    fun delayUntil(now: LocalDateTime, at: LocalTime): Duration {
        var next = now.toLocalDate().atTime(at)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** Whether [t] is inside the quiet hours. The window may run over midnight (22:00 to 07:00). */
    fun inQuiet(t: LocalTime, p: ReminderPrefs): Boolean {
        if (!p.quietOn || p.quietFrom == p.quietTo) return false
        return if (p.quietFrom < p.quietTo) t >= p.quietFrom && t < p.quietTo else t >= p.quietFrom || t < p.quietTo
    }

    /** What is still missing today, for the evening nudge. Null when there is nothing to say. */
    fun eveningText(day: DayGoals, units: Units): String? {
        if (day.core.none { it.value != null }) return "Your band has not sent today's numbers yet. Sync it to see your goals."
        val left = day.core.filter { !it.met }
        if (left.isEmpty()) return null
        val parts = left.map { g ->
            val remaining = (g.target - (g.value ?: 0.0)).coerceAtLeast(0.0)
            goalText(g.metric, remaining, units) + if (g.metric == GoalMetric.Sleep) " of sleep" else ""
        }
        return "Left today: " + parts.joinToString(", ") + "."
    }

    fun morningText(sleepMinutes: Int?, restingHr: Int?, stepGoal: Int): String {
        val sleep = sleepMinutes?.let { "You slept ${it / 60} h ${it % 60} min." } ?: "No sleep data yet."
        val hr = restingHr?.let { " Resting heart rate $it." }.orEmpty()
        return "$sleep$hr Today: ${"%,d".format(stepGoal)} steps."
    }

    fun lowBattery(level: Int?, p: ReminderPrefs): Boolean = p.lowBatteryOn && level != null && level <= p.lowBatteryAt

    fun stale(lastTrackerMs: Long?, nowMs: Long, p: ReminderPrefs): Boolean =
        p.staleOn && lastTrackerMs != null && nowMs - lastTrackerMs > Duration.ofHours(STALE_HOURS).toMillis()
}
