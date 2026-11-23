package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.GoalRing
import com.fitbittrack.pulse.ui.components.FitText
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.ProgressRing
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.SampleBanner
import com.fitbittrack.pulse.ui.components.TabHeader
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.components.fill
import com.fitbittrack.pulse.ui.components.icon
import com.fitbittrack.pulse.ui.components.label
import com.fitbittrack.pulse.ui.components.tone
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.DayGoals
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.goals.MonthUi
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** "8,420", "6h 48m": how a goal's numbers are written everywhere. */
fun goalValue(metric: GoalMetric, v: Double?, units: Units): String = when (metric) {
    GoalMetric.Distance -> Fmt.distance(v, units).first
    GoalMetric.Sleep -> Fmt.minutes(v?.toInt())
    else -> Fmt.whole(v)
}

fun goalUnit(metric: GoalMetric, units: Units): String? = when (metric) {
    GoalMetric.Distance -> Fmt.distance(null, units).second
    GoalMetric.Steps -> "steps"
    GoalMetric.Calories -> "kcal"
    GoalMetric.Zone -> "min"
    GoalMetric.Floors -> "floors"
    GoalMetric.Sleep -> null
}

/** "8,420 steps", "6.1 km". */
fun goalText(metric: GoalMetric, v: Double?, units: Units): String = listOfNotNull(goalValue(metric, v, units), goalUnit(metric, units)).joinToString(" ")

@Composable
fun CalendarScreen(
    ui: MonthUi,
    units: Units,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
    onMetric: (GoalMetric?) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onDay: (LocalDate) -> Unit,
    onEditGoals: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val thisMonth = YearMonth.from(ui.today)
    PulsePage(contentPadding) {
        TabHeader("Calendar", "How often you reach your goals") {
            TextButton(onClick = onEditGoals) { Text("Edit goals") }
        }
        if (isSample) SampleBanner(onConnect)

        ui.todayGoals?.let { TodayTargets(it, ui.allGoalsStreak, units) { onDay(ui.today) } }

        // --- Which goal ---
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalChip("All goals", null, ui.metric == null) { onMetric(null) }
            GoalMetric.entries.filter { it in GoalCalendar.CORE }.forEach { m -> GoalChip(m.label, m, ui.metric == m) { onMetric(m) } }
        }

        // --- Month ---
        val latest by rememberUpdatedState(ui)
        PulseCard(
            Modifier.pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        val step = 64.dp.toPx()
                        val shown = latest.month
                        if (dx > step) onMonth(shown.minusMonths(1))
                        else if (dx < -step && shown < YearMonth.from(latest.today)) onMonth(shown.plusMonths(1))
                    },
                ) { _, d -> dx += d }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (ui.month != thisMonth) TextButton(onClick = { onMonth(thisMonth) }) { Text("Today") }
                IconButton(onClick = { onMonth(ui.month.minusMonths(1)) }) { Icon(PulseIcons.Previous, contentDescription = "Previous month") }
                IconButton(onClick = { onMonth(ui.month.plusMonths(1)) }, enabled = ui.month < thisMonth) { Icon(PulseIcons.Forward, contentDescription = "Next month") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    ui.weekdays.forEach {
                        Text(
                            it.getDisplayName(TextStyle.NARROW_STANDALONE, locale), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                        )
                    }
                }
                (0 until (ui.leadingBlanks + ui.days.size + 6) / 7).forEach { r ->
                    Row(Modifier.fillMaxWidth()) {
                        (0 until 7).forEach { col ->
                            val day = ui.days.getOrNull(r * 7 + col - ui.leadingBlanks)
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                                if (day != null) DayCell(day, ui.metric, ui.today, units, onDay)
                            }
                        }
                    }
                }
            }
            Legend(ui.metric)
        }

        // --- Summary ---
        val name = ui.metric?.label ?: "All goals"
        PulseCard {
            CardHeader(name, if (ui.daysWithData == 0) null else "${ui.daysMet} of ${ui.daysWithData} days")
            val c = PulseTheme.colors
            ProgressBar(if (ui.daysWithData == 0) 0f else ui.daysMet.toFloat() / ui.daysWithData, ui.metric?.let { c.fill(it.tone) } ?: c.good, height = 10)
            Text(summary(ui), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TileRow {
            MetricTile("Current streak", MaterialTheme.colorScheme.onSurfaceVariant, "${ui.currentStreak}", days(ui.currentStreak), Modifier.weight(1f))
            MetricTile("Best in a year", MaterialTheme.colorScheme.onSurfaceVariant, "${ui.bestStreak}", days(ui.bestStreak), Modifier.weight(1f))
        }
        if (ui.metric != null) {
            TileRow {
                MetricTile("Month average", PulseTheme.colors.label(ui.metric.tone), goalValue(ui.metric, ui.average, units), goalUnit(ui.metric, units), Modifier.weight(1f))
                MetricTile("Daily target", PulseTheme.colors.label(ui.metric.tone), goalValue(ui.metric, ui.target, units), goalUnit(ui.metric, units), Modifier.weight(1f))
            }
        }
    }
}

