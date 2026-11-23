package com.fitbittrack.pulse.ui.model

import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.SleepStageType
import com.fitbittrack.pulse.domain.SyncState
import com.fitbittrack.pulse.domain.SyncStatus
import com.fitbittrack.pulse.domain.Workout
import com.fitbittrack.pulse.ui.format.Fmt
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Turns domain data (from the data layer) into what each screen draws. Pure functions. */
object Mappers {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun Instant.local(): LocalTime = atZone(zone).toLocalTime()

    fun dailyZoneTarget(g: Goals) = (g.weeklyActiveZoneMinutes / 7.0).roundToInt().coerceAtLeast(1)

    fun today(
        date: LocalDate,
        day: DaySummary?,
        last30: List<DaySummary>,
        hourly: List<Long?>,
        lastNight: SleepSession?,
        goals: Goals,
        now: LocalTime,
    ): TodayUi {
        val last7 = (6 downTo 0).map { back -> last30.firstOrNull { it.date == date.minusDays(back.toLong()) }?.restingHeartRate }
        return TodayUi(
            date = date,
            steps = day?.steps,
            stepGoal = goals.dailySteps,
            distanceMeters = day?.distanceMeters,
            caloriesKcal = day?.caloriesKcal,
            zoneMinutes = day?.activeZoneMinutes,
            zoneMinutesDailyTarget = dailyZoneTarget(goals),
            floors = day?.floors,
            floorsGoal = goals.dailyFloors,
            restingHeartRate = day?.restingHeartRate,
            restingHeartRateLast7 = last7,
            hrvMs = day?.hrvMs,
            spo2Percent = day?.spo2Percent,
            sleepMinutes = lastNight?.minutesAsleep?.toInt(),
            sleepShares = lastNight?.let { shares(it) },
            stepsPerHour = hourly,
            sleepStart = lastNight?.start?.local(),
            sleepEnd = lastNight?.end?.local(),
            now = now,
            insight = insight(day, last30, lastNight, goals),
        )
    }

    /** One plain-English observation, comparing you with your own 30-day normal. */
    fun insight(day: DaySummary?, last30: List<DaySummary>, lastNight: SleepSession?, goals: Goals): Insight? {
        val rhr = day?.restingHeartRate
        val normal = last30.filter { it.date != day?.date }.mapNotNull { it.restingHeartRate }.takeIf { it.size >= 7 }?.average()
        if (rhr != null && normal != null) {
            val d = (rhr - normal).roundToInt()
            if (d <= -2) return Insight("Resting heart rate is down", "$rhr bpm today, ${-d} bpm below your 30-day normal. A good sign of recovery.", true)
            if (d >= 3) return Insight("Resting heart rate is up", "$rhr bpm today, $d bpm above your normal. Rest, stress or illness can do this.", false)
        }
        if (lastNight != null) {
            val short = goals.sleepMinutes - lastNight.minutesAsleep
            if (short >= 60) return Insight("Short night", "You slept ${Fmt.minutes(lastNight.minutesAsleep.toInt())}, ${Fmt.minutes(short.toInt())} under your goal.", false)
            if (short <= 0) return Insight("Sleep goal reached", "${Fmt.minutes(lastNight.minutesAsleep.toInt())} of sleep last night.", true)
        }
        return null
    }

    private fun shares(s: SleepSession): StageShares? {
        val total = s.stages.sumOf { it.minutes }.toFloat().takeIf { it > 0 } ?: return null
        return StageShares(
            deep = s.minutesIn(SleepStageType.DEEP) / total,
            light = s.minutesIn(SleepStageType.LIGHT) / total,
            rem = s.minutesIn(SleepStageType.REM) / total,
            awake = s.minutesIn(SleepStageType.AWAKE) / total,
        )
    }

