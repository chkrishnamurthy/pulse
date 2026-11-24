package com.fitbittrack.pulse.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fitbittrack.pulse.direct.LocalLane
import com.fitbittrack.pulse.direct.LocalLaneStatus
import com.fitbittrack.pulse.direct.LocalLaneStatus.State
import com.fitbittrack.pulse.direct.LocalSnapshot
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.StatusPill
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** Settings section for the direct lanes: Health Connect on/off, how fresh each lane is, and live heart rate. */
@Composable
fun FasterDataCard(
    status: LocalLaneStatus,
    data: LocalSnapshot?,
    lastTrackerLabel: String,
    onSetHealthConnect: (Boolean) -> Unit,
    stepSensor: Boolean,
    stepsOn: Boolean,
    onLiveSteps: (Boolean) -> Unit,
    heartOn: Boolean,
    bandKnown: Boolean,
    onLiveHeart: (Boolean) -> Unit,
    onOpenLiveHeart: () -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { onSetHealthConnect(true) }

    // Read again when the page comes back from the phone's settings.
    var stepsAllowed by remember { mutableStateOf(stepsAllowed(context)) }
    var stepsRefused by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { stepsAllowed = stepsAllowed(context); if (stepsAllowed) stepsRefused = false; onPauseOrDispose {} }
    val askSteps = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        stepsAllowed = ok
        stepsRefused = !ok
        onLiveSteps(ok)
    }

    SectionTitle("Faster data")
    RowGroup(listOf(
        {
            if (stepSensor) {
                SwitchRow("Live steps", "Adds your phone's steps to the band's last count. Carry your phone for it.", stepsOn && stepsAllowed) { on ->
                    if (!on || stepsAllowed) onLiveSteps(on)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askSteps.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            } else {
                Text("Live steps: this phone has no step sensor.", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(16.dp))
            }
        },
        {
            SwitchRow(
                "Live heart rate on Home",
                if (bandKnown) "While Home is open and the band shares its heart rate." else "Turn on, then connect your band once.",
                heartOn,
            ) { on ->
                onLiveHeart(on)
                if (on && !bandKnown) onOpenLiveHeart()
            }
        },
        { ListRow(PulseIcons.Heart, c.heartLabel, "Open live heart rate", "Straight from the band over Bluetooth", onClick = onOpenLiveHeart) },
    ), dividerInset = 16)
    if (stepsRefused) {
        Text(
            "Pulse needs the Physical activity permission for live steps. Allow it in your phone's Settings → Apps → Pulse → Permissions.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        "Live steps show a ~ because they are a close guess. They are never saved. Your goals, calendar and history use only the band's own count.",
        style = MaterialTheme.typography.bodyMedium, color = muted,
    )
    PulseCard {
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        val note = when (status.state) {
            State.Unavailable -> "Not available on this phone. Pulse uses Google's servers."
            State.NeedsUpdate -> "Install or update Health Connect from the Play Store first."
            State.Off -> "Reads today's steps, distance, floors, calories and heart rate on this phone, without the internet trip."
            State.NeedsPermission -> "Pulse is not allowed to read yet. Allow steps and heart rate."
            State.NoBandData -> "No Fitbit data found yet. In Google Health open Connections → Partner apps → Manage Health Connect and turn on sharing for steps and heart rate."
            State.On -> "Today's numbers use whichever is fresher: Health Connect or Google's servers."
        }
        if (status.state == State.On) StatusPill("On", c.good, c.goodContainer)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = muted)
        if (status.state == State.On) {
            InfoRow("Newest steps here", data?.newestSteps?.let { Fmt.ago(it) } ?: "none")
            InfoRow("Newest heart rate here", data?.newestHeart?.let { Fmt.ago(it) } ?: "none")
            InfoRow("Newest on Google's servers", lastTrackerLabel)
        }
        if (status.unusedSources.isNotEmpty()) {
            Text("Not used, to avoid counting steps twice: ${status.unusedSources.joinToString()}", style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        if (status.state == State.On && status.caloriesMissing) Text("Calories are not allowed yet.", style = MaterialTheme.typography.bodyMedium, color = muted)
        status.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status.state) {
                State.Off -> Button(onClick = { onSetHealthConnect(true); ask.launch(LocalLane.PERMISSIONS) }) { Text("Turn on") }
                State.NeedsPermission -> Button(onClick = { ask.launch(LocalLane.PERMISSIONS) }) { Text("Allow") }
                State.NeedsUpdate -> Button(onClick = { openStore(context) }) { Text("Open Play Store") }
                State.On -> if (status.caloriesMissing) Button(onClick = { ask.launch(LocalLane.PERMISSIONS) }) { Text("Allow") }
                else -> Unit
            }
            if (status.state == State.On || status.state == State.NeedsPermission || status.state == State.NoBandData) {
                TextButton(onClick = { open(context, HealthConnectClient.getHealthConnectManageDataIntent(context)) }) { Text("Open Health Connect") }
                TextButton(onClick = { onSetHealthConnect(false) }) { Text("Turn off") }
            }
        }
    }
}

private fun stepsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

private fun openStore(context: Context) =
    open(context, Intent(Intent.ACTION_VIEW, "market://details?id=com.google.android.apps.healthdata".toUri()))

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing on this phone can show it.
    }
}
