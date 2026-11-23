package com.fitbittrack.pulse.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fitbittrack.pulse.reminders.BEDTIME_LEAD_MIN
import com.fitbittrack.pulse.reminders.ReminderKind
import com.fitbittrack.pulse.reminders.ReminderLogic
import com.fitbittrack.pulse.reminders.ReminderPrefs
import com.fitbittrack.pulse.reminders.notificationsAllowed
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clock: DateTimeFormatter get() = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

/** Evening nudge, bedtime reminder, morning summary and quiet hours. */
@Composable
fun RemindersScreen(p: ReminderPrefs, contentPadding: PaddingValues, onBack: () -> Unit, onChange: (ReminderPrefs) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(ctx)) }
    LifecycleResumeEffect(Unit) { allowed = notificationsAllowed(ctx); onPauseOrDispose { } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = notificationsAllowed(ctx) }
    // The notification question comes only when something is switched on.
    fun change(x: ReminderPrefs) {
        if ((x.anyTimed || x.anyBand) && !allowed && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        onChange(x)
    }

    DetailPage("Reminders", contentPadding, onBack) {
        if ((p.anyTimed || p.anyBand) && !allowed) {
            PulseCard {
                Text("Notifications are off for Pulse", style = MaterialTheme.typography.titleMedium)
                Text("Turn them on to get your reminders.", style = MaterialTheme.typography.bodyMedium, color = muted)
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open notification settings") }
            }
        }

        SectionTitle("Evening")
        RowGroup(listOf(
            { SwitchRow("Evening goal nudge", "Tells you what is left while there is still time", p.eveningOn) { change(p.copy(eveningOn = it)) } },
            { TimeRow("Send at", p.evening, p.eveningOn) { onChange(p.copy(evening = it)) } },
        ))
        Text("Example: \"Left today: 2,140 steps.\" Nothing is sent when all your goals are done.", style = MaterialTheme.typography.bodyMedium, color = muted)

        SectionTitle("Sleep")
        RowGroup(listOf(
            { SwitchRow("Bedtime reminder", "$BEDTIME_LEAD_MIN minutes before your usual bedtime", p.bedtimeOn) { change(p.copy(bedtimeOn = it)) } },
            { TimeRow("Usual bedtime", p.bedtime, p.bedtimeOn) { onChange(p.copy(bedtime = it)) } },
        ))

        SectionTitle("Morning")
        RowGroup(listOf(
            { SwitchRow("Morning summary", "Sleep, resting heart rate and today's steps", p.morningOn) { change(p.copy(morningOn = it)) } },
            { TimeRow("Send at", p.morning, p.morningOn) { onChange(p.copy(morning = it)) } },
        ))

        SectionTitle("Quiet hours")
        RowGroup(listOf(
            { SwitchRow("Do not disturb", "No reminders or band alerts in this time", p.quietOn) { onChange(p.copy(quietOn = it)) } },
            { TimeRow("From", p.quietFrom, p.quietOn) { onChange(p.copy(quietFrom = it)) } },
            { TimeRow("Until", p.quietTo, p.quietOn) { onChange(p.copy(quietTo = it)) } },
        ))
        val clash = listOf(ReminderKind.Evening, ReminderKind.Bedtime, ReminderKind.Morning)
            .filter { ReminderLogic.isOn(it, p) && ReminderLogic.inQuiet(ReminderLogic.timeOf(it, p), p) }
        if (clash.isNotEmpty()) {
            Text(
                "Some reminders are set inside quiet hours, so they will stay silent. Change their time or the quiet hours.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "Reminders use the data Pulse has already saved, so the numbers are as fresh as your last sync.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
    }
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

/** A row that shows a time and opens the system clock picker when tapped. */
@Composable
private fun TimeRow(label: String, time: LocalTime, enabled: Boolean, onPick: (LocalTime) -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button) {
                TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            time.format(clock), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Plus and minus buttons for a whole number. */
@Composable
fun NumberStepper(label: String, value: String, now: Int, step: Int, range: IntRange, onChange: (Int) -> Unit) {
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
