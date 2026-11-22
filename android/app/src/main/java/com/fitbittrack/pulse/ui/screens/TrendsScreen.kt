package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BarChart
import com.fitbittrack.pulse.ui.components.LineChart
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.SampleBanner
import com.fitbittrack.pulse.ui.components.ScreenHeader
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.DayStat
import com.fitbittrack.pulse.ui.model.ReportRange
import com.fitbittrack.pulse.ui.reports.Report
import com.fitbittrack.pulse.ui.reports.Reports
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun TrendsScreen(
    range: ReportRange,
    days: List<DayStat>,
    stepGoal: Int,
    sleepGoalMinutes: Int,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onRangeChange: (ReportRange) -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val report = Reports.build(days, stepGoal)
    // Year view: one bar per month (averages). Week/month: one bar per day.
    val bars = if (range == ReportRange.Year) Reports.monthly(days) else days
    val labels = bars.map { labelFor(it, range) }
    val title = headline(report, stepGoal)
    val period = if (days.isEmpty()) "" else "${days.first().date.format(shortDate)} – ${days.last().date.format(shortDate)}"

    PulsePage(contentPadding) {
        ScreenHeader("Report · $period", title)
        if (isSample) SampleBanner()
        Segmented(ReportRange.entries, range, { it.label }, onRangeChange)

        PulseCard {
            Row {
                Text("Steps", style = MaterialTheme.typography.titleMedium, color = c.moveLabel, modifier = Modifier.weight(1f))
                Text("avg ${Fmt.count(report.avgSteps)} · ${report.daysOnStepGoal} of ${report.daysWithSteps} days on goal", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            BarChart(
                values = bars.map { it.steps?.toFloat() },
                labels = labels,
                color = c.move, mutedColor = c.moveMuted,
                goal = stepGoal.toFloat(), goalLabel = "GOAL ${Fmt.count(stepGoal)}",
                description = "Steps per ${if (range == ReportRange.Year) "month (average)" else "day"}, average ${Fmt.count(report.avgSteps)}",
            )
        }

        PulseCard {
            Row {
                Text("Sleep", style = MaterialTheme.typography.titleMedium, color = c.sleepLabel, modifier = Modifier.weight(1f))
                Text("avg ${Fmt.minutes(report.avgSleepMinutes)}", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            BarChart(
                values = bars.map { it.sleepMinutes?.toFloat() },
                labels = labels,
                color = c.sleep, mutedColor = c.sleep.copy(alpha = 0.45f),
                goal = sleepGoalMinutes.toFloat(), goalLabel = "GOAL ${Fmt.minutes(sleepGoalMinutes)}",
                description = "Hours slept, average ${Fmt.minutes(report.avgSleepMinutes)}",
                height = 120.dp,
            )
        }

        PulseCard {
            val delta = if (report.restingStart != null && report.restingEnd != null) report.restingEnd - report.restingStart else null
            Row {
                Text("Resting heart rate", style = MaterialTheme.typography.titleMedium, color = c.heartLabel, modifier = Modifier.weight(1f))
                delta?.let {
                    Text(
                        if (it == 0) "no change" else (if (it > 0) "+$it" else "−${-it}") + " bpm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it <= 0) c.good else muted,
                    )
                }
            }
            LineChart(
                points = bars.mapIndexed { i, d -> i / (bars.size - 1f).coerceAtLeast(1f) to d.restingHeartRate?.toFloat() },
                color = c.heart,
                description = "Resting heart rate from ${Fmt.count(report.restingStart)} to ${Fmt.count(report.restingEnd)} bpm",
                xLabels = if (bars.isEmpty()) emptyList() else listOf(0f to bars.first().date.format(shortDate), 1f to bars.last().date.format(shortDate)),
                height = 110.dp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Best day", c.moveLabel, report.bestStepsDay?.date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: Fmt.NONE, null, Modifier.weight(1f)) {
                Text("${Fmt.count(report.bestStepsDay?.steps)} steps", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            MetricTile("Longest sleep", c.sleepLabel, report.longestSleepDay?.date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: Fmt.NONE, null, Modifier.weight(1f)) {
                Text(Fmt.minutes(report.longestSleepDay?.sleepMinutes), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Zone minutes", c.moveLabel, Fmt.count(report.totalZoneMinutes), "total", Modifier.weight(1f))
            MetricTile("Distance", c.moveLabel, "%.1f".format((report.totalDistanceMeters ?: 0.0) / 1000), "km", Modifier.weight(1f))
        }
    }
}

private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

private fun labelFor(d: DayStat, range: ReportRange): String = when (range) {
    ReportRange.Week -> d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    ReportRange.Month -> if (d.date.dayOfMonth % 7 == 1) d.date.dayOfMonth.toString() else ""
    ReportRange.Year -> d.date.month.getDisplayName(TextStyle.NARROW, Locale.getDefault())
}

private fun headline(r: Report, goal: Int): String = when {
    r.daysWithSteps == 0 -> "Not enough data yet"
    r.daysOnStepGoal * 2 >= r.daysWithSteps -> "A strong stretch"
    (r.avgSteps ?: 0) >= goal * 0.8 -> "Close to your goals"
    else -> "Room to move more"
}
