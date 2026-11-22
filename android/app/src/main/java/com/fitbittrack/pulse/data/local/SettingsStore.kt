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

        val state = stringPreferencesKey("sync_state") // idle | running | failed
        val progress = floatPreferencesKey("sync_progress") // < 0 = unknown
        val label = stringPreferencesKey("sync_label")
        val error = stringPreferencesKey("sync_error")
        val needsSignIn = booleanPreferencesKey("sync_needs_sign_in")
        val lastSuccess = longPreferencesKey("sync_last_success_ms")
        val lastTracker = longPreferencesKey("sync_last_tracker_ms")
        val backfillDone = booleanPreferencesKey("backfill_done")
        val historyStart = stringPreferencesKey("history_start")
    }

    val goals: Flow<Goals> = store.data.map { p ->
        val d = Goals()
        Goals(
            dailySteps = p[K.stepsGoal] ?: d.dailySteps,
            sleepMinutes = p[K.sleepGoal] ?: d.sleepMinutes,
            weeklyActiveZoneMinutes = p[K.azmGoal] ?: d.weeklyActiveZoneMinutes,
            dailyFloors = p[K.floorsGoal] ?: d.dailyFloors,
        )
    }

    suspend fun setGoals(g: Goals) {
        store.edit {
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

    suspend fun setIdle() = store.edit { it[K.state] = "idle" }

    suspend fun setLastTrackerSync(at: Instant) = store.edit { it[K.lastTracker] = at.toEpochMilli() }

    suspend fun isBackfillDone(): Boolean = store.data.first()[K.backfillDone] ?: false

    suspend fun setBackfillDone(historyStart: LocalDate) = store.edit {
        it[K.backfillDone] = true; it[K.historyStart] = historyStart.toString()
    }

    /** Forget all sync bookkeeping (used by "delete local data"). Goals are kept. */
    suspend fun resetSync() = store.edit {
        listOf(K.state, K.progress, K.label, K.error, K.needsSignIn, K.lastSuccess, K.lastTracker, K.backfillDone, K.historyStart)
            .forEach { k -> it.remove(k) }
    }
}