    fun heart(
        range: Range,
        samples: List<HeartRateSample>,
        summaries: List<DaySummary>,
        today: LocalDate,
        goals: Goals,
    ): HeartUi {
        val todaySummary = summaries.lastOrNull { it.date == today }
        val restingNow = todaySummary?.restingHeartRate ?: summaries.lastOrNull { it.restingHeartRate != null }?.restingHeartRate
        val normal = summaries.filter { it.date != today }.mapNotNull { it.restingHeartRate }.takeIf { it.size >= 7 }?.average()
        val hrvs = summaries.mapNotNull { it.hrvMs }
        val hrvRange = if (hrvs.size >= 7) {
            val mean = hrvs.average()
            val sd = kotlin.math.sqrt(hrvs.sumOf { (it - mean) * (it - mean) } / hrvs.size)
            (mean - sd)..(mean + sd)
        } else null
        val days = range.days.coerceAtLeast(7)
        val window = (days - 1 downTo 0).map { back -> today.minusDays(back.toLong()) }
        return HeartUi(
            range = range,
            latestBpm = samples.lastOrNull()?.bpm,
            minBpm = samples.minOfOrNull { it.bpm },
            maxBpm = samples.maxOfOrNull { it.bpm },
            samples = samples.map { s -> s.time.atZone(zone).toLocalTime().let { HeartPoint(it.hour * 60 + it.minute, s.bpm) } },
            restingByDay = window.map { d -> d to summaries.firstOrNull { it.date == d }?.restingHeartRate },
            restingHeartRate = restingNow,
            restingDeltaVsNormal = if (restingNow != null && normal != null) (restingNow - normal).roundToInt() else null,
            hrvMs = todaySummary?.hrvMs,
            hrvNormalRange = hrvRange,
            zoneMinutes = todaySummary?.activeZoneMinutes,
            zoneMinutesTarget = dailyZoneTarget(goals),
        )
    }

