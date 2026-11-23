package com.fitbittrack.pulse.ui.widget

import android.content.Context
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.SyncStatus
import com.fitbittrack.pulse.ui.DataLayerBridge
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.Mappers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Everything any widget shows, read once from the local database. Widgets never call Google. */
data class WidgetData(
    val signedIn: Boolean,
    val steps: Long?,
    val stepGoal: Int,
    val zoneMinutes: Long?,
    val zoneTarget: Int,
    val restingHeartRate: Int?,
    val restingLast7: List<Int?>,
    val sleepMinutes: Int?,
    val sleepGoal: Int,
    val sleepShares: com.fitbittrack.pulse.ui.model.StageShares?,
    val spo2: Double?,
    val hrv: Double?,
    val syncedAgo: String,
    val smart: SmartState,
)

/** What the smart widget shows right now. */
data class SmartState(val label: String, val headline: String, val detail: String, val kind: Kind) {
    enum class Kind { Sleep, Steps, Evening, Empty }
}

object WidgetDataLoader {

    suspend fun load(context: Context): WidgetData {
        val repo = DataLayerBridge.savedRepository(context)
        val today = LocalDate.now()
        return withTimeoutOrNull(5_000) {
            // Nothing synced yet means not signed in (or the first download hasn't finished).
            if (repo.syncStatus.first().lastSuccess == null) return@withTimeoutOrNull empty()
            val goals: Goals = repo.goals.first()
            val day = repo.daySummary(today).first()
            val last30 = repo.daySummaries(today.minusDays(30), today).first()
            val night = repo.sleep(today, today).first().lastOrNull { it.isMainSleep }
            val sync: SyncStatus = repo.syncStatus.first()
            val ui = Mappers.today(today, day, last30, emptyList(), night, goals, LocalTime.now())
            WidgetData(
                signedIn = true,
                steps = ui.steps, stepGoal = ui.stepGoal,
                zoneMinutes = ui.zoneMinutes, zoneTarget = ui.zoneMinutesDailyTarget,
                restingHeartRate = ui.restingHeartRate, restingLast7 = ui.restingHeartRateLast7,
                sleepMinutes = ui.sleepMinutes, sleepGoal = goals.sleepMinutes, sleepShares = ui.sleepShares,
                spo2 = ui.spo2Percent, hrv = ui.hrvMs,
                syncedAgo = Fmt.ago(sync.lastSuccess),
                smart = smart(ui.steps, ui.stepGoal, ui.sleepMinutes, goals, night?.end?.atZone(ZoneId.systemDefault())?.toLocalTime()),
            )
        } ?: empty()
    }

    /** Morning: last night's sleep. Daytime: steps to go. Evening: wind-down. */
    fun smart(steps: Long?, stepGoal: Int, sleepMin: Int?, goals: Goals, wokeAt: LocalTime?, now: LocalTime = LocalTime.now()): SmartState {
        val hour = now.hour
        return when {
            hour < 11 && sleepMin != null -> SmartState(
                "LAST NIGHT${wokeAt?.let { " · woke ${Fmt.clock(it)}" } ?: ""}",
                Fmt.minutes(sleepMin) + " slept",
                if (sleepMin >= goals.sleepMinutes) "Sleep goal reached." else "${Fmt.minutes(goals.sleepMinutes - sleepMin)} under your goal.",
                SmartState.Kind.Sleep,
            )
            hour >= 21 -> SmartState(
                "EVENING",
                if ((steps ?: 0) >= stepGoal) "Step goal done today" else "${Fmt.count(steps)} steps today",
                "Aim for ${Fmt.minutes(goals.sleepMinutes)} of sleep tonight.",
                SmartState.Kind.Evening,
            )
            steps == null -> SmartState("TODAY", "No steps yet", "Waiting for your band to sync.", SmartState.Kind.Empty)
            steps >= stepGoal -> SmartState("TODAY", "Step goal reached", "${Fmt.count(steps)} steps so far.", SmartState.Kind.Steps)
            else -> {
                val left = stepGoal - steps
                SmartState("TODAY", "${Fmt.count(left)} steps to go", "About ${(left / 100).coerceAtLeast(1)} min of walking.", SmartState.Kind.Steps)
            }
        }
    }

    private fun empty() = WidgetData(
        signedIn = false, steps = null, stepGoal = 10_000, zoneMinutes = null, zoneTarget = 22,
        restingHeartRate = null, restingLast7 = emptyList(), sleepMinutes = null, sleepGoal = 480, sleepShares = null,
        spo2 = null, hrv = null, syncedAgo = "never",
        smart = SmartState("PULSE", "Open Pulse to sign in", "Your health data appears here after the first sync.", SmartState.Kind.Empty),
    )
}
