package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BarChart
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.IconBadge
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.components.fill
import com.fitbittrack.pulse.ui.components.icon
import com.fitbittrack.pulse.ui.components.label
import com.fitbittrack.pulse.ui.components.tone
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.goals.GoalProgress
import com.fitbittrack.pulse.ui.model.DayUi
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Everything about one day: which goals were reached, by how much, and the rest of that day's numbers. */
@Composable
fun DayScreen(
    date: LocalDate,
    ui: DayUi?,
    units: Units,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDate: (LocalDate) -> Unit,
    onMetric: (MetricKind) -> Unit,
    onSleep: () -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = LocalConfiguration.current.locales[0]
    val today = ui?.today ?: LocalDate.now()
    val title = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale))
    }
    DetailPage(
        title, contentPadding, onBack,
        actions = {
            IconButton(onClick = { onDate(date.minusDays(1)) }) { Icon(PulseIcons.Previous, contentDescription = "Day before") }
            IconButton(onClick = { onDate(date.plusDays(1)) }, enabled = date < today) { Icon(PulseIcons.Forward, contentDescription = "Day after") }
        },
    ) {
        if (ui == null || ui.goals.date != date) return@DetailPage
        val goals = ui.goals

        PulseCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigGoalRing(goals, 132)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(headline(ui, date == today), style = MaterialTheme.typography.titleLarge)
                    val missing = goals.core.filter { it.value != null && !it.met }.joinToString { if (it.metric == GoalMetric.Zone) "zone minutes" else it.metric.label.lowercase() }
                    Text(
                        if (missing.isEmpty()) date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)) else (if (date == today) "Still to reach: " else "Missed: ") + missing,
                        style = MaterialTheme.typography.bodyMedium, color = muted,
                    )
                }
            }
        }

        SectionTitle("Goals")
        RowGroup(goals.items.map { g -> { GoalRow(g) { if (g.metric == GoalMetric.Sleep) onSleep() else onMetric(MetricKind.entries.first { it.goal == g.metric }) } } })

        if (ui.hourly.any { (it ?: 0) > 0 }) {
            PulseCard {
                CardHeader("Steps by hour", ui.hourly.withIndex().filter { it.value != null }.maxByOrNull { it.value!! }?.let { "Busiest at ${hourLabel(it.index)}" })
                BarChart(
                    values = ui.hourly.map { it?.toFloat() }, labels = List(24) { if (it % 6 == 0) hourLabel(it) else "" },
                    color = c.move, mutedColor = c.move, description = "Steps in each hour of the day", height = 120.dp,
                )
            }
        }

        val s = ui.summary
        if (s != null) {
            SectionTitle("More from this day")
            TileRow {
                DayTile(MetricKind.Distance, s.distanceMeters, units, Modifier.weight(1f), onMetric)
                DayTile(MetricKind.RestingHeart, s.restingHeartRate?.toDouble(), units, Modifier.weight(1f), onMetric)
            }
            TileRow {
                DayTile(MetricKind.Hrv, s.hrvMs, units, Modifier.weight(1f), onMetric)
                DayTile(MetricKind.Spo2, s.spo2Percent, units, Modifier.weight(1f), onMetric)
            }
        }

        if (ui.workouts.isNotEmpty()) {
            SectionTitle("Workouts")
            RowGroup(ui.workouts.map { w -> { WorkoutRow(w, units, showDay = false) } })
        }
    }
}

@Composable
private fun DayTile(kind: MetricKind, v: Double?, units: Units, modifier: Modifier, onMetric: (MetricKind) -> Unit) {
    val (value, unit) = kind.format(v, units)
    MetricTile(if (kind == MetricKind.Hrv) "HRV" else if (kind == MetricKind.RestingHeart) "Resting heart" else kind.label, PulseTheme.colors.label(kind.tone), value, unit, modifier, { onMetric(kind) }, size = 24)
}

private fun headline(ui: DayUi, isToday: Boolean): String {
    val met = ui.goals.coreMet
    val all = ui.goals.core.size
    return when {
        !ui.goals.hasData -> if (isToday) "Nothing yet" else "No data"
        met == all -> "All goals reached"
        isToday -> "${all - met} to go"
        met == 0 -> "No goals reached"
        else -> "$met of $all reached"
    }
}

@Composable
private fun GoalRow(g: GoalProgress, onClick: () -> Unit) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(if (g.met) PulseIcons.Check else g.metric.icon, c.label(g.metric.tone))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(g.metric.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline().weight(1f))
                Text(g.percent?.let { "$it%" } ?: "No data", style = MaterialTheme.typography.titleSmall, color = if (g.met) c.good else MaterialTheme.colorScheme.onSurface, modifier = Modifier.alignByBaseline())
            }
            ProgressBar(g.fraction ?: 0f, c.fill(g.metric.tone))
            Row {
                Text(
                    listOfNotNull("${goalValue(g.metric, g.value)} of ${goalValue(g.metric, g.target)}", goalUnit(g.metric)).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.alignByBaseline().weight(1f),
                )
                val rest = g.value?.let { g.target - it }
                Text(
                    when { rest == null -> ""; rest <= 0 -> "Reached"; else -> "${goalValue(g.metric, rest)} short" },
                    style = MaterialTheme.typography.bodyMedium, color = if (g.met) c.good else muted, modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

internal fun hourLabel(h: Int): String = Fmt.clock(java.time.LocalTime.of(h, 0)).replace(":00", "")
