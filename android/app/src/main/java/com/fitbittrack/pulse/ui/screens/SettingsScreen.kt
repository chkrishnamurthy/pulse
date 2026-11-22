package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.AuthState
import com.fitbittrack.pulse.ui.components.MetricLabel
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.components.StatusPill
import com.fitbittrack.pulse.ui.model.SyncUi
import com.fitbittrack.pulse.ui.theme.PulseTheme
import com.fitbittrack.pulse.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    auth: AuthState,
    themeMode: ThemeMode,
    sync: SyncUi,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSyncNow: () -> Unit,
    onSignInAgain: () -> Unit,
    onDeleteData: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }

    PulsePage(contentPadding) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(PulseIcons.Back, contentDescription = "Back") }
            Text("Sync and account", style = MaterialTheme.typography.headlineMedium)
        }

        // --- Sync status ---
        PulseCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    sync.needsSignIn -> StatusPill("Sign in needed", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.surfaceContainerHigh)
                    sync.error != null -> StatusPill("Sync problem", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.surfaceContainerHigh)
                    sync.running -> StatusPill("Syncing", muted, MaterialTheme.colorScheme.surfaceContainerHigh)
                    else -> StatusPill("Up to date", c.good, c.goodContainer)
                }
                Row(Modifier.weight(1f)) {}
                if (sync.needsSignIn) Button(onClick = onSignInAgain) { Text("Sign in") }
                else Button(onClick = onSyncNow, enabled = !sync.running) { Text("Sync now") }
            }
            if (sync.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                sync.progressLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
            }
            sync.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            InfoRow("Last sync", sync.lastSuccessLabel)
            InfoRow("Newest band data", sync.lastTrackerLabel)
            InfoRow("Automatic sync", "about every 4 hours")
            Text(
                "Pulse can only show what your band has already synced to the Google Health app.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
        }

        // --- Appearance ---
        MetricLabel("Appearance", muted)
        Segmented(ThemeMode.entries, themeMode, { it.name }, onThemeChange)
        Text(
            when (themeMode) {
                ThemeMode.System -> "Follows your phone's light or dark setting."
                ThemeMode.Light -> "Always light."
                ThemeMode.Dark -> "Always dark."
            },
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )

        // --- Account ---
        MetricLabel("Account", muted)
        PulseCard {
            Text(if (auth is AuthState.SignedIn) "Google account connected" else "Not connected", style = MaterialTheme.typography.titleMedium)
            Text(
                if (auth is AuthState.SignedIn) "Read-only access · ${auth.grantedScopes} of 5 permissions" else "Sign in from the welcome screen.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
            Text(
                "To remove Pulse's access completely, open myaccount.google.com → Security → Third-party apps → Fitbit Track → Remove access.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
        }
        OutlinedButton(onClick = { confirm = "delete" }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Delete data stored on this phone")
        }
        OutlinedButton(onClick = { confirm = "signout" }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Sign out and delete data", color = MaterialTheme.colorScheme.error)
        }
    }

    when (confirm) {
        "delete" -> ConfirmDialog(
            title = "Delete local data?",
            body = "Removes every health record Pulse has saved on this phone. Your data in Google Health is not touched. Pulse downloads it again at the next sync.",
            action = "Delete",
            onConfirm = { confirm = null; onDeleteData() },
            onDismiss = { confirm = null },
        )
        "signout" -> ConfirmDialog(
            title = "Sign out?",
            body = "Pulse stops syncing and deletes everything it saved on this phone. Your data in Google Health is not touched.",
            action = "Sign out",
            onConfirm = { confirm = null; onSignOut() },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