/** What is left to do today, and the streak it keeps alive. */
@Composable
private fun TodayTargets(day: DayGoals, streak: Int, units: Units, onClick: () -> Unit) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val done = day.met(null)
    PulseCard(onClick = onClick) {
        CardHeader("Today", "${day.coreMet} of ${day.core.size} done", if (done) c.good else muted)
        day.core.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (g.met) PulseIcons.Check else g.metric.icon, contentDescription = null, tint = c.label(g.metric.tone), modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(g.metric.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline().weight(1f))
                        Text(
                            g.value.let { v -> if (v == null) "No data" else if (g.met) "Done" else "${goalText(g.metric, g.target - v, units)} to go" },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (g.met) c.good else MaterialTheme.colorScheme.onSurface, modifier = Modifier.alignByBaseline(),
                        )
                    }
                    ProgressBar(g.fraction ?: 0f, c.fill(g.metric.tone))
                    Text("${goalValue(g.metric, g.value, units)} of ${goalText(g.metric, g.target, units)}", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
        }
        Text(streakText(done, streak), style = MaterialTheme.typography.titleSmall, color = if (done) c.good else MaterialTheme.colorScheme.onSurface)
    }
}

/** [streak] counts today only when today is done. */
internal fun streakText(done: Boolean, streak: Int): String = when {
    done && streak > 1 -> "Both done. $streak days in a row."
    done -> "Both done. Your streak starts today."
    streak > 0 -> "Reach both today to make it ${streak + 1} days in a row."
    else -> "Reach both today to start a streak."
}

private fun days(n: Int) = if (n == 1) "day" else "days"

private fun summary(ui: MonthUi): String = when {
    ui.daysWithData == 0 -> "No data for this month."
    ui.metric == null && ui.daysMet == 0 -> "A day counts when steps and calories both reach their target."
    ui.metric == null -> "Days when steps and calories both reached their target."
    else -> "Each day is judged by the target you had on that day."
}

@Composable
private fun GoalChip(text: String, metric: GoalMetric?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primary else scheme.surfaceContainer)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (metric != null) Box(Modifier.size(8.dp).background(PulseTheme.colors.fill(metric.tone), CircleShape))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.onPrimary else scheme.onSurface, maxLines = 1)
    }
}

