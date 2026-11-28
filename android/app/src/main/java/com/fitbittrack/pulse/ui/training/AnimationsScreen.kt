package com.fitbittrack.pulse.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import com.fitbittrack.pulse.training.EdbEntity
import com.fitbittrack.pulse.training.EdbRules
import com.fitbittrack.pulse.training.EdbStatus
import com.fitbittrack.pulse.training.ExerciseEntity
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.Space
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal fun megabytes(bytes: Long): String = if (bytes < 1_000_000) "${(bytes / 1000).coerceAtLeast(if (bytes > 0) 1 else 0)} KB" else "%.0f MB".format(Locale.US, bytes / 1_000_000.0)

/** Settings, Training, Exercise animations: what is saved, download all, close names, credits. */
@Composable
fun AnimationsScreen(
    status: EdbStatus,
    close: List<Pair<ExerciseEntity, EdbEntity>>?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSaveAll: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onTryAgain: () -> Unit,
    onAnswer: (ExerciseEntity, EdbEntity, Boolean) -> Unit,
) {
    val c = PulseTheme.colors
    val locale = locale()
    var ask by remember { mutableStateOf(false) }
    DetailPage("Exercise animations", contentPadding, onBack) {
        PulseCard {
            Hint("Saved on this phone")
            BigValue("%,d".format(status.saved), "of " + "%,d".format(if (status.listed > 0) status.listed else 1500), 40)
            if (status.listed > 0) LinearProgressIndicator(progress = { status.saved / status.listed.toFloat() }, modifier = Modifier.fillMaxWidth())
            InfoRow("Space used", megabytes(status.bytes))
            InfoRow("Exercises from ExerciseDB", if (status.listed == 0) "Not yet" else "%,d".format(status.listed))
            status.busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.activeLabel) }
            status.all?.let { (done, all) -> Text("Downloading: %,d of %,d".format(done, all), style = MaterialTheme.typography.bodyMedium, color = c.activeLabel) }
        }
        Hint("Pulse saves an animation when you open an exercise, before a planned workout, and for your plan and favourites on Wi-Fi.")
        val running = status.all != null || status.busy == "Waiting for Wi-Fi…"
        if (running) SideButton("Stop downloading", onClick = onStop)
        else MainButton(
            "Download all (about ${megabytes(((if (status.listed > 0) status.listed else 1500) - status.saved).coerceAtLeast(0) * 124_000L)}, Wi-Fi only)",
            enabled = status.listed == 0 || status.saved < status.listed,
            onClick = onSaveAll,
        )
        if (status.problem != null || status.pausedUntilMs > System.currentTimeMillis()) PulseCard {
            Text("ExerciseDB is resting", style = MaterialTheme.typography.titleMedium)
            Hint(
                (status.problem ?: "ExerciseDB was busy.") + if (status.pausedUntilMs > System.currentTimeMillis()) {
                    " Pulse tries again " + Instant.ofEpochMilli(status.pausedUntilMs).atZone(ZoneId.systemDefault()).toLocalDate().short(locale) + "."
                } else "",
            )
            SideButton("Try again now", onClick = onTryAgain)
        }
        if (!close.isNullOrEmpty()) {
            SectionTitle("Same exercise? (${close.size})")
            Hint("These names are close. Say yes, and your exercise gets the animation and steps. Your workouts stay as they are.")
            close.take(30).forEach { (p, e) ->
                PulseCard {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Hint("ExerciseDB: ${EdbRules.title(e.name)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        SideButton("Not the same", Modifier.weight(1f)) { onAnswer(p, e, false) }
                        MainButton("Same", Modifier.weight(1f)) { onAnswer(p, e, true) }
                    }
                }
            }
        }
        SectionTitle("Saved animations")
        RowGroup(listOf({ ListRow(PulseIcons.Close, MaterialTheme.colorScheme.error, "Delete all saved animations", "They download again when needed", onClick = { ask = true }) }))
        SectionTitle("Sources")
        PulseCard {
            Text(EdbRules.CREDIT, style = MaterialTheme.typography.titleSmall)
            Hint("Free version, for personal use only. oss.exercisedb.dev")
            Text("Other exercises and steps: free-exercise-db (public domain)", style = MaterialTheme.typography.titleSmall)
        }
    }
    if (ask) Ask("Delete all saved animations?", "They take ${megabytes(status.bytes)}. Pulse downloads them again when you open an exercise.", "Delete", { ask = false }) { ask = false; onDelete() }
}
