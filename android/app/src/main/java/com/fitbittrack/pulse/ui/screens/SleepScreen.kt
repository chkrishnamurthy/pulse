package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.Hypnogram
import com.fitbittrack.pulse.ui.components.MetricLabel
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.SampleBanner
import com.fitbittrack.pulse.ui.components.stageColor
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.SleepUi
import com.fitbittrack.pulse.ui.model.Stage
import com.fitbittrack.pulse.ui.theme.PulseTheme

@Composable
fun SleepScreen(sleep: SleepUi, isSample: Boolean, contentPadding: PaddingValues) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    PulsePage(contentPadding) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                MetricLabel("Sleep · last night", c.sleepLabel)
                BigValue(Fmt.minutes(sleep.minutesAsleep), null, 44)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("In bed", style = MaterialTheme.typography.bodyMedium, color = muted)
                Text("${Fmt.clock(sleep.start)} – ${Fmt.clock(sleep.end)}", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (isSample) SampleBanner()

        if (sleep.blocks.isEmpty()) {
            PulseCard { Text("No sleep recorded for last night yet. Wear your band to bed and open the Google Health app in the morning to sync.", style = MaterialTheme.typography.bodyMedium, color = muted) }
        } else {
            PulseCard { Hypnogram(sleep.blocks, sleep.totalMinutesInBed, Fmt.clock(sleep.start), Fmt.clock(sleep.end)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Stage.Awake to "Awake", Stage.Rem to "REM", Stage.Light to "Light", Stage.Deep to "Deep").forEach { (s, name) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(stageColor(s, c)))
                        Text(name, style = MaterialTheme.typography.bodyMedium, color = muted)
                        Text(Fmt.minutes(sleep.minutesByStage[s]), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        Text("Your body overnight", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("SpO2", c.bodyLabel, Fmt.whole(sleep.spo2Percent), "%", Modifier.weight(1f))
            MetricTile("Breathing", c.bodyLabel, Fmt.oneDecimal(sleep.respiratoryRate), "/min", Modifier.weight(1f))
            MetricTile("Skin temp", c.bodyLabel, Fmt.signedTemp(sleep.skinTempDeltaC), null, Modifier.weight(1f))
        }

        if (sleep.week.isNotEmpty()) {
            PulseCard {
                Row {
                    Text("Bedtime this week", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("Goal ${Fmt.minutes(sleep.sleepGoalMinutes)}", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                // Each bar spans 18:00 → 12:00 next day (18 h = 1080 min) top to bottom.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    sleep.week.forEach { n ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                if (n.startMin != null && n.endMin != null) {
                                    val top = 64f * n.startMin / 1080f
                                    val h = 64f * (n.endMin - n.startMin) / 1080f
                                    Box(
                                        Modifier.fillMaxWidth().offset(y = top.dp).height(h.dp).clip(RoundedCornerShape(6.dp))
                                            .background(if (n.isLast) c.sleepLabel else c.sleep),
                                    )
                                }
                            }
                            Text(n.label, style = MaterialTheme.typography.bodyMedium, color = if (n.isLast) MaterialTheme.colorScheme.onSurface else muted)
                        }
                    }
                }
            }
        }
    }
}
