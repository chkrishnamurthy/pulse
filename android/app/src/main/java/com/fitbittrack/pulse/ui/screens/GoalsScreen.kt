package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.MetricLabel
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** The goals the data layer stores (mirrors domain.Goals, kept UI-side so screens don't depend on it). */
data class GoalsUi(val dailySteps: Int, val sleepMinutes: Int, val weeklyZoneMinutes: Int, val dailyFloors: Int)

@Composable
fun GoalsScreen(
    goals: GoalsUi,
    prefs: UiPrefs,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onGoalsChange: (GoalsUi) -> Unit,
    onPrefsChange: (UiPrefs) -> Unit,
) {
    val c = PulseTheme.colors
    PulsePage(contentPadding) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(PulseIcons.Back, contentDescription = "Back") }
            Text("Goals and layout", style = MaterialTheme.typography.headlineMedium)
        }

        MetricLabel("Goals", MaterialTheme.colorScheme.onSurfaceVariant)
        GoalRow("Daily steps", Fmt.count(goals.dailySteps), c.move,
            dec = { onGoalsChange(goals.copy(dailySteps = (goals.dailySteps - 500).coerceAtLeast(1000))) },
            inc = { onGoalsChange(goals.copy(dailySteps = (goals.dailySteps + 500).coerceAtMost(50_000))) })
        GoalRow("Weekly zone minutes", "${goals.weeklyZoneMinutes} min", c.move,
            dec = { onGoalsChange(goals.copy(weeklyZoneMinutes = (goals.weeklyZoneMinutes - 10).coerceAtLeast(10))) },
            inc = { onGoalsChange(goals.copy(weeklyZoneMinutes = (goals.weeklyZoneMinutes + 10).coerceAtMost(1000))) })
        GoalRow("Sleep", Fmt.minutes(goals.sleepMinutes), c.sleep,
            dec = { onGoalsChange(goals.copy(sleepMinutes = (goals.sleepMinutes - 15).coerceAtLeast(240))) },
            inc = { onGoalsChange(goals.copy(sleepMinutes = (goals.sleepMinutes + 15).coerceAtMost(720))) })
        GoalRow("Daily floors", "${goals.dailyFloors}", c.move,
            dec = { onGoalsChange(goals.copy(dailyFloors = (goals.dailyFloors - 1).coerceAtLeast(1))) },
            inc = { onGoalsChange(goals.copy(dailyFloors = (goals.dailyFloors + 1).coerceAtMost(100))) })

        MetricLabel("Cards on Today", MaterialTheme.colorScheme.onSurfaceVariant)
        PulseCard {
            prefs.todayCards.forEachIndexed { i, (card, on) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(cardColor(card), CircleShape))
                    Text(card.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding8())
                    IconButton(
                        onClick = { onPrefsChange(prefs.copy(todayCards = prefs.todayCards.move(i, i - 1))) },
                        enabled = i > 0,
                        modifier = Modifier.semantics { contentDescription = "Move ${card.label} up" },
                    ) { Text("↑") }
                    IconButton(
                        onClick = { onPrefsChange(prefs.copy(todayCards = prefs.todayCards.move(i, i + 1))) },
                        enabled = i < prefs.todayCards.lastIndex,
                        modifier = Modifier.semantics { contentDescription = "Move ${card.label} down" },
                    ) { Text("↓") }
                    Switch(
                        checked = on,
                        onCheckedChange = { v -> onPrefsChange(prefs.copy(todayCards = prefs.todayCards.mapIndexed { j, p -> if (j == i) p.first to v else p })) },
                        modifier = Modifier.semantics { contentDescription = "Show ${card.label}" },
                    )
                }
            }
        }

        MetricLabel("Units", MaterialTheme.colorScheme.onSurfaceVariant)
        Segmented(Units.entries, prefs.units, { if (it == Units.Metric) "km · kg" else "mi · lb" }) { onPrefsChange(prefs.copy(units = it)) }
    }
}

@Composable
private fun GoalRow(label: String, value: String, color: Color, dec: () -> Unit, inc: () -> Unit) {
    PulseCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(8.dp).height(36.dp).background(color, RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
            FilledTonalIconButton(onClick = dec, modifier = Modifier.semantics { contentDescription = "Decrease $label" }) { Text("−") }
            FilledTonalIconButton(onClick = inc, modifier = Modifier.semantics { contentDescription = "Increase $label" }) { Text("+") }
        }
    }
}

@Composable
private fun cardColor(card: TodayCard): Color {
    val c = PulseTheme.colors
    return when (card) {
        TodayCard.Dial -> MaterialTheme.colorScheme.onSurface
        TodayCard.RestingHeart, TodayCard.Hrv -> c.heart
        TodayCard.Sleep -> c.sleep
        TodayCard.Spo2 -> c.body
        else -> c.move
    }
}

private fun Modifier.padding8() = this.padding(start = 10.dp)

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    val m = toMutableList()
    val item = m.removeAt(from)
    m.add(to, item)
    return m
}