/** One day: ring = progress, tinted centre = reached, solid centre = today. */
@Composable
private fun DayCell(day: DayGoals, metric: GoalMetric?, today: LocalDate, units: Units, onDay: (LocalDate) -> Unit) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val future = day.date > today
    val isToday = day.date == today
    val hasData = if (metric == null) day.core.any { it.value != null } else day[metric].value != null
    val met = !future && day.met(metric)
    val tint = metric?.let { c.fill(it.tone) } ?: c.good
    val spoken = buildString {
        append(day.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)))
        when {
            future -> append(", in the future")
            !hasData -> append(", no data")
            metric == null -> append(", ${day.coreMet} of ${day.core.size} goals reached")
            else -> append(", ${goalText(metric, day[metric].value, units)} of ${goalText(metric, day[metric].target, units)}${if (met) ", reached" else ""}")
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .then(if (future) Modifier else Modifier.clickable { onDay(day.date) })
            .clearAndSetSemantics {
                contentDescription = spoken
                if (!future) {
                    role = Role.Button
                    onClick { onDay(day.date); true }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!future && hasData) {
            if (metric == null) GoalRing(day.core, Modifier.fillMaxSize(), stroke = 4.dp)
            else ProgressRing(day[metric].fraction, tint, Modifier.fillMaxSize(), stroke = 4.dp)
        }
        Box(
            Modifier.fillMaxSize().padding(7.dp)
                .background(if (isToday) scheme.primary else if (met) tint.copy(alpha = 0.26f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.date.dayOfMonth}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (met || isToday) FontWeight.Bold else FontWeight.Medium,
                color = if (isToday) scheme.onPrimary else if (future || !hasData) scheme.onSurfaceVariant.copy(alpha = 0.6f) else scheme.onSurface,
            )
        }
    }
}

@Composable
private fun Legend(metric: GoalMetric?) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        val items = if (metric == null) GoalMetric.entries.filter { it in GoalCalendar.CORE }.map { it.label to c.fill(it.tone) }
        else listOf("Ring: how far" to c.fill(metric.tone), "Filled: reached" to c.fill(metric.tone).copy(alpha = 0.4f))
        items.forEach { (text, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(text, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** The last seven days as small rings, for Today. */
@Composable
fun WeekStrip(days: List<DayGoals>, today: LocalDate, onDay: (LocalDate) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { d ->
            Column(
                Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button) { onDay(d.date) }
                    .padding(vertical = 4.dp)
                    .clearAndSetSemantics { contentDescription = "${d.date.format(DateTimeFormatter.ofPattern("EEEE", locale))}, ${d.coreMet} of ${d.core.size} goals reached" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = if (d.date == today) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (d.date == today) FontWeight.Bold else FontWeight.Normal,
                )
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    GoalRing(d.core, Modifier.fillMaxSize(), stroke = 4.dp)
                    if (d.met(null)) Icon(PulseIcons.Check, contentDescription = null, tint = PulseTheme.colors.good, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** One goal as a compact ring with its numbers, for Today. */
@Composable
fun GoalTile(goal: com.fitbittrack.pulse.ui.goals.GoalProgress, units: Units, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = PulseTheme.colors
    Column(
        modifier.clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            ProgressRing(goal.fraction, c.fill(goal.metric.tone), Modifier.fillMaxSize(), stroke = 6.dp)
            Icon(if (goal.met) PulseIcons.Check else icon, contentDescription = null, tint = c.label(goal.metric.tone), modifier = Modifier.size(22.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(goalValue(goal.metric, goal.value, units), MaterialTheme.typography.titleSmall, MaterialTheme.colorScheme.onSurface)
            FitText("of ${goalValue(goal.metric, goal.target, units)}", MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
            FitText(if (goal.metric == GoalMetric.Zone) "Zone min" else goal.metric.label, MaterialTheme.typography.labelMedium, c.label(goal.metric.tone))
        }
    }
}

/** "3 of 4" in the middle of a big ring. */
@Composable
fun BigGoalRing(day: DayGoals, size: Int) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        GoalRing(day.core, Modifier.fillMaxSize(), stroke = 12.dp)
        Column(Modifier.size((size * 0.62f).dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("${day.coreMet}/${day.core.size}", style = MaterialTheme.typography.headlineMedium)
            Text("goals", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
