package com.fitbittrack.pulse.ui.model

import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/*
 * What each screen draws. Screens never see the data layer directly:
 * view models map domain objects (com.fitbittrack.pulse.domain) into these.
 * Every number is nullable: null means "no data", which the UI shows as a dash, never as 0.
 */

enum class Units { Metric, Imperial }

/** Which cards appear on Today, in order. Saved as a UI preference. */
enum class TodayCard(val label: String, val about: String) {
    Ring("Steps and goals", "Steps, goals and heart rate"),
    Train("Training", "Your next workout"),
    Band("Fitbit battery", "Percent at the last sync"),
    Air("Air outside", "Air quality near you"),
    Week("Last 7 days", "Your calendar goals"),
    Tip("Tip of the day", "One useful finding"),
    Body("Body numbers", "Heart, oxygen, distance and more"),
}

/** What the user told Pulse about themselves. */
data class PersonalPrefs(
    /** The goals a calendar day needs to be green. At least one. */
    val calendarGoals: Set<GoalMetric> = GoalCalendar.CORE,
    val age: Int? = null,
    /** Chosen by the user. Null = worked out from age. */
    val topHeartRate: Int? = null,
) {
    /** 220 minus age, else 190 (the number Pulse used before). */
    val autoTopHeartRate: Int get() = age?.let { (220 - it).coerceIn(120, 220) } ?: 190
    val maxHeartRate: Int get() = topHeartRate ?: autoTopHeartRate
}

data class UiPrefs(
    val units: Units = Units.Metric,
    val todayCards: List<Pair<TodayCard, Boolean>> = TodayCard.entries.map { it to true },
    /** Extra goals listed on Today (besides sleep and calories). Kept non-empty: Steps is always in it. */
    val ringGoals: Set<GoalMetric> = GoalCalendar.CIRCLE,
)

/** Share of the night in each stage, 0..1. */
data class StageShares(val deep: Float, val light: Float, val rem: Float, val awake: Float)

data class Insight(val title: String, val body: String, val positive: Boolean)

data class TodayUi(
    val date: LocalDate,
    val steps: Long?,
    val stepGoal: Int,
    val distanceMeters: Double?,
    val caloriesKcal: Double?,
    val zoneMinutes: Long?,
    val zoneMinutesDailyTarget: Int,
    val floors: Long?,
    val floorsGoal: Int,
    val restingHeartRate: Int?,
    val restingHeartRateLast7: List<Int?>,
    val hrvMs: Double?,
    val spo2Percent: Double?,
    val respiratoryRate: Double? = null,
    val sleepMinutes: Int?,
    val sleepShares: StageShares?,
    /** 24 values, null = no data for that hour yet. Empty list = not available. */
    val stepsPerHour: List<Long?>,
    val sleepStart: LocalTime?,
    val sleepEnd: LocalTime?,
    val now: LocalTime,
    val insight: Insight?,
    /** Newest heart rate of today, and when it was measured. */
    val latestBpm: Int? = null,
    val latestBpmAt: Instant? = null,
)

/** The quick numbers on Home. All empty on sample data. */
data class HomeLive(
    /** Band steps plus the phone's own steps since. Null when there is nothing to add. */
    val steps: Long? = null,
    /** How far the band's numbers reach. */
    val bandUpTo: Instant? = null,
    /** Straight from the band, right now. */
    val bpm: Int? = null,
)

enum class Range(val label: String, val days: Int) { Day("Day", 1), Week("Week", 7), Month("Month", 30) }

data class HeartPoint(val minuteOfDay: Int, val bpm: Int)

data class HeartUi(
    val range: Range,
    val latestBpm: Int?,
    val minBpm: Int?,
    val maxBpm: Int?,
    /** Day view: intraday samples. */
    val samples: List<HeartPoint>,
    /** Week / month view: one resting value per day, oldest first. */
    val restingByDay: List<Pair<LocalDate, Int?>>,
    val restingHeartRate: Int?,
    val restingDeltaVsNormal: Int?,
    val hrvMs: Double?,
    val hrvNormalRange: ClosedFloatingPointRange<Double>?,
    val zoneMinutes: Long?,
    val zoneMinutesTarget: Int,
)

enum class Stage { Awake, Rem, Light, Deep }

data class StageBlock(val stage: Stage, val startMinute: Int, val endMinute: Int)

data class SleepUi(
    val nightOf: LocalDate?,
    val start: LocalTime?,
    val end: LocalTime?,
    val minutesAsleep: Int?,
    val minutesByStage: Map<Stage, Int>,
    /** Minutes since [start]. */
    val blocks: List<StageBlock>,
    val totalMinutesInBed: Int,
    val spo2Percent: Double?,
    val respiratoryRate: Double?,
    val skinTempDeltaC: Double?,
    val hrvMs: Double?,
    /** Last 7 nights, oldest first: bedtime and wake time as minutes after 18:00 (so the night is continuous). */
    val week: List<NightBar>,
    val sleepGoalMinutes: Int,
)

data class NightBar(val label: String, val startMin: Int?, val endMin: Int?, val isLast: Boolean)

data class WorkoutUi(
    val id: String,
    val tag: String,
    val name: String,
    val whenLabel: String,
    val durationMinutes: Long,
    val distanceMeters: Double?,
    val avgHeartRate: Int?,
    val caloriesKcal: Double?,
    val startMs: Long = 0,
    val endMs: Long = 0,
)

data class ActivityUi(
    val weekZoneMinutes: Long?,
    val weekZoneTarget: Int,
    val weekDistanceMeters: Double?,
    val weekActiveMinutes: Long?,
    val workouts: List<WorkoutUi>,
)

/** The Health tab: the newest value of everything. */
data class HubUi(
    val today: LocalDate,
    val latest: Map<MetricKind, Pair<LocalDate, Double>>,
    val latestBpm: Int?,
    val sleepMinutes: Int?,
    val workouts7: Int,
)

enum class ReportRange(val label: String, val days: Int) { Week("Week", 7), Month("Month", 30), Year("Year", 365) }

/** One day as the reports need it. */
data class DayStat(
    val date: LocalDate,
    val steps: Long?,
    val sleepMinutes: Int?,
    val restingHeartRate: Int?,
    val zoneMinutes: Long?,
    val distanceMeters: Double?,
)

data class SyncUi(
    val running: Boolean,
    val progressLabel: String?,
    val error: String?,
    val needsSignIn: Boolean,
    val lastSuccessLabel: String,
    val lastTrackerLabel: String,
    val lastTrackerAt: Instant? = null,
    /** Band battery in percent, null when Google has not said. */
    val batteryPercent: Int? = null,
    val batteryWord: String? = null,
    /** Real account connected but nothing downloaded yet: show the first-sync card. */
    val firstSyncPending: Boolean,
    /** Set while older history downloads in the background. Doesn't block refreshing. */
    val historyLabel: String? = null,
    val historyProgress: Float? = null,
    /** 0..1 during the history download, null when unknown. */
    val progress: Float? = null,
)
