package com.fitbittrack.pulse.data.local

import com.fitbittrack.pulse.data.remote.Metric
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.SleepStage
import com.fitbittrack.pulse.domain.SleepStageType
import com.fitbittrack.pulse.domain.Workout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

@Serializable
private data class StageRow(val t: String, val s: Long, val e: Long)

private val json = Json { ignoreUnknownKeys = true }

fun SleepSession.toEntity(wakeDate: LocalDate) = SleepEntity(
    id = id,
    startMs = start.toEpochMilli(),
    endMs = end.toEpochMilli(),
    wakeDate = wakeDate.toString(),
    mainSleep = isMainSleep,
    minutesAsleep = minutesAsleep,
    stagesJson = json.encodeToString(stages.map { StageRow(it.type.name, it.start.toEpochMilli(), it.end.toEpochMilli()) }),
)

fun SleepEntity.toDomain() = SleepSession(
    id = id,
    start = Instant.ofEpochMilli(startMs),
    end = Instant.ofEpochMilli(endMs),
    stages = runCatching { json.decodeFromString<List<StageRow>>(stagesJson) }.getOrDefault(emptyList()).map {
        SleepStage(
            runCatching { SleepStageType.valueOf(it.t) }.getOrDefault(SleepStageType.UNKNOWN),
            Instant.ofEpochMilli(it.s),
            Instant.ofEpochMilli(it.e),
        )
    },
    reportedMinutesAsleep = minutesAsleep,
    isMainSleep = mainSleep,
    wakeDate = runCatching { LocalDate.parse(wakeDate) }.getOrNull(),
)

fun Workout.toEntity(startDate: LocalDate) = WorkoutEntity(
    id, type, name, start.toEpochMilli(), end.toEpochMilli(), startDate.toString(),
    activeSeconds, caloriesKcal, distanceMeters, avgHeartRate,
)

fun WorkoutEntity.toDomain() = Workout(
    id, type, name, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs),
    activeSeconds, caloriesKcal, distanceMeters, avgHeartRate,
)

fun HeartRateEntity.toDomain() = HeartRateSample(Instant.ofEpochSecond(epochSecond), bpm)

/** Groups daily rows into one [DaySummary] per date, oldest first. */
fun List<DailyMetricEntity>.toDaySummaries(): List<DaySummary> =
    groupBy { it.date }.toSortedMap().map { (date, rows) ->
        val m = rows.associate { it.metric to it.value }
        DaySummary(
            date = LocalDate.parse(date),
            steps = m[Metric.STEPS]?.toLong(),
            distanceMeters = m[Metric.DISTANCE_M],
            floors = m[Metric.FLOORS]?.toLong(),
            caloriesKcal = m[Metric.CALORIES],
            activeZoneMinutes = m[Metric.AZM]?.toLong(),
            activeMinutes = m[Metric.ACTIVE_MIN]?.toLong(),
            restingHeartRate = m[Metric.RESTING_HR]?.roundToInt(),
            hrvMs = m[Metric.HRV],
            spo2Percent = m[Metric.SPO2],
            respiratoryRate = m[Metric.RESP_RATE],
            skinTempDeltaC = m[Metric.SKIN_TEMP_DELTA],
            vo2Max = m[Metric.VO2MAX],
        )
    }

/** 24 slots for local hours 0..23; null where Google has no value yet. */
fun List<HourlyStepsEntity>.toHourly(): List<Long?> {
    val slots = arrayOfNulls<Long>(24)
    forEach { if (it.hour in 0..23) slots[it.hour] = it.steps }
    return slots.toList()
}
