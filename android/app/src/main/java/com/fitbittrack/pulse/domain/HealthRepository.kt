package com.fitbittrack.pulse.domain

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The one place screens read health data from. Everything comes from the local database,
 * so screens open instantly and work offline. Syncing fills the database in the background.
 */
interface HealthRepository {
    /** One entry per day that has any data, oldest first. Inclusive range. */
    fun daySummaries(from: LocalDate, to: LocalDate): Flow<List<DaySummary>>
    fun daySummary(date: LocalDate): Flow<DaySummary?>

    /** Heart rate for one local day, in 5-minute averages. Fetched on demand for older days. */
    fun heartRate(date: LocalDate): Flow<List<HeartRateSample>>

    /** 24 entries for local hours 0..23. null = no data for that hour yet. Fetched on demand for older days. */
    fun hourlySteps(date: LocalDate): Flow<List<Long?>>

    /** Sleep sessions whose wake-up day is in the range, oldest first. */
    fun sleep(from: LocalDate, to: LocalDate): Flow<List<SleepSession>>

    /** Workouts that started in the range, newest first. */
    fun workouts(from: LocalDate, to: LocalDate): Flow<List<Workout>>

    val syncStatus: Flow<SyncStatus>

    val goals: Flow<Goals>
    suspend fun setGoals(goals: Goals)

    /** Runs a sync right now (pull-to-refresh). Returns when it has finished. */
    suspend fun refreshNow()

    /** Deletes the local copy only. Your data at Google is not touched. */
    suspend fun deleteAllLocalData()
}
