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
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.IconBadge
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.components.StatusPill
import com.fitbittrack.pulse.ui.components.TabHeader
import com.fitbittrack.pulse.ui.model.SyncUi
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme
import com.fitbittrack.pulse.ui.theme.ThemeMode

/** The You tab: account, goals, look and feel. */
@Composable
fun YouScreen(
    state: UiState,
    sync: SyncUi,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
    onGoals: () -> Unit,
    onKnowMe: () -> Unit,
    onReminders: () -> Unit,
    remindersLabel: String,
    onBand: () -> Unit,
    onCalendarGoals: () -> Unit,
    calendarGoalsLabel: String,
    onCustomize: () -> Unit,
    onSync: () -> Unit,
    onChat: () -> Unit,
    onWidgets: () -> Unit,
    onTraining: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onPrefsChange: (UiPrefs) -> Unit,
) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val signedIn = state.auth is AuthState.SignedIn
    PulsePage(contentPadding) {
        TabHeader("You")

        PulseCard(onClick = if (signedIn) onSync else null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(PulseIcons.You, scheme.onSurface, 52)
                androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (signedIn) "Google account connected" else "Sample data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (signedIn) state.accountName ?: "Read-only access" else "Connect the Google account your Fitbit Air uses.",
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (!signedIn) {
                (state.auth as? AuthState.Error)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = scheme.error) }
                Button(onClick = onConnect, enabled = state.auth !is AuthState.Working, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Connect Google account") }
            }
        }

        RowGroup(buildList {
            add { ListRow(PulseIcons.You, c.heartLabel, "Know me", "Age and top heart rate", onClick = onKnowMe) }
            add { ListRow(PulseIcons.Chat, c.energyLabel, "Reminders", remindersLabel, onClick = onReminders) }
            add { ListRow(PulseIcons.Heart, c.heartLabel, "My band", sync.batteryPercent?.let { "Battery $it%" } ?: "Battery, sync and alerts", onClick = onBand) }
            add { ListRow(PulseIcons.Calendar, c.activeLabel, "Calendar goals", calendarGoalsLabel, onClick = onCalendarGoals) }
            add { ListRow(PulseIcons.Target, c.moveLabel, "Goals", "Steps, calories, sleep and more", onClick = onGoals) }
            add { ListRow(PulseIcons.Gym, c.activeLabel, "Training", "Units, rest, reminder and backup", onClick = onTraining) }
            add { ListRow(PulseIcons.Layout, c.sleepLabel, "Today screen", "Cards, and goals on Home", onClick = onCustomize) }
            add { ListRow(PulseIcons.Layout, c.energyLabel, "Widgets", "Pictures of your day for the home screen", onClick = onWidgets) }
            add { ListRow(PulseIcons.Chat, c.bodyLabel, "Ask Pulse", "What chat may read, your chats", onClick = onChat) }
            if (signedIn && !isSample) add {
                ListRow(PulseIcons.Sync, if (sync.error != null) scheme.error else c.good, "Sync and data", if (sync.needsSignIn) "Sign in again" else if (sync.error != null) "Sync problem" else sync.lastSuccessLabel, onClick = onSync)
            }
        })

        SectionTitle("Appearance")
        Segmented(ThemeMode.entries, state.themeMode, { if (it == ThemeMode.System) "Automatic" else it.name }, onThemeChange)
        Text(
            when (state.themeMode) {
                ThemeMode.System -> "Follows your phone's light or dark setting."
                ThemeMode.Light -> "Always light."
                ThemeMode.Dark -> "Always dark."
            },
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
        )

        SectionTitle("Units")
        Segmented(Units.entries, state.prefs.units, { if (it == Units.Metric) "Kilometres" else "Miles" }) { onPrefsChange(state.prefs.copy(units = it)) }
    }
}

/** Sync status, data sources, and removing data. Real account only. */
@Composable
fun SyncScreen(
    state: UiState,
    sync: SyncUi,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSyncNow: () -> Unit,
    onConnect: () -> Unit,
    onRetryTest: () -> Unit,
    onDeleteData: () -> Unit,
    onSignOut: () -> Unit,
    fasterData: @Composable () -> Unit,
) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }

    DetailPage("Sync and data", contentPadding, onBack) {
        PulseCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    sync.needsSignIn -> StatusPill("Sign in needed", scheme.error, scheme.surfaceContainerHigh)
                    sync.error != null -> StatusPill("Sync problem", scheme.error, scheme.surfaceContainerHigh)
                    sync.running -> StatusPill("Syncing", muted, scheme.surfaceContainerHigh)
                    sync.firstSyncPending -> StatusPill("Waiting for first sync", muted, scheme.surfaceContainerHigh)
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
            sync.historyLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
            sync.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.error) }
            InfoRow("Last sync", sync.lastSuccessLabel)
            InfoRow("Newest band data", sync.lastTrackerLabel)
            InfoRow("Automatic sync", "about every 15 minutes")
            Text("Pulse can only show what your band has already synced to the Google Health app.", style = MaterialTheme.typography.bodyMedium, color = muted)
        }

        fasterData()

        SectionTitle("Account")
        PulseCard {
            Text("Google account connected", style = MaterialTheme.typography.titleMedium)
            state.accountName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "Read-only access" + ((state.auth as? AuthState.SignedIn)?.grantedScopes?.let { " · $it of 5 permissions" } ?: ""),
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
                    Text(t.message, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetryTest) { Text("Check again") }
                        TextButton(onClick = onConnect) { Text("Sign in again") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { confirm = "delete" }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Delete data stored on this phone")
        }
        OutlinedButton(
            onClick = { confirm = "signout" },
            enabled = !state.signingOut,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (state.signingOut) "Signing out…" else "Sign out and disconnect", color = scheme.error)
        }
        Text(
            "Signing out also removes Pulse's permission at Google. You can check this any time at myaccount.google.com → Security → Your connections to third-party apps.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
    }

    when (confirm) {
        "delete" -> ConfirmDialog(
            title = "Delete local data?",
            body = "Removes every health record Pulse has saved on this phone. Your data in Google Health is not touched. Pulse downloads it again at the next sync. Your training stays.",
            action = "Delete",
            onConfirm = { confirm = null; onDeleteData() },
            onDismiss = { confirm = null },
        )
        "signout" -> ConfirmDialog(
            title = "Sign out?",
            body = "Pulse stops syncing, deletes the health data it saved on this phone, and gives up its permission at Google. Your data in Google Health is not touched. Your training stays on this phone.",
            action = "Sign out",
            onConfirm = { confirm = null; onSignOut() },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
