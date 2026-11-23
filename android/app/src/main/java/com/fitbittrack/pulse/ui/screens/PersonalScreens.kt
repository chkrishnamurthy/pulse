package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.IconBadge
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.icon
import com.fitbittrack.pulse.ui.components.label
import com.fitbittrack.pulse.ui.components.tone
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.PersonalPrefs
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** Age and top heart rate. The heart screen shades its effort zones with the top heart rate. */
@Composable
fun KnowMeScreen(p: PersonalPrefs, contentPadding: PaddingValues, onBack: () -> Unit, onChange: (PersonalPrefs) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DetailPage("Know me", contentPadding, onBack) {
        SectionTitle("About me")
        RowGroup(listOf(
            { Stepper("Age", p.age?.toString() ?: "Not set", p.age ?: 30, 1, 10..100) { onChange(p.copy(age = it)) } },
            { Stepper("Top heart rate", "${p.maxHeartRate} beats a minute" + if (p.topHeartRate == null) " (automatic)" else "", p.maxHeartRate, 1, 120..230) { onChange(p.copy(topHeartRate = it)) } },
        ))
        Text(
            "Automatic is 220 minus your age, or 190 until you set your age. Change the top heart rate if you know your real number. It only moves the shaded effort zones on the Heart screen.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
        if (p.age != null || p.topHeartRate != null) {
            TextButton(onClick = { onChange(PersonalPrefs(p.calendarGoals)) }) { Text("Clear age and top heart rate") }
        }
    }
}

/** Which goals a calendar day needs to be green. */
@Composable
fun CalendarGoalsScreen(
    chosen: Set<GoalMetric>, goals: GoalsUi, units: Units, contentPadding: PaddingValues,
    onBack: () -> Unit, onChange: (Set<GoalMetric>) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val options = listOf(GoalMetric.Steps, GoalMetric.Calories, GoalMetric.Distance, GoalMetric.Sleep, GoalMetric.Zone, GoalMetric.Floors)
    fun target(m: GoalMetric): String = when (m) {
        GoalMetric.Steps -> goalText(m, goals.dailySteps.toDouble(), units) + " a day"
        GoalMetric.Calories -> goalText(m, goals.dailyCalories.toDouble(), units) + " a day"
        GoalMetric.Distance -> goalText(m, goals.dailyDistanceMeters.toDouble(), units) + " a day"
        GoalMetric.Sleep -> goalText(m, goals.sleepMinutes.toDouble(), units) + " a night"
        GoalMetric.Zone -> "${goals.weeklyZoneMinutes} min a week"
        GoalMetric.Floors -> "${goals.dailyFloors} floors a day"
    }
    DetailPage("Calendar goals", contentPadding, onBack) {
        Text("A calendar day turns green when you reach every goal you tick here.", style = MaterialTheme.typography.bodyMedium, color = muted)
        RowGroup(options.map { m ->
            {
                val on = m in chosen
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(m.icon, PulseTheme.colors.label(m.tone))
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.titleMedium)
                        Text(target(m), style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    Switch(
                        checked = on,
                        // The last goal stays on: a day needs at least one.
                        enabled = !(on && chosen.size == 1),
                        onCheckedChange = { v -> onChange(if (v) chosen + m else chosen - m) },
                        modifier = Modifier.semantics { contentDescription = "Count ${m.label} in the calendar" },
                    )
                }
            }
        })
        Text(
            "Two or three goals feel best. Change a target in You, then Goals. Days already in the calendar are judged again with the goals you choose now.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
    }
}

@Composable
private fun Stepper(label: String, value: String, now: Int, step: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            IconButton(onClick = { onChange((now - step).coerceIn(range)) }, enabled = now > range.first) { Icon(PulseIcons.Minus, contentDescription = "Less: $label", modifier = Modifier.size(20.dp)) }
            IconButton(onClick = { onChange((now + step).coerceIn(range)) }, enabled = now < range.last) { Icon(PulseIcons.Plus, contentDescription = "More: $label", modifier = Modifier.size(20.dp)) }
        }
    }
}
