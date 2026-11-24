package com.fitbittrack.pulse.data

import com.fitbittrack.pulse.data.local.PulseDatabase
import com.fitbittrack.pulse.data.local.SettingsStore
import com.fitbittrack.pulse.data.local.toDaySummaries
import com.fitbittrack.pulse.data.local.toDomain
import com.fitbittrack.pulse.data.local.toHourly
import com.fitbittrack.pulse.domain.DatedGoals
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.SyncStatus
import com.fitbittrack.pulse.domain.Workout
import com.fitbittrack.pulse.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class HealthRepositoryImpl(
    private val db: PulseDatabase,
    private val store: SettingsStore,
    private val engine: SyncEngine,
    /** Lives as long as the app, so on-demand fetches finish even if a screen closes. */
    private val appScope: CoroutineScope,
    /** Runs after local data is deleted (widgets redraw to "Open Pulse to sign in"). */
    private val onLocalDataChanged: suspend () -> Unit = {},
) : HealthRepository {

    private val dao = db.dao()

    override fun daySummaries(from: LocalDate, to: LocalDate): Flow<List<DaySummary>> =
        dao.daily(from.toString(), to.toString()).map { it.toDaySummaries() }.distinctUntilChanged()

    override fun daySummary(date: LocalDate): Flow<DaySummary?> =
        daySummaries(date, date).map { it.firstOrNull() }

    override fun heartRate(date: LocalDate): Flow<List<HeartRateSample>> =
        dao.heartRate(date.toString())
            .map { rows -> rows.map { it.toDomain() } }
            .onStart { appScope.launch { engine.ensureDetail(date, SyncEngine.KIND_HR) } }

    override fun hourlySteps(date: LocalDate): Flow<List<Long?>> =
        dao.hourly(date.toString())
            .map { it.toHourly() }
            .onStart { appScope.launch { engine.ensureDetail(date, SyncEngine.KIND_HOURLY) } }

    override fun savedHeartRate(date: LocalDate): Flow<List<HeartRateSample>> =
        dao.heartRate(date.toString()).map { rows -> rows.map { it.toDomain() } }

    override fun savedHourlySteps(date: LocalDate): Flow<List<Long?>> =
        dao.hourly(date.toString()).map { it.toHourly() }

    override fun sleep(from: LocalDate, to: LocalDate): Flow<List<SleepSession>> =
        dao.sleep(from.toString(), to.toString()).map { rows -> rows.map { it.toDomain() } }

    override fun workouts(from: LocalDate, to: LocalDate): Flow<List<Workout>> =
        dao.workouts(from.toString(), to.toString()).map { rows -> rows.map { it.toDomain() } }

    override val syncStatus: Flow<SyncStatus> = store.syncStatus.distinctUntilChanged()

    override val goals: Flow<Goals> = store.goals.distinctUntilChanged()

    override val goalHistory: Flow<List<DatedGoals>> = store.goalHistory.distinctUntilChanged()

    override suspend fun setGoals(goals: Goals) = store.setGoals(goals)

    /** If a full sync is already running, return at once: its progress shows in [syncStatus]. The history download never blocks this. */
    override suspend fun refreshNow() {
        if (engine.isRunning) return
        engine.sync(includeBackfill = false)
    }

    override suspend fun refreshIfStale(maxAge: Duration) {
        val last = store.syncStatus.first().lastSuccess
        // A time in the future (the phone's clock was changed) counts as stale.
        val age = last?.let { Duration.between(it, Instant.now()) }
        if (age == null || age.isNegative || age >= maxAge) refreshNow()
    }

    override suspend fun refreshToday() {
        engine.refreshToday()
    }

    /** Stops any running sync first, so nothing is written back after the delete. */
    override suspend fun deleteAllLocalData() {
        engine.exclusive {
            withContext(Dispatchers.IO) { db.clearAllTables() }
            store.resetSync()
        }
        try {
            onLocalDataChanged()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
