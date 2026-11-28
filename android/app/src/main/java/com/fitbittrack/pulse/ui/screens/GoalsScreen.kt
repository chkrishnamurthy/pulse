package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.IconBadge
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.fill
import com.fitbittrack.pulse.ui.components.icon
import com.fitbittrack.pulse.ui.components.label
import com.fitbittrack.pulse.ui.components.tone
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** The goals the data layer stores (mirrors domain.Goals, kept UI-side so screens don't depend on it). */
data class GoalsUi(val dailySteps: Int, val sleepMinutes: Int, val weeklyZoneMinutes: Int, val dailyFloors: Int, val dailyCalories: Int, val dailyDistanceMeters: Int)

@Composable
fun GoalsScreen(goals: GoalsUi, units: Units, contentPadding: PaddingValues, onBack: () -> Unit, onGoalsChange: (GoalsUi) -> Unit) {
    DetailPage("Goals", contentPadding, onBack) {
        RowGroup(listOf(
            { GoalStepper(GoalMetric.Steps, "Steps a day", Fmt.count(goals.dailySteps), goals.dailySteps, 500, 1_000..50_000) { onGoalsChange(goals.copy(dailySteps = it)) } },
            { GoalStepper(GoalMetric.Calories, "Calories a day", "${Fmt.count(goals.dailyCalories)} kcal", goals.dailyCalories, 50, 1_000..6_000) { onGoalsChange(goals.copy(dailyCalories = it)) } },
            {
                // Half a kilometre, or half a mile.
                GoalStepper(GoalMetric.Distance, "Distance a day", goalText(GoalMetric.Distance, goals.dailyDistanceMeters.toDouble(), units), goals.dailyDistanceMeters, if (units == Units.Metric) 500 else 805, 1_000..50_000) { onGoalsChange(goals.copy(dailyDistanceMeters = it)) }
            },
            { GoalStepper(GoalMetric.Sleep, "Sleep a night", Fmt.minutes(goals.sleepMinutes), goals.sleepMinutes, 15, 240..720) { onGoalsChange(goals.copy(sleepMinutes = it)) } },
            { GoalStepper(GoalMetric.Zone, "Zone minutes a week", "${goals.weeklyZoneMinutes} min", goals.weeklyZoneMinutes, 10, 10..1_000) { onGoalsChange(goals.copy(weeklyZoneMinutes = it)) } },
            { GoalStepper(GoalMetric.Floors, "Floors a day", "${goals.dailyFloors}", goals.dailyFloors, 1, 1..100) { onGoalsChange(goals.copy(dailyFloors = it)) } },
        ))
        Text(
            "The calendar tracks steps and calories. A new goal counts from today. Earlier days in the calendar keep the goal they had. Calories means all energy used in a day, resting included. The weekly zone goal is split over seven days.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GoalStepper(metric: GoalMetric, label: String, value: String, now: Int, step: Int, range: IntRange, onChange: (Int) -> Unit) {
    val tint = PulseTheme.colors.label(metric.tone)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(metric.icon, tint)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            IconButton(onClick = { onChange((now - step).coerceIn(range)) }, enabled = now > range.first) { Icon(PulseIcons.Minus, contentDescription = "Less: $label", modifier = Modifier.size(20.dp)) }
            IconButton(onClick = { onChange((now + step).coerceIn(range)) }, enabled = now < range.last) { Icon(PulseIcons.Plus, contentDescription = "More: $label", modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Which cards Today shows, and in which order. */
@Composable
fun CustomizeScreen(prefs: UiPrefs, contentPadding: PaddingValues, onBack: () -> Unit, onPrefsChange: (UiPrefs) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    DetailPage("Today screen", contentPadding, onBack) {
        Text("Show, hide or move the cards on your home.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        RowGroup(prefs.todayCards.mapIndexed { i, (card, on) ->
            {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(cardColor(card), CircleShape))
                    Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(card.label, style = MaterialTheme.typography.titleSmall)
                        Text(card.about, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onPrefsChange(prefs.copy(todayCards = prefs.todayCards.move(i, i - 1))) }, enabled = i > 0) {
                        Icon(PulseIcons.Up, contentDescription = "Move ${card.label} up", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onPrefsChange(prefs.copy(todayCards = prefs.todayCards.move(i, i + 1))) }, enabled = i < prefs.todayCards.lastIndex) {
                        Icon(PulseIcons.Down, contentDescription = "Move ${card.label} down", modifier = Modifier.size(20.dp))
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { v -> onPrefsChange(prefs.copy(todayCards = prefs.todayCards.mapIndexed { j, p -> if (j == i) p.first to v else p })) },
                        modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = "Show ${card.label}" },
                    )
                }
            }
        }, dividerInset = 38)
        // Steps has its own card and sleep and calories are always listed, so only the rest can be added.
        SectionTitle("More goals on Home")
        RowGroup(GoalMetric.entries.filter { it in GoalCalendar.TODAY && it != GoalMetric.Steps && it !in HOME_ALWAYS }.map { m ->
            {
                val on = m in prefs.ringGoals
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(PulseTheme.colors.fill(m.tone), CircleShape))
                    Text(m.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Switch(
                        checked = on,
                        // Steps stays in the saved set, so turning the last extra goal off never empties it.
                        onCheckedChange = { v -> onPrefsChange(prefs.copy(ringGoals = if (v) prefs.ringGoals + m else prefs.ringGoals - m + GoalMetric.Steps)) },
                        modifier = Modifier.semantics { contentDescription = "Show ${m.label} on Home" },
                    )
                }
            }
        }, dividerInset = 38)
    }
}

@Composable
private fun cardColor(card: TodayCard): androidx.compose.ui.graphics.Color {
    val c = PulseTheme.colors
    return when (card) {
        TodayCard.Ring -> c.move
        TodayCard.Train -> c.active
        TodayCard.Band, TodayCard.Air, TodayCard.Body -> c.body
        TodayCard.Week -> c.energy
        TodayCard.Tip -> c.good
    }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    val m = toMutableList()
    val item = m.removeAt(from)
    m.add(to, item)
    return m
}
