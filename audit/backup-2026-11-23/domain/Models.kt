package com.fitbittrack.pulse.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Plain data the screens draw. Every value is nullable: null means "no data for that day",
 * which happens when the tracker has not synced yet or the Fitbit Air does not record it.
 */
data class DaySummary(
    val date: LocalDate,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val floors: Long? = null,
    val caloriesKcal: Double? = null,
    val activeZoneMinutes: Long? = null,
    val activeMinutes: Long? = null,
    val restingHeartRate: Int? = null,
    val hrvMs: Double? = null,
    val spo2Percent: Double? = null,
    val respiratoryRate: Double? = null,
    /** Nightly skin temperature minus your personal baseline, in °C. */
    val skinTempDeltaC: Double? = null,
    val vo2Max: Double? = null,
)

data class HeartRateSample(val time: Instant, val bpm: Int)

enum class SleepStageType { AWAKE, LIGHT, DEEP, REM, UNKNOWN }

data class SleepStage(val type: SleepStageType, val start: Instant, val end: Instant) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}

data class SleepSession(
    val id: String,
    val start: Instant,
    val end: Instant,
    val stages: List<SleepStage>,
    /** Google's own "minutes asleep" when it sends one; otherwise worked out from the stages. */
    private val reportedMinutesAsleep: Long? = null,
    val isMainSleep: Boolean = true,
    /** The local day you woke up, from Google's civil end time (correct even after travel). */
    val wakeDate: LocalDate? = null,
) {
    val minutesAsleep: Long
        get() = reportedMinutesAsleep
            ?: stages.filter { it.type != SleepStageType.AWAKE }.sumOf { it.minutes }
                .takeIf { stages.isNotEmpty() }
            ?: Duration.between(start, end).toMinutes()

    fun minutesIn(type: SleepStageType): Long = stages.filter { it.type == type }.sumOf { it.minutes }
}

data class Workout(
    val id: String,
    /** Google's exercise type code, for example RUNNING or BIKING. */
    val type: String,
    val name: String,
    val start: Instant,
    val end: Instant,
    val activeSeconds: Long? = null,
    val caloriesKcal: Double? = null,
    val distanceMeters: Double? = null,
    val avgHeartRate: Int? = null,
)

data class Goals(
    val dailySteps: Int = 10000,
    val sleepMinutes: Int = 480,
    val weeklyActiveZoneMinutes: Int = 150,
    val dailyFloors: Int = 10,
)

sealed interface SyncState {
    data object Idle : SyncState
    /** [progress] is 0..1 during the first history download, null when unknown. */
    data class Running(val progress: Float?, val label: String) : SyncState
    data class Failed(val message: String, val needsSignIn: Boolean) : SyncState
}

data class SyncStatus(
    val state: SyncState = SyncState.Idle,
    /** When the app last finished a sync with Google. */
    val lastSuccess: Instant? = null,
    /** When the Fitbit Air last synced to the Google Health app (from paired devices). */
    val lastTrackerSync: Instant? = null,
)
