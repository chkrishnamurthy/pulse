package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SampleChip
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.TabHeader
import com.fitbittrack.pulse.ui.components.label
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.HubUi
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** Every kind of data in one list, with its newest value. */
@Composable
fun HealthScreen(
    hub: HubUi,
    units: Units,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
    onMetric: (MetricKind) -> Unit,
    onHeart: () -> Unit,
    onSleep: () -> Unit,
    onWorkouts: () -> Unit,
    onLiveHeart: () -> Unit,
) {
    val c = PulseTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    fun metric(kind: MetricKind): @Composable () -> Unit = {
        val at = hub.latest[kind]
        ListRow(
            kind.icon, c.label(kind.tone), kind.label,
            subtitle = at?.let { whenLabel(it.first, hub.today, locale) } ?: "No data in the last 30 days",
            value = at?.let { kind.text(it.second, units) } ?: Fmt.NONE,
            onClick = { onMetric(kind) },
        )
    }
    PulsePage(contentPadding) {
        TabHeader("Health", "Everything your band records") { if (isSample) SampleChip(onConnect) }

        SectionTitle("Activity")
        RowGroup(listOf(
            metric(MetricKind.Steps), metric(MetricKind.Calories), metric(MetricKind.Zone), metric(MetricKind.Distance), metric(MetricKind.Floors),
            { ListRow(PulseIcons.Activity, c.moveLabel, "Workouts", "Last 7 days", "${hub.workouts7}", onWorkouts) },
        ))
        SectionTitle("Heart")
        RowGroup(buildList {
            add { ListRow(PulseIcons.Health, c.heartLabel, "Heart rate", "Through the day", hub.latestBpm?.let { "$it bpm" } ?: Fmt.NONE, onHeart) }
            add(metric(MetricKind.RestingHeart)); add(metric(MetricKind.Hrv)); add(metric(MetricKind.Vo2Max))
            if (!isSample) add { ListRow(PulseIcons.Heart, c.heartLabel, "Live heart rate", "Straight from your band", null, onLiveHeart) }
        })
        SectionTitle("Sleep and body")
        RowGroup(listOf(
            { ListRow(PulseIcons.Sleep, c.sleepLabel, "Sleep", "Last night", Fmt.minutes(hub.sleepMinutes), onSleep) },
            metric(MetricKind.Spo2), metric(MetricKind.Breathing), metric(MetricKind.SkinTemp),
        ))
    }
}
