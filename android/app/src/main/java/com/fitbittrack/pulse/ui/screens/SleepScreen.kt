package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BedtimeChart
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.Hypnogram
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.components.stageColor
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.SleepUi
import com.fitbittrack.pulse.ui.model.Stage
import com.fitbittrack.pulse.ui.theme.PulseTheme

@Composable
fun SleepScreen(sleep: SleepUi?, contentPadding: PaddingValues, onBack: () -> Unit, onMetric: (MetricKind) -> Unit) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DetailPage("Sleep", contentPadding, onBack) {
        if (sleep == null) return@DetailPage
        PulseCard {
            Text("Last night", style = MaterialTheme.typography.bodyMedium, color = muted)
            BigValue(Fmt.minutes(sleep.minutesAsleep), null, 40)
            if (sleep.minutesAsleep != null) {
                ProgressBar(sleep.minutesAsleep.toFloat() / sleep.sleepGoalMinutes.coerceAtLeast(1), c.sleep, height = 10)
                Row {
                    Text("Goal ${Fmt.minutes(sleep.sleepGoalMinutes)}", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
                    Text("${Fmt.clock(sleep.start)} – ${Fmt.clock(sleep.end)}", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
        }

        if (sleep.blocks.isEmpty()) {
            PulseCard { Text("No sleep recorded for last night yet. Wear your band to bed and open the Google Health app in the morning to sync.", style = MaterialTheme.typography.bodyMedium, color = muted) }
        } else {
            PulseCard {
                CardHeader("Stages", "${Fmt.minutes(sleep.totalMinutesInBed)} in bed")
                Hypnogram(sleep.blocks, sleep.totalMinutesInBed, Fmt.clock(sleep.start), Fmt.clock(sleep.end))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Stage.Awake to "Awake", Stage.Rem to "REM", Stage.Light to "Light", Stage.Deep to "Deep").forEach { (s, name) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(stageColor(s, c)))
                            Text(name, style = MaterialTheme.typography.bodySmall, color = muted)
                            Text(Fmt.minutes(sleep.minutesByStage[s]), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        }
                    }
                }
            }
        }

        if (sleep.week.isNotEmpty()) {
            PulseCard {
                CardHeader("Last 7 nights", "Asleep and awake times")
                BedtimeChart(sleep.week)
            }
        }

        SectionTitle("Your body overnight")
        TileRow {
            MetricTile("Blood oxygen", c.bodyLabel, Fmt.whole(sleep.spo2Percent), "%", Modifier.weight(1f), { onMetric(MetricKind.Spo2) }, size = 24)
            MetricTile("Breathing", c.bodyLabel, Fmt.oneDecimal(sleep.respiratoryRate), "/min", Modifier.weight(1f), { onMetric(MetricKind.Breathing) }, size = 24)
        }
        TileRow {
            MetricTile("Skin temperature", c.bodyLabel, Fmt.signedTemp(sleep.skinTempDeltaC), "C", Modifier.weight(1f), { onMetric(MetricKind.SkinTemp) }, size = 24)
            MetricTile("Heart variability", c.heartLabel, Fmt.whole(sleep.hrvMs), "ms", Modifier.weight(1f), { onMetric(MetricKind.Hrv) }, size = 24)
        }
    }
}
