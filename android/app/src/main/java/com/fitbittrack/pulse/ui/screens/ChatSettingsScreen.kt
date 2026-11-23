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
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle

@Composable
fun ChatSettingsScreen(
    prefs: ChatPrefs,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPrefs: (ChatPrefs) -> Unit,
    onDeleteChats: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var confirm by rememberSaveable { mutableStateOf(false) }
    DetailPage("Ask Pulse", contentPadding, onBack) {
        Text("Chat answers from the data saved on this phone. Your questions and numbers are not sent anywhere.", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)

        SectionTitle("What chat may read")
        RowGroup(Area.entries.map { a -> { SwitchRow(a.label, a in prefs.areas, inGroup = true) { on -> onPrefs(prefs.copy(areas = if (on) prefs.areas + a else prefs.areas - a)) } } }, dividerInset = 16)

        SectionTitle("Chats")
        PulseCard {
            SwitchRow("Keep chats on this phone", prefs.keep) { onPrefs(prefs.copy(keep = it)) }
            Text("When off, a chat is gone when you close Pulse. Emergency messages are never kept.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            OutlinedButton(onClick = { confirm = true }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Delete all chats", color = scheme.error) }
        }
        Text("Deleting your chats does not touch your health data. Signing out deletes your chats.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = scheme.surfaceContainerHigh,
        title = { Text("Delete all chats?") },
        text = { Text("Removes every chat kept on this phone. This can't be undone.") },
        confirmButton = { TextButton(onClick = { onDeleteChats(); confirm = false }) { Text("Delete", color = scheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
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
