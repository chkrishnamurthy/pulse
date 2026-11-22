package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.SampleBanner
import com.fitbittrack.pulse.ui.components.ScreenHeader
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.ActivityUi
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme

@Composable
fun ActivityScreen(activity: ActivityUi, units: Units, isSample: Boolean, contentPadding: PaddingValues) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    PulsePage(contentPadding) {
        ScreenHeader("Activity · last 7 days", workoutsTitle(activity.workouts.size), c.moveLabel)
        if (isSample) SampleBanner()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Zone min", c.moveLabel, Fmt.count(activity.weekZoneMinutes), null, Modifier.weight(1f)) {
                Text("goal ${activity.weekZoneTarget}", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            val (d, u) = Fmt.distance(activity.weekDistanceMeters, units)
            MetricTile("Distance", c.moveLabel, d, u, Modifier.weight(1f))
            MetricTile("Active", c.moveLabel, Fmt.minutes(activity.weekActiveMinutes?.toInt()), null, Modifier.weight(1f))
        }
        ProgressBar((activity.weekZoneMinutes ?: 0).toFloat() / activity.weekZoneTarget.coerceAtLeast(1), c.move)

        Text("History", style = MaterialTheme.typography.titleMedium)
        if (activity.workouts.isEmpty()) {
            PulseCard { Text("No workouts in the last 7 days.", style = MaterialTheme.typography.bodyMedium, color = muted) }
        }
        activity.workouts.forEach { w ->
            PulseCard {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).background(c.move.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(w.tag, style = MaterialTheme.typography.labelSmall, color = c.moveLabel) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row {
                            Text(w.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(w.whenLabel, style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                        Text(
                            listOfNotNull(
                                w.distanceMeters?.let { Fmt.distance(it, units).let { (v, u) -> "$v $u" } },
                                "${w.durationMinutes} min",
                                w.avgHeartRate?.let { "avg $it bpm" },
                                w.caloriesKcal?.let { "${Fmt.whole(it)} kcal" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                }
            }
        }
        Text(
            "Workouts come from your band's auto-detect or ones you start in the Google Health app. Routes need your phone's GPS.",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
    }
}

private fun workoutsTitle(n: Int) = when (n) { 0 -> "No workouts"; 1 -> "1 workout"; else -> "$n workouts" }
