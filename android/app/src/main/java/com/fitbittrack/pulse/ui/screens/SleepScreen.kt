package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fitbittrack.pulse.ui.components.BedtimeChart
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.SleepClock
import com.fitbittrack.pulse.ui.components.StageTile
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.components.stageColor
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.SleepUi
import com.fitbittrack.pulse.ui.model.Stage
import com.fitbittrack.pulse.ui.theme.PulseTheme

@Composable
fun SleepScreen(sleep: SleepUi?, contentPadding: PaddingValues, onBack: () -> Unit, onMetric: (MetricKind) -> Unit, onAsk: (String) -> Unit) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DetailPage("Sleep", contentPadding, onBack) {
        if (sleep == null) return@DetailPage
        if (sleep.start == null) {
            PulseCard { Text("No sleep recorded for last night yet. Wear your band to bed and open the Google Health app in the morning to sync.", style = MaterialTheme.typography.bodyMedium, color = muted) }
        } else {
            SleepClock(sleep, sleep.start)
            if (sleep.blocks.isEmpty()) {
                PulseCard { Text("No sleep stages for this night.", style = MaterialTheme.typography.bodyMedium, color = muted) }
            } else {
                // Awake and REM on the first row, light and deep on the second.
                listOf(Stage.Awake to "Awake", Stage.Rem to "REM", Stage.Light to "Light", Stage.Deep to "Deep").chunked(2).forEach { pair ->
                    TileRow {
                        pair.forEach { (stage, name) -> StageTile(name, sleep.minutesByStage[stage], sleep.totalMinutesInBed, stageColor(stage, c)) }
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
        AskChips("Ask about your sleep", listOf("How did I sleep this week?", "Did I get enough deep sleep?"), onAsk)
    }
}
