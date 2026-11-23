package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.components.LineChart
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.HeartUi
import com.fitbittrack.pulse.ui.model.Range
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HeartScreen(
    heart: HeartUi?,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onRangeChange: (Range) -> Unit,
    onOpenLive: () -> Unit,
    onBack: () -> Unit,
    onMetric: (MetricKind) -> Unit,
    onAsk: (String) -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DetailPage("Heart rate", contentPadding, onBack) {
        if (heart == null) return@DetailPage
        Segmented(Range.entries, heart.range, { it.label }, onRangeChange)

        if (heart.range == Range.Day) {
            PulseCard {
                CardHeader("Latest", heart.minBpm?.let { "Today ${Fmt.count(it)}–${Fmt.count(heart.maxBpm)} bpm" })
                BigValue(Fmt.count(heart.latestBpm), "bpm", 40)
                if (heart.samples.isEmpty()) {
                    Text("No heart-rate data for today yet. It appears after your band syncs.", style = MaterialTheme.typography.bodyMedium, color = muted)
                } else {
                    val rest = heart.restingHeartRate?.toFloat()
                    LineChart(
                        points = heart.samples.map { it.minuteOfDay / 1440f to it.bpm.toFloat() },
                        color = c.heart,
                        description = "Heart rate today, ${Fmt.count(heart.minBpm)} to ${Fmt.count(heart.maxBpm)} bpm",
                        yMin = 40f, yMax = maxOf(160f, (heart.maxBpm ?: 0) + 10f),
                        referenceY = rest, referenceLabel = rest?.let { "Resting ${it.toInt()}" },
                        xLabels = listOf(0f to hourLabel(0), 0.25f to hourLabel(6), 0.5f to hourLabel(12), 0.75f to hourLabel(18)),
                        // Zone bands drawn by Pulse from heart rate (Google lists no zone data type for the Fitbit Air).
                        bands = heart.restingHeartRate?.let { zoneBands(it, c.heart) } ?: emptyList(),
                    )
                }
            }
        } else {
            val points = heart.restingByDay
            PulseCard {
                CardHeader("Resting heart rate", headline(heart))
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

        TileRow {
            MetricTile("Resting", c.heartLabel, Fmt.count(heart.restingHeartRate), "bpm", Modifier.weight(1f), { onMetric(MetricKind.RestingHeart) }) {
                heart.restingDeltaVsNormal?.let { d ->
                    Text(
                        when { d < 0 -> "${-d} below normal"; d > 0 -> "$d above normal"; else -> "Normal for you" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (d <= 0) c.good else muted,
                    )
                }
            }
            MetricTile("Variability", c.heartLabel, Fmt.whole(heart.hrvMs), "ms", Modifier.weight(1f), { onMetric(MetricKind.Hrv) }) {
                heart.hrvNormalRange?.let {
                    Text("Normal ${it.start.toInt()}–${it.endInclusive.toInt()}", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
        }
        PulseCard(onClick = { onMetric(MetricKind.Zone) }) {
            CardHeader("Zone minutes today", "${Fmt.count(heart.zoneMinutes)} of ${heart.zoneMinutesTarget}")
            ProgressBar((heart.zoneMinutes ?: 0).toFloat() / heart.zoneMinutesTarget.coerceAtLeast(1), c.active, height = 10)
        }
        if (!isSample) RowGroup(listOf({ ListRow(PulseIcons.Heart, c.heartLabel, "Live heart rate", "Straight from your band", onClick = onOpenLive) }))
        AskChips("Ask about your heart", listOf("Is my resting heart rate normal for me?", "How is my HRV?"), onAsk)
        if (heart.range == Range.Day) Text("Shaded bands are rough effort zones worked out by Pulse, not Google's official ones.", style = MaterialTheme.typography.bodySmall, color = muted)
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
