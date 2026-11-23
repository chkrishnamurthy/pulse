package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.reminders.ReminderPrefs
import com.fitbittrack.pulse.reminders.STALE_HOURS
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.model.SyncUi
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** Battery, last sync and band alerts. Battery and sync time come from Google, so they are as fresh as the last sync. */
@Composable
fun MyBandScreen(
    sync: SyncUi, isSample: Boolean, p: ReminderPrefs, contentPadding: PaddingValues,
    onBack: () -> Unit, onChange: (ReminderPrefs) -> Unit,
) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val level = sync.batteryPercent
    DetailPage("My band", contentPadding, onBack) {
        PulseCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val track = scheme.surfaceContainerHigh
                val tint = if (level != null && level <= p.lowBatteryAt) scheme.error else c.good
                Box(Modifier.size(150.dp).semantics { contentDescription = level?.let { "Battery $it percent" } ?: "Battery not known" }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(150.dp)) {
                        val w = 14.dp.toPx()
                        val d = Size(size.width - w, size.height - w)
                        drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), d, style = Stroke(w))
                        if (level != null) drawArc(tint, -90f, 360f * level / 100f, false, Offset(w / 2, w / 2), d, style = Stroke(w, cap = StrokeCap.Round))
                    }
                    Text(level?.let { "$it%" } ?: "–", style = MaterialTheme.typography.displaySmall)
                }
                Text("Fitbit Air", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        isSample -> "Connect your Google account to see your band."
                        level != null -> "Battery as of the last sync"
                        sync.batteryWord != null -> "Battery: ${sync.batteryWord}"
                        else -> "Google has not reported the battery yet. It appears after the next sync."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = muted,
                )
            }
            InfoRow("Newest band data", sync.lastTrackerLabel)
            InfoRow("Pulse last synced", sync.lastSuccessLabel)
        }

        SectionTitle("Alerts")
        RowGroup(listOf(
            { SwitchRow("Low battery alert", "So it does not die before bed", p.lowBatteryOn) { onChange(p.copy(lowBatteryOn = it)) } },
            { NumberStepper("Warn me at", "${p.lowBatteryAt}%", p.lowBatteryAt, 5, 5..50) { onChange(p.copy(lowBatteryAt = it)) } },
            { SwitchRow("Not synced alert", "If there is no new band data for over $STALE_HOURS hours", p.staleOn) { onChange(p.copy(staleOn = it)) } },
        ))
        Text(
            "Alerts come at most once a day and never in your quiet hours (set in Reminders). Notifications must be allowed for Pulse.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
        Text(
            "Alarm and Smart Wake stay in the Google Health app. Google does not let other apps set them.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
    }
}
