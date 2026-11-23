package com.fitbittrack.pulse.ui.goals

import com.fitbittrack.pulse.domain.DatedGoals
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.SleepSession
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/** Every daily goal. [GoalCalendar.CORE] are the ones the calendar counts. The order is the order around the ring. */
enum class GoalMetric(val label: String) {
    Steps("Steps"),
    Sleep("Sleep"),
    Calories("Calories"),
    Distance("Distance"),
    Zone("Zone minutes"),
    Floors("Floors"),
}

/** How far one goal got on one day. [value] null = the band recorded nothing. */
data class GoalProgress(val metric: GoalMetric, val value: Double?, val target: Double) {
    val fraction: Float? get() = value?.let { if (target <= 0) 1f else (it / target).toFloat() }
    val met: Boolean get() = value != null && value >= target
    val percent: Int? get() = fraction?.let { (it * 100).toInt() }
}

/** Every goal for one day, judged by the targets that applied on that day. */
data class DayGoals(val date: LocalDate, val items: List<GoalProgress>) {
    operator fun get(metric: GoalMetric): GoalProgress = items.first { it.metric == metric }
    val core: List<GoalProgress> get() = items.filter { it.metric in GoalCalendar.CORE }
    /** The rings on Today. */
    val todayGoals: List<GoalProgress> get() = items.filter { it.metric in GoalCalendar.TODAY }
    val coreMet: Int get() = core.count { it.met }
    val hasData: Boolean get() = items.any { it.value != null }

    /** [metric] null = "All goals": every core goal reached. */
    fun met(metric: GoalMetric?): Boolean = if (metric == null) core.all { it.met } else get(metric).met
}

/** One month of the calendar, ready to draw. */
data class MonthUi(
    val month: YearMonth,
    val metric: GoalMetric?,
    /** Empty cells before day 1, for the first day of the week in use. */
    val leadingBlanks: Int,
    val weekdays: List<DayOfWeek>,
    val days: List<DayGoals>,
    val today: LocalDate,
    /** Days in this month with data, and how many of them reached the goal. */
    val daysWithData: Int,
    val daysMet: Int,
    val currentStreak: Int,
    val bestStreak: Int,
    /** Today itself, whichever month is open, and the streak of complete days. */
    val todayGoals: DayGoals?,
    val allGoalsStreak: Int,
    /** Average of the metric over days with data (null for "All goals"). */
    val average: Double?,
    val target: Double?,
)

/** Goal rules. Plain functions: easy to test. */
object GoalCalendar {

    /** The goals the calendar tracks. A day is complete when all of them are reached. */
    val CORE = setOf(GoalMetric.Steps, GoalMetric.Calories)

    /** The goals shown as rings on Today. */
    val TODAY = setOf(GoalMetric.Steps, GoalMetric.Sleep, GoalMetric.Calories, GoalMetric.Zone)

    /** The targets that applied on [date]: the newest change on or before it, else the oldest known. */
    fun goalsOn(history: List<DatedGoals>, date: LocalDate): Goals {
        val sorted = history.sortedBy { it.from }
        return (sorted.lastOrNull { it.from <= date } ?: sorted.firstOrNull())?.goals ?: Goals()
    }

    fun day(date: LocalDate, summary: DaySummary?, sleepMinutes: Int?, goals: Goals): DayGoals = DayGoals(
        date,
        listOf(
            GoalProgress(GoalMetric.Steps, summary?.steps?.toDouble(), goals.dailySteps.toDouble()),
            GoalProgress(GoalMetric.Sleep, sleepMinutes?.toDouble(), goals.sleepMinutes.toDouble()),
            GoalProgress(GoalMetric.Calories, summary?.caloriesKcal, goals.dailyCalories.toDouble()),
            GoalProgress(GoalMetric.Distance, summary?.distanceMeters, goals.dailyDistanceMeters.toDouble()),
            GoalProgress(GoalMetric.Zone, summary?.activeZoneMinutes?.toDouble(), (goals.weeklyActiveZoneMinutes / 7.0).roundToInt().coerceAtLeast(1).toDouble()),
            GoalProgress(GoalMetric.Floors, summary?.floors?.toDouble(), goals.dailyFloors.toDouble()),
        ),
    )

    /** One entry per date from [from] to [to], oldest first, also for days without data. */
    fun days(
        from: LocalDate, to: LocalDate,
        summaries: List<DaySummary>, sleeps: List<SleepSession>, history: List<DatedGoals>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayGoals> {
        val byDate = summaries.associateBy { it.date }
        val sleepByWake = sleeps.filter { it.isMainSleep }
            .groupBy { it.wakeDate ?: it.end.atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.maxOf { it.minutesAsleep }.toInt() }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { d -> day(d, byDate[d], sleepByWake[d], goalsOn(history, d)) }
            .toList()
    }

    /** Days in a row up to today. Today only counts once reached: an unfinished day doesn't break the streak. */
    fun currentStreak(days: List<DayGoals>, metric: GoalMetric?, today: LocalDate): Int {
        val past = days.filter { it.date <= today }.sortedByDescending { it.date }
        val counted = if (past.firstOrNull()?.let { it.date == today && !it.met(metric) } == true) past.drop(1) else past
        return counted.takeWhile { it.met(metric) }.size
    }

    fun bestStreak(days: List<DayGoals>, metric: GoalMetric?): Int {
        var best = 0
        var run = 0
        days.sortedBy { it.date }.forEach { run = if (it.met(metric)) run + 1 else 0; best = maxOf(best, run) }
        return best
    }

    /** [allDays] should reach back at least a year for the streaks; it may hold more than this month. */
    fun month(month: YearMonth, metric: GoalMetric?, allDays: List<DayGoals>, today: LocalDate, firstDay: DayOfWeek): MonthUi {
        val byDate = allDays.associateBy { it.date }
        val days = (1..month.lengthOfMonth()).map { n ->
            val d = month.atDay(n)
            byDate[d] ?: day(d, null, null, Goals())
        }
        val withData = days.filter { it.date <= today && (if (metric == null) it.core.any { g -> g.value != null } else it[metric].value != null) }
        return MonthUi(
            month = month,
            metric = metric,
            leadingBlanks = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7,
            weekdays = (0L..6L).map { firstDay.plus(it) },
            days = days,
            today = today,
            daysWithData = withData.size,
            daysMet = withData.count { it.met(metric) },
            currentStreak = currentStreak(allDays, metric, today),
            bestStreak = bestStreak(allDays.filter { it.date <= today }, metric),
            todayGoals = byDate[today],
            allGoalsStreak = currentStreak(allDays, null, today),
            average = metric?.let { m -> withData.mapNotNull { it[m].value }.takeIf { it.isNotEmpty() }?.average() },
            target = metric?.let { m -> (days.lastOrNull { it.date <= today } ?: days.last())[m].target },
        )
    }
}
