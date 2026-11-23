package com.fitbittrack.pulse.ui.sample

import com.fitbittrack.pulse.domain.DatedGoals
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.SleepStage
import com.fitbittrack.pulse.domain.SleepStageType
import com.fitbittrack.pulse.domain.SyncState
import com.fitbittrack.pulse.domain.SyncStatus
import com.fitbittrack.pulse.domain.Workout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.sin

/**
 * A pretend repository that returns SAMPLE data shaped exactly like the real one.
 * Used before sign-in and in previews, so every screen can be seen and tested without Google.
 */
class SampleHealthRepository : HealthRepository {

    private val zone = ZoneId.systemDefault()
    private val goalsState = MutableStateFlow(Goals())
    private val status = MutableStateFlow(SyncStatus(SyncState.Idle, Instant.now().minusSeconds(720), Instant.now().minusSeconds(900)))

    private fun at(date: LocalDate, h: Int, m: Int = 0): Instant = date.atTime(h, m).atZone(zone).toInstant()

    private fun summary(d: LocalDate): DaySummary {
        val i = d.toEpochDay().toInt()
        val wave = sin(i / 3.0)
        val isToday = d == LocalDate.now()
        return DaySummary(
            date = d,
            steps = if (isToday) 8_420 else (9_300 + 2_600 * wave + (i * 37 % 900)).toLong(),
            distanceMeters = if (isToday) 6_100.0 else 6_500 + 1_800 * wave,
            floors = (8 + 4 * wave).toLong().coerceAtLeast(0),
            caloriesKcal = if (isToday) 2_184.0 else 2_760 + 330 * sin(i / 2.0) + (i * 53 % 120),
            activeZoneMinutes = if (isToday) 34 else (24 + 14 * wave).toLong().coerceAtLeast(0),
            activeMinutes = (35 + 15 * wave).toLong(),
            restingHeartRate = if (isToday) 58 else 60 + (sin(i / 5.0) * 1.5).toInt(),
            hrvMs = 42 + 4 * sin(i / 4.0),
            spo2Percent = 96.0,
            respiratoryRate = 14.2,
            skinTempDeltaC = 0.3 * sin(i / 6.0),
            vo2Max = 44.0,
        )
    }

    override fun daySummaries(from: LocalDate, to: LocalDate): Flow<List<DaySummary>> =
        flowOf(generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) && !it.isAfter(LocalDate.now()) }.map(::summary).toList())

    override fun daySummary(date: LocalDate): Flow<DaySummary?> = flowOf(summary(date))

    override fun heartRate(date: LocalDate): Flow<List<HeartRateSample>> {
        val hourly = listOf(60, 57, 55, 54, 55, 56, 58, 64, 78, 74, 82, 128, 141, 96, 84, 88, 76, 72, 70, 68, 66, 64, 62, 61)
        val upTo = if (date == LocalDate.now()) LocalTime.now().hour else 23
        return flowOf(hourly.take(upTo + 1).flatMapIndexed { h, base ->
            (0 until 12).map { k -> HeartRateSample(at(date, h, k * 5), (base + 4 * sin((h * 12 + k) / 2.0)).toInt()) }
        })
    }

    override fun hourlySteps(date: LocalDate): Flow<List<Long?>> {
        val pattern = listOf<Long>(0, 0, 0, 0, 0, 0, 40, 620, 980, 310, 450, 720, 880, 260, 540, 610, 480, 900, 760, 320, 180, 90, 20, 0)
        val upTo = if (date == LocalDate.now()) LocalTime.now().hour else 23
        return flowOf(pattern.mapIndexed { h, v -> if (h <= upTo) v else null })
    }

    override fun sleep(from: LocalDate, to: LocalDate): Flow<List<SleepSession>> =
        flowOf(generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) && !it.isAfter(LocalDate.now()) }.map(::night).toList())

    private fun night(wakeDay: LocalDate): SleepSession {
        val shift = (wakeDay.toEpochDay() % 5).toInt() * 6
        val start = at(wakeDay.minusDays(1), 23, 0).plusSeconds(shift * 60L)
        val plan = listOf(
            SleepStageType.LIGHT to 14, SleepStageType.DEEP to 38, SleepStageType.LIGHT to 22, SleepStageType.REM to 12,
            SleepStageType.AWAKE to 4, SleepStageType.LIGHT to 30, SleepStageType.DEEP to 22, SleepStageType.LIGHT to 25,
            SleepStageType.REM to 24, SleepStageType.LIGHT to 28, SleepStageType.AWAKE to 8, SleepStageType.DEEP to 12,
            SleepStageType.LIGHT to 30, SleepStageType.REM to 30, SleepStageType.LIGHT to 34, SleepStageType.AWAKE to 6,
            SleepStageType.LIGHT to 26, SleepStageType.REM to 39, SleepStageType.AWAKE to 23, SleepStageType.LIGHT to 22,
        )
        // Nights differ, so the calendar has something to show.
        val longer = (wakeDay.toEpochDay() * 37 % 7).toInt() * 16
        var t = start
        val stages = (plan + (SleepStageType.LIGHT to 1 + longer)).map { (type, m) -> SleepStage(type, t, t.plusSeconds(m * 60L)).also { t = it.end } }
        return SleepSession("sample-$wakeDay", start, t, stages)
    }

    override fun workouts(from: LocalDate, to: LocalDate): Flow<List<Workout>> {
        val today = LocalDate.now()
        return flowOf(
            listOf(
                Workout("w1", "RUNNING", "Morning run", at(today, 11, 2), at(today, 11, 33), 31 * 60, 410.0, 5_200.0, 148),
                Workout("w2", "WALKING", "Walk", at(today.minusDays(2), 18, 40), at(today.minusDays(2), 19, 26), 46 * 60, 190.0, 3_800.0, 104),
                Workout("w3", "BIKING", "Outdoor bike", at(today.minusDays(4), 7, 15), at(today.minusDays(4), 8, 7), 52 * 60, 520.0, 18_600.0, 131),
                Workout("w4", "WEIGHTLIFTING", "Weights", at(today.minusDays(6), 19, 5), at(today.minusDays(6), 19, 45), 40 * 60, 230.0, null, 112),
            ).filter { val d = it.start.atZone(zone).toLocalDate(); !d.isBefore(from) && !d.isAfter(to) }
        )
    }

    override val syncStatus: Flow<SyncStatus> = status
    override val goals: Flow<Goals> = goalsState.map { it }
    private val historyState = MutableStateFlow(listOf(DatedGoals(LocalDate.ofEpochDay(0), Goals())))
    override val goalHistory: Flow<List<DatedGoals>> = historyState

    override suspend fun setGoals(goals: Goals) {
        historyState.value = DatedGoals.record(historyState.value, goalsState.value, goals, LocalDate.now())
        goalsState.value = goals
    }

    override suspend fun refreshNow() {
        status.value = status.value.copy(state = SyncState.Running(null, "Sample refresh"))
        delay(800)
        status.value = SyncStatus(SyncState.Idle, Instant.now(), status.value.lastTrackerSync)
    }

    override suspend fun deleteAllLocalData() = Unit
}
