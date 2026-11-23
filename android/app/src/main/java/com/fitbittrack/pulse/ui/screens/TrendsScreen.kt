package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BarChart
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.LineChart
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.SampleBanner
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.components.TabHeader
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.DayStat
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.ReportRange
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.reports.Report
import com.fitbittrack.pulse.ui.reports.Reports
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TrendsScreen(
    range: ReportRange,
    days: List<DayStat>,
    stepGoal: Int,
    sleepGoalMinutes: Int,
    units: Units,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onRangeChange: (ReportRange) -> Unit,
    onConnect: () -> Unit,
    onMetric: (MetricKind) -> Unit,
    onSleep: () -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = LocalConfiguration.current.locales[0]
    val report = Reports.build(days, stepGoal)
    // Year view: one bar per month (averages). Week/month: one bar per day.
    val bars = if (range == ReportRange.Year) Reports.monthly(days) else days
    val labels = bars.map { axisLabel(it.date, range, locale) }
    val period = if (days.isEmpty()) null else "${days.first().date.format(short(locale))} – ${days.last().date.format(short(locale))}"
    val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", locale)

    PulsePage(contentPadding) {
        TabHeader("Trends", period)
        if (isSample) SampleBanner(onConnect)
        Segmented(ReportRange.entries, range, { it.label }, onRangeChange)

        PulseCard {
            Text(headline(report, stepGoal), style = MaterialTheme.typography.titleLarge)
            Text(
                if (report.daysWithSteps == 0) "Come back after a few synced days."
                else "Step goal reached on ${report.daysOnStepGoal} of ${report.daysWithSteps} days. Average ${Fmt.count(report.avgSteps)} steps and ${Fmt.minutes(report.avgSleepMinutes)} of sleep.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
        }

        PulseCard(onClick = { onMetric(MetricKind.Steps) }) {
            CardHeader("Steps", "Goal ${Fmt.count(stepGoal)}")
            BarChart(
                values = bars.map { it.steps?.toFloat() }, labels = labels,
                color = c.move, mutedColor = c.move.copy(alpha = 0.42f), goal = stepGoal.toFloat(),
                description = "Steps per ${if (range == ReportRange.Year) "month (average)" else "day"}, average ${Fmt.count(report.avgSteps)}",
            )
        }
        PulseCard(onClick = onSleep) {
            CardHeader("Sleep", "Goal ${Fmt.minutes(sleepGoalMinutes)}")
            BarChart(
                values = bars.map { it.sleepMinutes?.toFloat() }, labels = labels,
                color = c.sleep, mutedColor = c.sleep.copy(alpha = 0.42f), goal = sleepGoalMinutes.toFloat(),
                description = "Sleep, average ${Fmt.minutes(report.avgSleepMinutes)}",
            )
        }
        PulseCard(onClick = { onMetric(MetricKind.RestingHeart) }) {
            val delta = if (report.restingStart != null && report.restingEnd != null) report.restingEnd - report.restingStart else null
            CardHeader(
                "Resting heart rate",
                delta?.let { if (it == 0) "No change" else (if (it > 0) "+$it" else "−${-it}") + " bpm" },
                if (delta != null && delta <= 0) c.good else muted,
            )
            LineChart(
                points = bars.mapIndexed { i, d -> i / (bars.size - 1f).coerceAtLeast(1f) to d.restingHeartRate?.toFloat() },
                color = c.heart,
                description = "Resting heart rate from ${Fmt.count(report.restingStart)} to ${Fmt.count(report.restingEnd)} bpm",
                xLabels = if (bars.size < 2) emptyList() else listOf(0f to bars.first().date.format(short(locale)), 1f to bars.last().date.format(short(locale))),
                height = 140.dp,
            )
        }

        TileRow {
            MetricTile("Most steps", c.moveLabel, Fmt.count(report.bestStepsDay?.steps), null, Modifier.weight(1f), report.bestStepsDay?.let { { onDay(it.date) } }) {
                Text(report.bestStepsDay?.date?.format(dayFmt) ?: "", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
            }
            MetricTile("Longest sleep", c.sleepLabel, Fmt.minutes(report.longestSleepDay?.sleepMinutes), null, Modifier.weight(1f), report.longestSleepDay?.let { { onDay(it.date) } }) {
                Text(report.longestSleepDay?.date?.format(dayFmt) ?: "", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
            }
        }
        TileRow {
            MetricTile("Zone minutes", c.activeLabel, Fmt.count(report.totalZoneMinutes), "total", Modifier.weight(1f), { onMetric(MetricKind.Zone) })
            val (dist, unit) = Fmt.distance(report.totalDistanceMeters, units)
            MetricTile("Distance", c.moveLabel, dist, unit, Modifier.weight(1f), { onMetric(MetricKind.Distance) })
        }
    }
}

private fun headline(r: Report, goal: Int): String = when {
    r.daysWithSteps == 0 -> "Not enough data yet"
    r.daysOnStepGoal * 2 >= r.daysWithSteps -> "A strong stretch"
    (r.avgSteps ?: 0) >= goal * 0.8 -> "Close to your goals"
    else -> "Room to move more"
}
