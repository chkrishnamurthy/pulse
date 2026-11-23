package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.chat.Area
import com.fitbittrack.pulse.chat.ChatPrefs
import com.fitbittrack.pulse.chat.ModelState
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.StatusPill
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** First time: what the AI is, what it costs (nothing), and the download. */
@Composable
fun ChatSetupScreen(
    model: ModelState,
    prefs: ChatPrefs,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    DetailPage("Ask Pulse", contentPadding, onBack) {
        Text("Explanations, free and private", style = MaterialTheme.typography.headlineMedium)
        Text("Google's Gemma AI runs on this phone. No account, no key and no monthly cost.", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
        RowGroup(listOf(
            { ListRow(PulseIcons.Check, c.good, "Your data stays here", "Questions and health numbers never leave the phone.") },
            { ListRow(PulseIcons.Layout, c.sleepLabel, "Works without internet", "After one download, it runs on the phone itself.") },
            { ListRow(PulseIcons.Trends, c.moveLabel, "Real numbers", "Pulse does the maths. The AI only explains it.") },
        ))
        when (model) {
            is ModelState.Downloading, ModelState.Checking -> PulseCard {
                CardHeader(if (model == ModelState.Checking) "Checking" else if ((model as ModelState.Downloading).waiting) "Waiting" else "Downloading", downloadText(model))
                ProgressBar(if (model is ModelState.Downloading) (model.bytes.toDouble() / model.total).toFloat() else 1f, c.good, height = 10)
                Text(
                    if ((model as? ModelState.Downloading)?.waiting == true) "It continues by itself${if (prefs.wifiOnly) " when Wi-Fi is available" else ""}. You can leave this screen."
                    else "You can leave this screen. It carries on, and continues later if the internet stops.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
            }
            ModelState.Ready -> PulseCard {
                StatusPill("Ready", c.good, c.goodContainer)
                Text("The file was checked. Chat needs no internet from now on.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            is ModelState.Unsupported -> PulseCard { Text(model.reason + " Pulse still answers with your exact numbers.", style = MaterialTheme.typography.bodyLarge) }
            is ModelState.Absent -> {
                PulseCard {
                    InfoRow("Download size", "2.6 GB, once")
                    InfoRow("Free space needed", "about 3.1 GB")
                    InfoRow("From", "huggingface.co (Google's Gemma 4 E2B)")
                    InfoRow("Saved in", "Pulse's private storage")
                    model.problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.error) }
                }
                SwitchRow("Use Wi-Fi only", prefs.wifiOnly, onWifiOnly)
            }
        }
        when (model) {
            is ModelState.Absent -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Download AI (2.6 GB)") }
            is ModelState.Downloading, ModelState.Checking -> OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Cancel download") }
            else -> Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start chatting") }
        }
        Text("AI can be wrong. It does not diagnose and is not a doctor. Only the download request leaves your phone.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
fun ChatSettingsScreen(
    model: ModelState,
    prefs: ChatPrefs,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPrefs: (ChatPrefs) -> Unit,
    onSetup: () -> Unit,
    onDeleteModel: () -> Unit,
    onDeleteChats: () -> Unit,
) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    DetailPage("Ask Pulse", contentPadding, onBack) {
        PulseCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gemma 4 E2B", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (model) {
                    ModelState.Ready -> StatusPill("Ready", c.good, c.goodContainer)
                    is ModelState.Downloading, ModelState.Checking -> StatusPill("Downloading", scheme.onSurfaceVariant, scheme.surfaceContainerHigh)
                    else -> StatusPill("Not on this phone", scheme.onSurfaceVariant, scheme.surfaceContainerHigh)
                }
            }
            InfoRow("Uses on this phone", if (model == ModelState.Ready) "2.6 GB" else if (model is ModelState.Downloading) downloadText(model) else "Nothing")
            InfoRow("Runs on", "This phone only")
            if (model == ModelState.Ready) OutlinedButton(onClick = { confirm = "model" }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Delete AI from this phone", color = scheme.error) }
            else if (model !is ModelState.Unsupported) OutlinedButton(onClick = onSetup, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (model is ModelState.Absent) "Add the AI" else "Show download") }
        }

        SectionTitle("What chat may read")
        RowGroup(Area.entries.map { a -> { SwitchRow(a.label, a in prefs.areas, inGroup = true) { on -> onPrefs(prefs.copy(areas = if (on) prefs.areas + a else prefs.areas - a)) } } }, dividerInset = 16)

        SectionTitle("Chats")
        PulseCard {
            SwitchRow("Keep chats on this phone", prefs.keep) { onPrefs(prefs.copy(keep = it)) }
            Text("When off, a chat is gone when you close Pulse. Emergency messages are never kept.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            OutlinedButton(onClick = { confirm = "chats" }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Delete all chats", color = scheme.error) }
        }
        Text("Deleting the AI or your chats does not touch your health data. Signing out deletes your chats.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
    if (confirm != null) AlertDialog(
        onDismissRequest = { confirm = null },
        containerColor = scheme.surfaceContainerHigh,
        title = { Text(if (confirm == "model") "Delete the AI?" else "Delete all chats?") },
        text = { Text(if (confirm == "model") "Frees 2.6 GB. Pulse still answers with your exact numbers. You can download it again at any time." else "Removes every chat kept on this phone. This can't be undone.") },
        confirmButton = { TextButton(onClick = { if (confirm == "model") onDeleteModel() else onDeleteChats(); confirm = null }) { Text("Delete", color = scheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) = SwitchRow(label, on, inGroup = false, onChange = onChange)

@Composable
private fun SwitchRow(label: String, on: Boolean, inGroup: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange).heightIn(min = 56.dp).padding(horizontal = if (inGroup) 16.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}
