package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.LineChart
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.SampleBanner
import com.fitbittrack.pulse.ui.components.ScreenHeader
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.HeartUi
import com.fitbittrack.pulse.ui.model.Range
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HeartScreen(
    heart: HeartUi,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onRangeChange: (Range) -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    PulsePage(contentPadding) {
        ScreenHeader("Heart", headline(heart), c.heartLabel)
        if (isSample) SampleBanner()
        Segmented(Range.entries, heart.range, { it.label }, onRangeChange)

        if (heart.range == Range.Day) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                Column { Text("Latest", style = MaterialTheme.typography.bodyMedium, color = muted); BigValue(Fmt.count(heart.latestBpm), "bpm", 40) }
                Column { Text("Range today", style = MaterialTheme.typography.bodyMedium, color = muted); Text("${Fmt.count(heart.minBpm)}–${Fmt.count(heart.maxBpm)}", style = MaterialTheme.typography.titleMedium) }
            }
            PulseCard {
                if (heart.samples.isEmpty()) {
                    Text("No heart-rate data for today yet. It appears after your band syncs.", style = MaterialTheme.typography.bodyMedium, color = muted)
                } else {
                    val rest = heart.restingHeartRate?.toFloat()
                    LineChart(
                        points = heart.samples.map { it.minuteOfDay / 1440f to it.bpm.toFloat() },
                        color = c.heart,
                        description = "Heart rate today, ${Fmt.count(heart.minBpm)} to ${Fmt.count(heart.maxBpm)} bpm",
                        yMin = 40f, yMax = maxOf(160f, (heart.maxBpm ?: 0) + 10f),
                        referenceY = rest, referenceLabel = rest?.let { "RESTING ${it.toInt()}" },
                        xLabels = listOf(0f to "0:00", 0.25f to "6:00", 0.5f to "12:00", 0.75f to "18:00"),
                        // Zone bands drawn by Pulse from heart rate (Google lists no zone data type for the Fitbit Air).
                        bands = heart.restingHeartRate?.let { zoneBands(it, c.heart) } ?: emptyList(),
                    )
                }
            }
        } else {
            val points = heart.restingByDay
            PulseCard {
                Text("Resting heart rate", style = MaterialTheme.typography.titleMedium)
                LineChart(
                    points = points.mapIndexed { i, (_, v) -> i / (points.size - 1f).coerceAtLeast(1f) to v?.toFloat() },
                    color = c.heart,
                    description = "Resting heart rate over ${heart.range.label.lowercase()}",
                    xLabels = if (points.isEmpty()) emptyList() else listOf(
                        0f to points.first().first.format(shortDate),
                        1f to points.last().first.format(shortDate),
                    ),
                    height = 160.dp,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Active zone minutes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${Fmt.count(heart.zoneMinutes)} of ${heart.zoneMinutesTarget} min", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            ProgressBar((heart.zoneMinutes ?: 0).toFloat() / heart.zoneMinutesTarget.coerceAtLeast(1), c.move, height = 12)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Resting", c.heartLabel, Fmt.count(heart.restingHeartRate), "bpm", Modifier.weight(1f)) {
                heart.restingDeltaVsNormal?.let { d ->
                    Text(
                        when { d < 0 -> "${-d} below normal"; d > 0 -> "$d above normal"; else -> "Normal for you" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (d <= 0) c.good else muted,
                    )
                }
            }
            MetricTile("HRV", c.heartLabel, Fmt.whole(heart.hrvMs), "ms", Modifier.weight(1f)) {
                heart.hrvNormalRange?.let {
                    Text("Your normal: ${it.start.toInt()}–${it.endInclusive.toInt()}", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
        }
        Box(Modifier)
    }
}

private val shortDate: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

private fun headline(h: HeartUi): String = when {
    h.restingDeltaVsNormal == null -> "Heart"
    h.restingDeltaVsNormal <= 0 -> "Calm and steady"
    h.restingDeltaVsNormal >= 4 -> "Running a bit high"
    else -> "About normal"
}

/**
 * Rough heart-rate zones using heart-rate reserve (Karvonen), with an estimated max of 190.
 * These are for shading only; Pulse does not claim them as Google's official zones.
 */
private fun zoneBands(resting: Int, color: androidx.compose.ui.graphics.Color): List<Triple<Float, Float, androidx.compose.ui.graphics.Color>> {
    val reserve = 190 - resting
    fun at(p: Double) = (resting + reserve * p).toFloat()
    return listOf(
        Triple(at(0.4), at(0.6), color.copy(alpha = 0.07f)),
        Triple(at(0.6), at(0.85), color.copy(alpha = 0.13f)),
        Triple(at(0.85), 220f, color.copy(alpha = 0.2f)),
    )
}
