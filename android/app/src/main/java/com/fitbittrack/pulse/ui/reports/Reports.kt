package com.fitbittrack.pulse.ui.reports

import com.fitbittrack.pulse.ui.model.DayStat
import java.time.LocalDate
import kotlin.math.roundToInt

/** Weekly / monthly / yearly report, computed from daily stats. Pure functions: easy to test. */
data class Report(
    val days: List<DayStat>,
    val avgSteps: Long?,
    val daysOnStepGoal: Int,
    val daysWithSteps: Int,
    val avgSleepMinutes: Int?,
    val restingStart: Int?,
    val restingEnd: Int?,
    val totalZoneMinutes: Long?,
    val totalDistanceMeters: Double?,
    val bestStepsDay: DayStat?,
    val longestSleepDay: DayStat?,
)

object Reports {

    fun build(days: List<DayStat>, stepGoal: Int): Report {
        val withSteps = days.filter { it.steps != null }
        val withSleep = days.filter { it.sleepMinutes != null }
        val withRhr = days.filter { it.restingHeartRate != null }
        return Report(
            days = days,
            avgSteps = withSteps.takeIf { it.isNotEmpty() }?.map { it.steps!! }?.average()?.toLong(),
            daysOnStepGoal = withSteps.count { it.steps!! >= stepGoal },
            daysWithSteps = withSteps.size,
            avgSleepMinutes = withSleep.takeIf { it.isNotEmpty() }?.map { it.sleepMinutes!! }?.average()?.roundToInt(),
            restingStart = withRhr.firstOrNull()?.restingHeartRate,
            restingEnd = withRhr.lastOrNull()?.restingHeartRate,
            totalZoneMinutes = days.mapNotNull { it.zoneMinutes }.takeIf { it.isNotEmpty() }?.sum(),
            totalDistanceMeters = days.mapNotNull { it.distanceMeters }.takeIf { it.isNotEmpty() }?.sum(),
            bestStepsDay = withSteps.maxByOrNull { it.steps!! },
            longestSleepDay = withSleep.maxByOrNull { it.sleepMinutes!! },
        )
    }

    /**
     * For the year view, 365 bars are too many: group into calendar months and average them.
     * Returns one DayStat per month, dated on the 1st.
     */
    fun monthly(days: List<DayStat>): List<DayStat> =
        days.groupBy { it.date.withDayOfMonth(1) }.toSortedMap().map { (month, list) ->
            DayStat(
                date = month,
                steps = list.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                sleepMinutes = list.mapNotNull { it.sleepMinutes }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                restingHeartRate = list.mapNotNull { it.restingHeartRate }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                zoneMinutes = list.mapNotNull { it.zoneMinutes }.takeIf { it.isNotEmpty() }?.sum(),
                distanceMeters = list.mapNotNull { it.distanceMeters }.takeIf { it.isNotEmpty() }?.sum(),
            )
        }

    /** Fills missing dates with empty stats so charts show gaps, not squashed bars. */
    fun fillGaps(days: List<DayStat>, from: LocalDate, to: LocalDate): List<DayStat> {
        val byDate = days.associateBy { it.date }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { byDate[it] ?: DayStat(it, null, null, null, null, null) }
            .toList()
    }
}