    fun sleep(sessions: List<SleepSession>, summaries: List<DaySummary>, goals: Goals): SleepUi {
        val main = sessions.filter { it.isMainSleep }
        val last = main.lastOrNull()
        // Google's own civil wake date when the data layer has it; device time zone otherwise.
        val wakeDay = last?.wakeDate ?: last?.end?.atZone(zone)?.toLocalDate()
        val body = summaries.firstOrNull { it.date == wakeDay }
        val start = last?.start
        val blocks = if (last == null || start == null) emptyList() else last.stages.mapNotNull { st ->
            val stage = when (st.type) {
                SleepStageType.AWAKE -> Stage.Awake
                SleepStageType.REM -> Stage.Rem
                SleepStageType.LIGHT -> Stage.Light
                SleepStageType.DEEP -> Stage.Deep
                SleepStageType.UNKNOWN -> null
            } ?: return@mapNotNull null
            StageBlock(stage, Duration.between(start, st.start).toMinutes().toInt(), Duration.between(start, st.end).toMinutes().toInt())
        }
        val dayLetter = DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())
        val week = main.takeLast(7).let { list ->
            list.mapIndexed { i, s ->
                val st = s.start.atZone(zone)
                // Minutes after 18:00 on the evening the night began.
                val evening = st.toLocalDate().let { if (st.hour < 12) it.minusDays(1) else it }.atTime(18, 0).atZone(zone).toInstant()
                NightBar(
                    label = s.end.atZone(zone).format(dayLetter),
                    startMin = Duration.between(evening, s.start).toMinutes().toInt().coerceIn(0, 1080),
                    endMin = Duration.between(evening, s.end).toMinutes().toInt().coerceIn(0, 1080),
                    isLast = i == list.lastIndex,
                )
            }
        }
        return SleepUi(
            nightOf = wakeDay?.minusDays(1),
            start = last?.start?.local(),
            end = last?.end?.local(),
            minutesAsleep = last?.minutesAsleep?.toInt(),
            minutesByStage = if (last == null) emptyMap() else mapOf(
                Stage.Awake to last.minutesIn(SleepStageType.AWAKE).toInt(),
                Stage.Rem to last.minutesIn(SleepStageType.REM).toInt(),
                Stage.Light to last.minutesIn(SleepStageType.LIGHT).toInt(),
                Stage.Deep to last.minutesIn(SleepStageType.DEEP).toInt(),
            ),
            blocks = blocks,
            totalMinutesInBed = if (last == null) 0 else Duration.between(last.start, last.end).toMinutes().toInt(),
            spo2Percent = body?.spo2Percent,
            respiratoryRate = body?.respiratoryRate,
            skinTempDeltaC = body?.skinTempDeltaC,
            hrvMs = body?.hrvMs,
            week = week,
            sleepGoalMinutes = goals.sleepMinutes,
        )
    }

    fun activity(workouts: List<Workout>, week: List<DaySummary>, goals: Goals): ActivityUi {
        return ActivityUi(
            weekZoneMinutes = week.mapNotNull { it.activeZoneMinutes }.takeIf { it.isNotEmpty() }?.sum(),
            weekZoneTarget = goals.weeklyActiveZoneMinutes,
            weekDistanceMeters = week.mapNotNull { it.distanceMeters }.takeIf { it.isNotEmpty() }?.sum(),
            weekActiveMinutes = week.mapNotNull { it.activeMinutes }.takeIf { it.isNotEmpty() }?.sum(),
            workouts = workouts(workouts),
        )
    }

    fun workouts(list: List<Workout>): List<WorkoutUi> {
        val whenFmt = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
        return list.map { w ->
            WorkoutUi(
                id = w.id,
                tag = tagFor(w.type),
                name = w.name.ifBlank { w.type.lowercase().replaceFirstChar { it.uppercase() } },
                whenLabel = w.start.atZone(zone).format(whenFmt),
                durationMinutes = (w.activeSeconds ?: Duration.between(w.start, w.end).seconds) / 60,
                distanceMeters = w.distanceMeters,
                avgHeartRate = w.avgHeartRate,
                caloriesKcal = w.caloriesKcal,
            )
        }
    }

    private fun tagFor(type: String): String {
        val t = type.uppercase()
        return when {
            "RUN" in t -> "RUN"
            "WALK" in t || "HIK" in t -> "WLK"
            "BIK" in t || "CYCL" in t -> "BIK"
            "SWIM" in t -> "SWM"
            "WEIGHT" in t || "STRENGTH" in t -> "GYM"
            "YOGA" in t -> "YGA"
            else -> t.take(3).ifBlank { "ACT" }
        }
    }

    /** Joins day summaries with the sleep that ended on each day. */
    fun dayStats(summaries: List<DaySummary>, sleeps: List<SleepSession>): List<DayStat> {
        val sleepByWakeDay = sleeps.filter { it.isMainSleep }.associateBy { it.wakeDate ?: it.end.atZone(zone).toLocalDate() }
        val dates = (summaries.map { it.date } + sleepByWakeDay.keys).toSortedSet()
        val byDate = summaries.associateBy { it.date }
        return dates.map { d ->
            val s = byDate[d]
            DayStat(d, s?.steps, sleepByWakeDay[d]?.minutesAsleep?.toInt(), s?.restingHeartRate, s?.activeZoneMinutes, s?.distanceMeters)
        }
    }

    fun sync(s: SyncStatus, isSample: Boolean = false, now: Instant = Instant.now()): SyncUi = SyncUi(
        running = s.state is SyncState.Running,
        progressLabel = (s.state as? SyncState.Running)?.let { r -> r.progress?.let { "${r.label} · ${(it * 100).roundToInt()}%" } ?: r.label },
        error = (s.state as? SyncState.Failed)?.message,
        needsSignIn = (s.state as? SyncState.Failed)?.needsSignIn == true,
        lastSuccessLabel = if (s.lastSuccess == null) "Not synced yet" else "Synced ${Fmt.ago(s.lastSuccess, now)}",
        lastTrackerLabel = if (s.lastTrackerSync == null) "unknown" else Fmt.ago(s.lastTrackerSync, now),
        firstSyncPending = !isSample && s.lastSuccess == null,
        progress = (s.state as? SyncState.Running)?.progress,
        historyLabel = s.history?.let { r -> r.progress?.let { "${r.label} · ${(it * 100).roundToInt()}%" } ?: r.label },
        historyProgress = s.history?.progress,
    )
}
