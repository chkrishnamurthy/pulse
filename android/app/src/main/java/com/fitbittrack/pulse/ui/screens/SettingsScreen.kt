package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.fitbittrack.pulse.TestState
import com.fitbittrack.pulse.UiState
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
    state: UiState,
    sync: SyncUi,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSyncNow: () -> Unit,
    onConnect: () -> Unit,
    onRetryTest: () -> Unit,
    onDeleteData: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val signedIn = state.auth is AuthState.SignedIn
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }

    PulsePage(contentPadding) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(PulseIcons.Back, contentDescription = "Back") }
            Text("Sync and account", style = MaterialTheme.typography.headlineMedium)
        }

        // --- Sync status (only meaningful with a real account) ---
        if (signedIn && !isSample) {
            PulseCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        sync.needsSignIn -> StatusPill("Sign in needed", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.surfaceContainerHigh)
                        sync.error != null -> StatusPill("Sync problem", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.surfaceContainerHigh)
                        sync.running -> StatusPill("Syncing", muted, MaterialTheme.colorScheme.surfaceContainerHigh)
                        sync.firstSyncPending -> StatusPill("Waiting for first sync", muted, MaterialTheme.colorScheme.surfaceContainerHigh)
                        else -> StatusPill("Up to date", c.good, c.goodContainer)
                    }
                    Row(Modifier.weight(1f)) {}
                    if (sync.needsSignIn) Button(onClick = onConnect) { Text("Sign in") }
                    else Button(onClick = onSyncNow, enabled = !sync.running) { Text("Sync now") }
                }
                if (sync.running) {
                    if (sync.progress != null) LinearProgressIndicator(progress = { sync.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
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
        }

        // --- Appearance ---
        MetricLabel("Appearance", muted)
        Segmented(ThemeMode.entries, state.themeMode, { it.name }, onThemeChange)
        Text(
            when (state.themeMode) {
                ThemeMode.System -> "Follows your phone's light or dark setting."
                ThemeMode.Light -> "Always light."
                ThemeMode.Dark -> "Always dark."
            },
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )

        // --- Account ---
        MetricLabel("Account", muted)
        PulseCard {
            if (!signedIn) {
                Text("Not connected", style = MaterialTheme.typography.titleMedium)
                Text("You are looking at sample data. Connect the Google account your Fitbit Air uses.", style = MaterialTheme.typography.bodyMedium, color = muted)
                (state.auth as? AuthState.Error)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onConnect, enabled = state.auth !is AuthState.Working) { Text("Connect Google account") }
            } else {
                val auth = state.auth as AuthState.SignedIn
                Text("Google account connected", style = MaterialTheme.typography.titleMedium)
                state.accountName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "Read-only access" + (auth.grantedScopes?.let { " · $it of 5 permissions" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = muted,
                )
                when (val t = state.connectionTest) {
                    TestState.NotRun -> Unit
                    TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        Text("Checking connection to Google Health…", style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    is TestState.Passed -> StatusPill("Google Health connected", c.good, c.goodContainer)
                    is TestState.Failed -> {
                        Text(t.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRetryTest) { Text("Check again") }
                            TextButton(onClick = onConnect) { Text("Sign in again") }
                        }
                    }
                }
            }
        }

        if (signedIn) {
            OutlinedButton(onClick = { confirm = "delete" }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Delete data stored on this phone")
            }
            OutlinedButton(
                onClick = { confirm = "signout" },
                enabled = !state.signingOut,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.signingOut) "Signing out…" else "Sign out and disconnect", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Signing out also removes Pulse's permission at Google. You can check this any time at myaccount.google.com → Security → Your connections to third-party apps.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
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
            body = "Pulse stops syncing, deletes everything it saved on this phone, and gives up its permission at Google. Your data in Google Health is not touched.",
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
