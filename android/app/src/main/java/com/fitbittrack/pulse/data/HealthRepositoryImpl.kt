package com.fitbittrack.pulse.data

import com.fitbittrack.pulse.data.local.PulseDatabase
import com.fitbittrack.pulse.data.local.SettingsStore
import com.fitbittrack.pulse.data.local.toDaySummaries
import com.fitbittrack.pulse.data.local.toDomain
import com.fitbittrack.pulse.data.local.toHourly
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class HealthRepositoryImpl(
    private val db: PulseDatabase,
    private val store: SettingsStore,
    private val engine: SyncEngine,
    /** Lives as long as the app, so on-demand fetches finish even if a screen closes. */
    private val appScope: CoroutineScope,
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

    override fun sleep(from: LocalDate, to: LocalDate): Flow<List<SleepSession>> =
        dao.sleep(from.toString(), to.toString()).map { rows -> rows.map { it.toDomain() } }

    override fun workouts(from: LocalDate, to: LocalDate): Flow<List<Workout>> =
        dao.workouts(from.toString(), to.toString()).map { rows -> rows.map { it.toDomain() } }

    override val syncStatus: Flow<SyncStatus> = store.syncStatus.distinctUntilChanged()

    override val goals: Flow<Goals> = store.goals.distinctUntilChanged()

    override suspend fun setGoals(goals: Goals) = store.setGoals(goals)

    override suspend fun refreshNow() {
        engine.sync(includeBackfill = false)
    }

    override suspend fun deleteAllLocalData() {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        store.resetSync()
    }
}
