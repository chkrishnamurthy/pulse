package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.ActivityUi
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.model.WorkoutUi
import com.fitbittrack.pulse.ui.theme.PulseTheme

@Composable
fun ActivityScreen(activity: ActivityUi?, units: Units, contentPadding: PaddingValues, onBack: () -> Unit) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DetailPage("Workouts", contentPadding, onBack) {
        if (activity == null) return@DetailPage
        PulseCard {
            CardHeader("Zone minutes this week", "Goal ${activity.weekZoneTarget} min")
            BigValue(Fmt.count(activity.weekZoneMinutes), "min", 36)
            ProgressBar((activity.weekZoneMinutes ?: 0).toFloat() / activity.weekZoneTarget.coerceAtLeast(1), c.active, height = 10)
        }
        TileRow {
            val (d, u) = Fmt.distance(activity.weekDistanceMeters, units)
            MetricTile("Distance", c.moveLabel, d, u, Modifier.weight(1f))
            MetricTile("Active time", c.activeLabel, Fmt.minutes(activity.weekActiveMinutes?.toInt()), null, Modifier.weight(1f))
        }

        SectionTitle(when (val n = activity.workouts.size) { 0 -> "No workouts in the last 7 days"; 1 -> "1 workout in the last 7 days"; else -> "$n workouts in the last 7 days" })
        if (activity.workouts.isNotEmpty()) RowGroup(activity.workouts.map { w -> { WorkoutRow(w, units, showDay = true) } })
        Text(
            "Workouts come from your band's auto-detect, or ones you start in the Google Health app.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
    }
}

@Composable
fun WorkoutRow(w: WorkoutUi, units: Units, showDay: Boolean) {
    val c = PulseTheme.colors
    val icon = when (w.tag) { "WLK" -> PulseIcons.Steps; "GYM" -> PulseIcons.Bolt; else -> PulseIcons.Activity }
    ListRow(
        icon, c.activeLabel, w.name,
        subtitle = listOfNotNull(
            w.whenLabel.takeIf { showDay },
            w.distanceMeters?.let { Fmt.distance(it, units).let { (v, u) -> "$v $u" } },
            w.avgHeartRate?.let { "$it bpm" },
            w.caloriesKcal?.let { "${Fmt.whole(it)} kcal" },
        ).joinToString(" · "),
        value = "${w.durationMinutes} min",
    )
}
