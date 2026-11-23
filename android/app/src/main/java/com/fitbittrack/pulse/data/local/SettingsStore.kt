package com.fitbittrack.pulse.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fitbittrack.pulse.domain.DatedGoals
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.SyncState
import com.fitbittrack.pulse.domain.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

private val Context.pulseStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_settings")

/** Goals and sync bookkeeping. No tokens or health records are kept here. */
class SettingsStore(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.pulseStore)

    private object K {
        val stepsGoal = intPreferencesKey("goal_steps")
        val sleepGoal = intPreferencesKey("goal_sleep_min")
        val azmGoal = intPreferencesKey("goal_azm_week")
        val floorsGoal = intPreferencesKey("goal_floors")
        val caloriesGoal = intPreferencesKey("goal_calories")
        /** "epochDay:steps:sleep:zone:floors:calories|…", oldest first. */
        val goalHistory = stringPreferencesKey("goal_history")

        val state = stringPreferencesKey("sync_state") // idle | running | failed
        val progress = floatPreferencesKey("sync_progress") // < 0 = unknown
        val label = stringPreferencesKey("sync_label")
        val error = stringPreferencesKey("sync_error")
        val needsSignIn = booleanPreferencesKey("sync_needs_sign_in")
        val lastSuccess = longPreferencesKey("sync_last_success_ms")
        val lastTracker = longPreferencesKey("sync_last_tracker_ms")
        val backfillDone = booleanPreferencesKey("backfill_done")
        val historyStart = stringPreferencesKey("history_start")
        val historyLabel = stringPreferencesKey("history_label") // present only while history downloads
        val historyProgress = floatPreferencesKey("history_progress")
        val historyTried = longPreferencesKey("history_tried_ms")
    }

    val goals: Flow<Goals> = store.data.map(::readGoals)

    /** Never empty: without any change yet, the current goals count for every day. */
    val goalHistory: Flow<List<DatedGoals>> = store.data.map { p ->
        readHistory(p).ifEmpty { listOf(DatedGoals(LocalDate.ofEpochDay(0), readGoals(p))) }
    }

    private fun readHistory(p: Preferences): List<DatedGoals> = p[K.goalHistory].orEmpty().split("|").mapNotNull { entry ->
        val n = entry.split(":").mapNotNull { it.toLongOrNull() }
        if (n.size != 6) null
        else DatedGoals(LocalDate.ofEpochDay(n[0]), Goals(n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toInt(), n[5].toInt()))
    }

    private fun readGoals(p: Preferences): Goals {
        val d = Goals()
        return Goals(
            dailySteps = p[K.stepsGoal] ?: d.dailySteps,
            sleepMinutes = p[K.sleepGoal] ?: d.sleepMinutes,
            weeklyActiveZoneMinutes = p[K.azmGoal] ?: d.weeklyActiveZoneMinutes,
            dailyFloors = p[K.floorsGoal] ?: d.dailyFloors,
            dailyCalories = p[K.caloriesGoal] ?: d.dailyCalories,
        )
    }

    suspend fun setGoals(g: Goals, today: LocalDate = LocalDate.now()) {
        store.edit {
            val before = readGoals(it)
            if (before == g) return@edit
            it[K.goalHistory] = DatedGoals.record(readHistory(it), before, g, today).joinToString("|") { (from, x) ->
                listOf(from.toEpochDay(), x.dailySteps, x.sleepMinutes, x.weeklyActiveZoneMinutes, x.dailyFloors, x.dailyCalories).joinToString(":")
            }
            it[K.caloriesGoal] = g.dailyCalories
            it[K.stepsGoal] = g.dailySteps
            it[K.sleepGoal] = g.sleepMinutes
            it[K.azmGoal] = g.weeklyActiveZoneMinutes
            it[K.floorsGoal] = g.dailyFloors
        }
    }

    val syncStatus: Flow<SyncStatus> = store.data.map { p ->
        val state = when (p[K.state]) {
            "running" -> SyncState.Running(p[K.progress]?.takeIf { it >= 0f }, p[K.label] ?: "Syncing…")
            "failed" -> SyncState.Failed(p[K.error] ?: "Sync failed.", p[K.needsSignIn] ?: false)
            else -> SyncState.Idle
        }
        SyncStatus(
            state = state,
            lastSuccess = p[K.lastSuccess]?.let(Instant::ofEpochMilli),
            lastTrackerSync = p[K.lastTracker]?.let(Instant::ofEpochMilli),
            history = p[K.historyLabel]?.let { SyncState.Running(p[K.historyProgress]?.takeIf { v -> v >= 0f }, it) },
        )
    }

    suspend fun setRunning(label: String, progress: Float? = null) = store.edit {
        it[K.state] = "running"; it[K.label] = label; it[K.progress] = progress ?: -1f
    }

    suspend fun setFailed(message: String, needsSignIn: Boolean) = store.edit {
        it[K.state] = "failed"; it[K.error] = message; it[K.needsSignIn] = needsSignIn
    }

    suspend fun setSucceeded(at: Instant) = store.edit {
        it[K.state] = "idle"; it[K.lastSuccess] = at.toEpochMilli(); it.remove(K.error); it.remove(K.needsSignIn)
    }

    /** Most data arrived but something failed: the sync time moves on and the problem stays visible. */
    suspend fun setPartial(at: Instant, message: String) = store.edit {
        it[K.state] = "failed"; it[K.error] = message; it[K.needsSignIn] = false; it[K.lastSuccess] = at.toEpochMilli()
    }

    suspend fun setHistory(label: String, progress: Float? = null) = store.edit {
        it[K.historyLabel] = label; it[K.historyProgress] = progress ?: -1f
    }

    suspend fun clearHistory() = store.edit { it.remove(K.historyLabel); it.remove(K.historyProgress) }

    suspend fun historyTriedAt(): Instant? = store.data.first()[K.historyTried]?.let(Instant::ofEpochMilli)

    suspend fun setHistoryTried(at: Instant) = store.edit { it[K.historyTried] = at.toEpochMilli() }

    suspend fun setIdle() = store.edit { it[K.state] = "idle" }

    /** A "running" state left over from a process that was killed mid-sync. Call once per process, before any sync. */
    suspend fun clearStaleRunning() = store.edit {
        if (it[K.state] == "running") it[K.state] = "idle"
        it.remove(K.historyLabel); it.remove(K.historyProgress)
    }

    suspend fun setLastTrackerSync(at: Instant) = store.edit { it[K.lastTracker] = at.toEpochMilli() }

    suspend fun isBackfillDone(): Boolean = store.data.first()[K.backfillDone] ?: false

    suspend fun setBackfillDone(historyStart: LocalDate) = store.edit {
        it[K.backfillDone] = true; it[K.historyStart] = historyStart.toString()
    }

    /** Forget all sync bookkeeping (used by "delete local data"). Goals are kept. */
    suspend fun resetSync() = store.edit {
        listOf(K.state, K.progress, K.label, K.error, K.needsSignIn, K.lastSuccess, K.lastTracker, K.backfillDone, K.historyStart,
            K.historyLabel, K.historyProgress, K.historyTried)
            .forEach { k -> it.remove(k) }
    }
}
