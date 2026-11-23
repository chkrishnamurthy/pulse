package com.fitbittrack.pulse.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.fitbittrack.pulse.direct.HeartDevice
import com.fitbittrack.pulse.direct.LiveState
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.Sparkline
import com.fitbittrack.pulse.ui.components.StatusPill
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** Android 12+: "Nearby devices". Older: Android only allows a Bluetooth search with the location permission. */
private val bluetoothPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun allowed(context: Context) =
    bluetoothPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun LiveHeartScreen(
    state: LiveState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onConnect: (HeartDevice) -> Unit,
    onStop: () -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refused = !allowed(context)
        if (!refused) onSearch()
    }
    val search = { if (allowed(context)) { refused = false; onSearch() } else ask.launch(bluetoothPermissions) }

    // Leaving the screen ends the connection, so it never uses battery in the background.
    LifecycleStartEffect(Unit) { onStopOrDispose { onStop() } }

    val view = LocalView.current
    val live = state is LiveState.Live
    DisposableEffect(live) {
        view.keepScreenOn = live
        onDispose { view.keepScreenOn = false }
    }

    DetailPage("Live heart rate", contentPadding, onBack) {

        when (state) {
            is LiveState.Live -> PulseCard {
                StatusPill("Live from ${state.name}", c.good, c.goodContainer)
                BigValue(Fmt.count(state.bpm), "bpm", 64)
                if (!state.onSkin) Text("The band says it is not touching your skin.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Sparkline(state.history.map { it.toFloat() }, c.heart)
                if (state.history.isNotEmpty()) {
                    Text("Lowest ${state.history.min()} · highest ${state.history.max()} in the last ${state.history.size} readings", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
            is LiveState.Connecting -> PulseCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Connecting to ${state.name}…", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onClick = onStop) { Text("Cancel") }
            }
            is LiveState.Searching -> PulseCard {
                Text("Choose your band", style = MaterialTheme.typography.titleMedium)
                if (state.devices.isEmpty()) Text("Looking for devices…", style = MaterialTheme.typography.bodyMedium, color = muted)
                state.devices.forEach { d ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onConnect(d) }, verticalArrangement = Arrangement.Center) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (d.sharing) "Sharing heart rate now" else "Known to this phone",
                            style = MaterialTheme.typography.bodyMedium, color = if (d.sharing) c.good else muted,
                        )
                    }
                }
                OutlinedButton(onClick = onSearch) { Text("Search again") }
            }
            is LiveState.Failed -> PulseCard {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Button(onClick = search) { Text("Try again") }
            }
            LiveState.Off -> Button(onClick = search, modifier = Modifier.fillMaxWidth()) { Text("Find my band") }
        }
        if (refused) {
            Text(
                "Pulse needs the Nearby devices permission for this. You can allow it in your phone's Settings → Apps → Pulse → Permissions.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
        }

        PulseCard {
            Text("How to start", style = MaterialTheme.typography.titleMedium)
            listOf(
                "1. Open Google Health → Connections → your Fitbit Air.",
                "2. Tap Share heart rate, then Get started.",
                "3. Come back here and tap Find my band.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "This comes straight from the band, with no internet. It is shown live and not saved. Sharing uses more of the band's battery. Google doesn't say whether an app on the same phone can listen, so it may not work on every phone.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
        }
    }
}
