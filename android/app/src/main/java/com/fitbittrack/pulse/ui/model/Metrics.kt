package com.fitbittrack.pulse.ui.model

import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.DayGoals
import com.fitbittrack.pulse.ui.goals.GoalMetric
import java.time.LocalDate

/** One colour family per kind of data. */
enum class Tone { Move, Energy, Active, Heart, Sleep, Body }

/** Every number that has its own detail page. */
enum class MetricKind(
    val label: String,
    val tone: Tone,
    val goal: GoalMetric?,
    /** Bars for things you add up over a day, a line for things you measure. */
    val bars: Boolean,
    /** Does adding days together mean something? */
    val summable: Boolean,
    val about: String,
    val read: (DaySummary) -> Double?,
) {
    Steps("Steps", Tone.Move, GoalMetric.Steps, true, true, "Counted by your band all day.", { it.steps?.toDouble() }),
    Calories("Calories", Tone.Energy, GoalMetric.Calories, true, true, "All energy used in a day: resting plus activity.", { it.caloriesKcal }),
    Zone("Zone minutes", Tone.Active, GoalMetric.Zone, true, true, "Minutes with a raised heart rate. Harder zones count double.", { it.activeZoneMinutes?.toDouble() }),
    Distance("Distance", Tone.Move, null, true, true, "Worked out from your steps and workouts.", { it.distanceMeters }),
    Floors("Floors", Tone.Body, GoalMetric.Floors, true, true, "One floor is about 3 metres up.", { it.floors?.toDouble() }),
    RestingHeart("Resting heart rate", Tone.Heart, null, false, false, "Your heart rate when fully at rest. Lower is usually better.", { it.restingHeartRate?.toDouble() }),
    Hrv("Heart rate variability", Tone.Heart, null, false, false, "Tiny changes between heartbeats, measured in your sleep. Compare with your own normal.", { it.hrvMs }),
    Spo2("Blood oxygen", Tone.Body, null, false, false, "Average oxygen level during sleep. Not for medical use.", { it.spo2Percent }),
    Breathing("Breathing rate", Tone.Body, null, false, false, "Breaths per minute during sleep.", { it.respiratoryRate }),
    SkinTemp("Skin temperature", Tone.Body, null, false, false, "Difference from your own usual night temperature.", { it.skinTempDeltaC }),
    Vo2Max("Cardio fitness", Tone.Body, null, false, false, "An estimate of VO2 max: how much oxygen your body can use.", { it.vo2Max }),
    ;

    /** Value and unit, for example "8,420" to "steps". */
    fun format(v: Double?, units: Units): Pair<String, String> = when (this) {
        Steps -> Fmt.whole(v) to "steps"
        Calories -> Fmt.whole(v) to "kcal"
        Zone -> Fmt.whole(v) to "min"
        Distance -> Fmt.distance(v, units)
        Floors -> Fmt.whole(v) to "floors"
        RestingHeart -> Fmt.whole(v) to "bpm"
        Hrv -> Fmt.whole(v) to "ms"
        Spo2 -> Fmt.whole(v) to "%"
        Breathing -> Fmt.oneDecimal(v) to "/min"
        SkinTemp -> Fmt.signedTemp(v) to "C"
        Vo2Max -> Fmt.oneDecimal(v) to "ml/kg/min"
    }

    /** "8,420 steps", or a dash. */
    fun text(v: Double?, units: Units): String = format(v, units).let { (a, u) -> if (v == null) a else if (u == "%" || u == "C") "$a$u" else "$a $u" }
}

/** A metric's detail page. */
data class MetricUi(
    val kind: MetricKind,
    val range: ReportRange,
    val today: LocalDate,
    /** One value per day, oldest first, gaps included. */
    val days: List<Pair<LocalDate, Double?>>,
    /** What the chart draws: days, or one average per month in the year view. */
    val bars: List<Pair<LocalDate, Double?>>,
    val latest: Pair<LocalDate, Double>?,
    val average: Double?,
    val total: Double?,
    val best: Pair<LocalDate, Double>?,
    val lowest: Pair<LocalDate, Double>?,
    val target: Double?,
    val daysMet: Int,
    val daysWithData: Int,
    /** Steps page only: today by hour. */
    val hourly: List<Long?> = emptyList(),
)

/** One day in full, opened from the calendar. */
data class DayUi(
    val goals: DayGoals,
    val summary: DaySummary?,
    val sleepMinutes: Int?,
    val hourly: List<Long?>,
    val workouts: List<WorkoutUi>,
    val today: LocalDate,
)

object MetricMapper {

    /** [goalFor] gives the target that applied on a date; null for metrics without a goal. */
    fun metric(
        kind: MetricKind, range: ReportRange, today: LocalDate,
        summaries: List<DaySummary>, hourly: List<Long?>, goalFor: (LocalDate) -> Double?,
    ): MetricUi {
        val byDate = summaries.associateBy { it.date }
        val days = (range.days - 1L downTo 0L).map { back -> today.minusDays(back).let { d -> d to byDate[d]?.let(kind.read) } }
        val data = days.mapNotNull { (d, v) -> v?.let { d to it } }
        // Today isn't finished: it would pull averages down for things that add up over the day.
        val finished = if (kind.summable) data.filter { it.first != today }.ifEmpty { data } else data
        val bars = if (range == ReportRange.Year) {
            days.groupBy { it.first.withDayOfMonth(1) }.toSortedMap()
                .map { (m, list) -> m to list.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.average() }
        } else days
        return MetricUi(
            kind = kind, range = range, today = today, days = days, bars = bars,
            latest = data.lastOrNull(),
            average = finished.takeIf { it.isNotEmpty() }?.map { it.second }?.average(),
            total = if (kind.summable) data.takeIf { it.isNotEmpty() }?.sumOf { it.second } else null,
            best = data.maxByOrNull { it.second },
            lowest = finished.minByOrNull { it.second },
            target = goalFor(today),
            daysMet = data.count { (d, v) -> goalFor(d)?.let { v >= it } == true },
            daysWithData = data.size,
            hourly = hourly,
        )
    }
}
