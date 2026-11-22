package com.fitbittrack.pulse.ui.sample

import com.fitbittrack.pulse.ui.model.ActivityUi
import com.fitbittrack.pulse.ui.model.DayStat
import com.fitbittrack.pulse.ui.model.HeartPoint
import com.fitbittrack.pulse.ui.model.HeartUi
import com.fitbittrack.pulse.ui.model.Insight
import com.fitbittrack.pulse.ui.model.NightBar
import com.fitbittrack.pulse.ui.model.Range
import com.fitbittrack.pulse.ui.model.SleepUi
import com.fitbittrack.pulse.ui.model.Stage
import com.fitbittrack.pulse.ui.model.StageBlock
import com.fitbittrack.pulse.ui.model.StageShares
import com.fitbittrack.pulse.ui.model.TodayUi
import com.fitbittrack.pulse.ui.model.WorkoutUi
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.sin

/**
 * SAMPLE data, the same numbers as the design canvas. Used for previews and while the
 * data layer is not connected. Screens show a "Sample data" banner whenever this is used.
 */
object SampleData {

    private val today: LocalDate get() = LocalDate.now()

    fun today(stepGoal: Int = 10_000): TodayUi = TodayUi(
        date = today,
        steps = 8_420, stepGoal = stepGoal,
        distanceMeters = 6_100.0, caloriesKcal = 2_184.0,
        zoneMinutes = 34, zoneMinutesDailyTarget = 22,
        floors = 9, floorsGoal = 10,
        restingHeartRate = 58, restingHeartRateLast7 = listOf(61, 60, 61, 60, 59, 59, 58),
        hrvMs = 42.0, spo2Percent = 96.0,
        sleepMinutes = 455, sleepShares = StageShares(0.16f, 0.52f, 0.23f, 0.09f),
        stepsPerHour = listOf<Long?>(0, 0, 0, 0, 0, 0, 40, 620, 980, 310, 450, 720, 880, 260, 540) + List(9) { null },
        sleepStart = LocalTime.of(23, 10), sleepEnd = LocalTime.of(6, 45),
        now = LocalTime.of(14, 20),
        insight = Insight("Resting heart rate is down", "58 bpm today, 3 bpm below your 30-day normal. A good sign of recovery.", true),
    )

    fun heart(range: Range): HeartUi {
        val hourly = listOf(60, 57, 55, 54, 55, 56, 58, 64, 78, 74, 82, 128, 141, 96, 84)
        val samples = hourly.flatMapIndexed { h, base ->
            (0 until 12).map { k -> HeartPoint(h * 60 + k * 5, (base + 4 * sin((h * 12 + k) / 2.0)).toInt()) }
        }
        val days = range.days.coerceAtLeast(7)
        val resting = (0 until days).map { i ->
            today.minusDays((days - 1 - i).toLong()) to (61 - i * 3 / days + if (i % 4 == 0) 1 else 0)
        }
        return HeartUi(
            range = range, latestBpm = 72, minBpm = 52, maxBpm = 141,
            samples = samples, restingByDay = resting,
            restingHeartRate = 58, restingDeltaVsNormal = -3,
            hrvMs = 42.0, hrvNormalRange = 38.0..47.0,
            zoneMinutes = 34, zoneMinutesTarget = 22,
        )
    }

    fun sleep(goalMinutes: Int = 480): SleepUi {
        val plan = listOf(
            Stage.Light to 14, Stage.Deep to 38, Stage.Light to 22, Stage.Rem to 12, Stage.Awake to 4, Stage.Light to 30,
            Stage.Deep to 22, Stage.Light to 25, Stage.Rem to 24, Stage.Light to 28, Stage.Awake to 8, Stage.Deep to 12,
            Stage.Light to 30, Stage.Rem to 30, Stage.Light to 34, Stage.Awake to 6, Stage.Light to 26, Stage.Rem to 39,
            Stage.Awake to 23, Stage.Light to 22,
        )
        var t = 0
        val blocks = plan.map { (s, m) -> StageBlock(s, t, t + m).also { t += m } }
        val byStage = Stage.entries.associateWith { s -> plan.filter { it.first == s }.sumOf { it.second } }
        val nights = listOf("M" to (310 to 750), "T" to (300 to 690), "W" to (320 to 760), "T" to (305 to 720), "F" to (330 to 815), "S" to (295 to 760), "S" to (310 to 765))
        return SleepUi(
            nightOf = today.minusDays(1), start = LocalTime.of(23, 10), end = LocalTime.of(6, 45),
            minutesAsleep = t - (byStage[Stage.Awake] ?: 0), minutesByStage = byStage,
            blocks = blocks, totalMinutesInBed = t,
            spo2Percent = 96.0, respiratoryRate = 14.2, skinTempDeltaC = 0.3,
            week = nights.mapIndexed { i, (l, se) -> NightBar(l, se.first, se.second, i == nights.lastIndex) },
            sleepGoalMinutes = goalMinutes,
        )
    }

    fun activity(): ActivityUi = ActivityUi(
        weekZoneMinutes = 168, weekZoneTarget = 150, weekDistanceMeters = 38_400.0, weekActiveMinutes = 250,
        workouts = listOf(
            WorkoutUi("1", "RUN", "Morning run", "Today 11:02", 31, 5_200.0, 148, 410.0),
            WorkoutUi("2", "WLK", "Walk", "Fri 18:40", 46, 3_800.0, 104, 190.0),
            WorkoutUi("3", "BIK", "Outdoor bike", "Wed 07:15", 52, 18_600.0, 131, 520.0),
            WorkoutUi("4", "GYM", "Weights", "Mon 19:05", 40, null, 112, 230.0),
        ),
    )

    fun days(count: Int): List<DayStat> = (0 until count).map { i ->
        val d = today.minusDays((count - 1 - i).toLong())
        val wave = sin(i / 3.0)
        DayStat(
            date = d,
            steps = (9_300 + 2_600 * wave + (i * 37 % 900)).toLong(),
            sleepMinutes = (432 + 40 * sin(i / 2.0)).toInt(),
            restingHeartRate = 61 - (i * 3 / count.coerceAtLeast(1)),
            zoneMinutes = (24 + 14 * wave).toLong().coerceAtLeast(0),
            distanceMeters = 6_500 + 1_800 * wave,
        )
    }
}
