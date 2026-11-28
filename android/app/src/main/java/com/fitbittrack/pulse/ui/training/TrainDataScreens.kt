package com.fitbittrack.pulse.ui.training

import android.app.TimePickerDialog
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.fitbittrack.pulse.training.EffortMode
import com.fitbittrack.pulse.training.ExerciseEntity
import com.fitbittrack.pulse.training.FileUnit
import com.fitbittrack.pulse.training.ImportBatchEntity
import com.fitbittrack.pulse.training.TrainingHealth
import com.fitbittrack.pulse.training.TrainingSettings
import com.fitbittrack.pulse.training.WeightUnit
import com.fitbittrack.pulse.training.Weights
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.screens.NumberStepper
import com.fitbittrack.pulse.ui.screens.SwitchRow
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ---------- import ----------

@Composable
fun ImportScreen(
    step: ImportStep,
    batches: List<ImportBatchEntity>,
    exercises: Map<String, ExerciseEntity>,
    contentPadding: PaddingValues,
    act: ImportActions,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(act.file) }
    val back = { if (step is ImportStep.Choose || step is ImportStep.Saved || step is ImportStep.Reading) onBack() else act.back() }
    BackHandler(enabled = step !is ImportStep.Choose && step !is ImportStep.Saved && step !is ImportStep.Reading) { act.back() }
    DetailPage(
        when (step) {
            is ImportStep.Unit -> "Is this right?"
            is ImportStep.Names -> "Match exercises"
            is ImportStep.Check -> "Ready to save"
            is ImportStep.Saved -> "Saved"
            else -> "Bring in old workouts"
        },
        contentPadding, onBack = back,
    ) {
        when (step) {
            is ImportStep.Choose, is ImportStep.Failed -> Choose(
                step as? ImportStep.Failed,
                batches,
                act,
            ) { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
            ImportStep.Reading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ImportStep.Unit -> UnitStep(step, act)
            is ImportStep.Names -> NamesStep(step, exercises, act)
            is ImportStep.Check -> CheckStep(step, act)
            is ImportStep.Saved -> {
                TileRow {
                    MetricTile("Workouts", PulseTheme.colors.activeLabel, Fmt.count(step.workouts), null, Modifier.weight(1f))
                    MetricTile("Sets", PulseTheme.colors.activeLabel, Fmt.count(step.sets), null, Modifier.weight(1f))
                }
                if (step.already > 0) Hint("${words(step.already, "workout")} were already here and were not added again.")
                MainButton("See all workouts", onClick = onDone)
            }
        }
    }
}

class ImportActions(
    val file: (Uri) -> Unit,
    val back: () -> Unit,
    val another: () -> Unit,
    val unit: (FileUnit) -> Unit,
    val choose: (String, String?) -> Unit,
    val pick: (String) -> Unit,
    val guesses: () -> Unit,
    val check: () -> Unit,
    val save: () -> Unit,
    val undo: (String) -> Unit,
)

@Composable
private fun Choose(failed: ImportStep.Failed?, batches: List<ImportBatchEntity>, act: ImportActions, onPick: () -> Unit) {
    val locale = locale()
    var ask by remember { mutableStateOf<ImportBatchEntity?>(null) }
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Choose your file", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Pulse only reads the file. It never signs in to Boostcamp and never asks for that password.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (failed != null) PulseCard { Text(failed.message, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) }
    MainButton("Choose file", icon = PulseIcons.File, onClick = onPick)
    PulseCard { listOf("Choose the file", "Check kg or lb", "Match exercises", "Look, then save").forEachIndexed { i, t -> StepLine(i, t) } }
    Hint("The file is the Response of the history request on boostcamp.app/history, saved as it is. Boostcamp's terms do not clearly allow this, so it is your choice.")
    if (batches.isNotEmpty()) {
        SectionTitle("Brought in before")
        RowGroup(batches.map { b ->
            { ListRow(
                PulseIcons.Undo,
                PulseTheme.colors.bodyLabel,
                words(b.workouts, "workout"),
                "${Instant.ofEpochMilli(b.atMs).atZone(ZoneId.systemDefault()).toLocalDate().short(locale)}. Tap to undo.",
                onClick = { ask = b },
            ) }
        })
    }
    ask?.let { b -> Ask(
        "Undo this import?",
        "Its ${words(b.workouts, "workout")} leave your history. Workouts you logged in Pulse stay.",
        "Undo",
        { ask = null },
    ) { act.undo(b.id); ask = null } }
}

@Composable
private fun StepLine(i: Int, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(if (i == 0) scheme.primary else scheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = if (i == 0) scheme.onPrimary else scheme.onSurfaceVariant)
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (i == 0) scheme.onSurface else scheme.onSurfaceVariant)
    }
}

@Composable
private fun UnitStep(s: ImportStep.Unit, act: ImportActions) {
    val locale = locale()
    var pick by remember(s.sample) { mutableStateOf<FileUnit?>(null) }
    val sample = s.draft.sample(s.sample)
    if (sample == null) {
        Hint("This file has no weights, so there is nothing to check.")
        MainButton("Next") { act.unit(FileUnit.Kilos) }
        return
    }
    val (w, e, set) = sample
    val raw = set.weight ?: 0.0
    PulseCard {
        Hint("From your file")
        Text("${e.name}, ${w.date.short(locale)} ${w.date.year}", style = MaterialTheme.typography.titleLarge)
        InfoRow("Reps", "${set.reps ?: 0}")
        InfoRow("The file says", Weights.plain(raw))
    }
    SectionTitle("What did you really lift?")
    val asPounds = Weights.grams(raw, WeightUnit.Lb)
    val asKilos = Weights.grams(raw, WeightUnit.Kg)
    RowGroup(listOf(
        { RadioRow(
            "${Weights.text(asPounds, WeightUnit.Kg)} (${Weights.text(asPounds, WeightUnit.Lb)})",
            "The file is in pounds",
            pick == FileUnit.Pounds,
        ) { pick = FileUnit.Pounds } },
        { RadioRow(
            "${Weights.text(asKilos, WeightUnit.Kg)} (${Weights.text(asKilos, WeightUnit.Lb)})",
            "The file is in kilos",
            pick == FileUnit.Kilos,
        ) { pick = FileUnit.Kilos } },
    ))
    MainButton("Next", enabled = pick != null) { pick?.let(act.unit) }
    SideButton("I do not remember. Show another set", onClick = act.another)
    Hint("A wrong answer makes every weight 2.2 times off.")
}

@Composable
private fun NamesStep(s: ImportStep.Names, exercises: Map<String, ExerciseEntity>, act: ImportActions) {
    val c = PulseTheme.colors
    val all = s.sure.size + s.ask.size
    var ask by remember { mutableStateOf<String?>(null) }
    PulseCard {
        CardHeader("${s.sure.size} of $all matched", if (s.ask.isEmpty()) "Nothing to check" else "${s.ask.size} to check")
        ProgressBar(if (all == 0) 1f else s.sure.size.toFloat() / all, c.active, height = 10)
    }
    if (s.ask.isNotEmpty()) {
        SectionTitle("Please check")
        RowGroup(s.ask.map { e ->
            {
                val id = s.chosen[e.key]
                ListRow(
                    if (id == null) PulseIcons.Plus else PulseIcons.Link,
                    if (id == null) c.activeLabel else c.moveLabel,
                    e.name + (e.equipment?.let { " ($it)" } ?: ""),
                    if (id != null) "Is ${exercises[id]?.name}" else "New exercise" + (s.guess[e.key]?.let { ". Or is it ${exercises[it]?.name}?" } ?: ""),
                    onClick = { ask = e.key },
                )
            }
        })
    }
    val open = s.guess.count { (k, _) -> s.chosen[k] == null }
    if (open > 0) SideButton(if (open == 1) "Use the suggestion" else "Use all $open suggestions", icon = PulseIcons.Link, onClick = act.guesses)
    MainButton("Next", onClick = act.check)
    ask?.let { key ->
        val e = s.ask.firstOrNull { it.key == key }
        PulseSheet({ ask = null }) {
            Text(e?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
            RowGroup(buildList {
                listOfNotNull(
                    s.chosen[key],
                    s.guess[key],
                ).distinct().forEach { id -> add { RadioRow(exercises[id]?.name ?: "Exercise", "It is this one", s.chosen[key] == id) { act.choose(key, id); ask = null } } }
                add { RadioRow("Make a new exercise", "With this name, as your own", s.chosen[key] == null) { act.choose(key, null); ask = null } }
            })
            SideButton("Choose from the list", icon = PulseIcons.Search) { ask = null; act.pick(key) }
        }
    }
}

@Composable
private fun CheckStep(s: ImportStep.Check, act: ImportActions) {
    val c = PulseTheme.colors
    val r = s.rows
    TileRow {
        MetricTile("Workouts", c.activeLabel, Fmt.count(r.sessions.size), null, Modifier.weight(1f))
        MetricTile("Sets", c.activeLabel, Fmt.count(r.sets.size), null, Modifier.weight(1f))
    }
    val inFile = s.draft.volume(s.unit)
    PulseCard {
        CardHeader("Check")
        if (r.already == 0) {
            InfoRow("Weight in the file", "${bigWeight(inFile, WeightUnit.Kg)} kg")
            InfoRow("Weight in Pulse", "${bigWeight(r.volume, WeightUnit.Kg)} kg")
            if (inFile != r.volume) Hint("These should be the same. Please go back.", MaterialTheme.colorScheme.error)
        } else InfoRow("Already here", words(r.already, "workout"))
        InfoRow("New exercises", "${r.newExercises.size}")
        if (s.draft.leftOut.isEmpty()) InfoRow(
            "Left out",
            "Nothing",
        ) else s.draft.leftOut.entries.sortedByDescending { it.value }.forEach { (k, v) -> InfoRow("Left out: $k", "$v") }
    }
    MainButton(
        if (r.sessions.isEmpty()) "Nothing new to save" else "Save ${words(r.sessions.size, "workout")}",
        enabled = r.sessions.isNotEmpty(),
        onClick = act.save,
    )
    Hint("You can undo this import later. It removes only these workouts.")
}

// ---------- backup ----------

@Composable
fun BackupScreen(
    settings: TrainingSettings, note: String?, contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSave: (Uri) -> Unit,
    onRestore: (Uri) -> Unit,
    onSettings: (TrainingSettings) -> Unit,
    onDeleteAll: () -> Unit,
    onNoteSeen: () -> Unit,
) {
    val locale = locale()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(onSave) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked = it }
    var ask by remember { mutableStateOf(false) }
    DetailPage("Backup", contentPadding, onBack) {
        PulseCard {
            Hint("Last copy")
            BigValue(settings.lastBackupMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().short(locale) } ?: "None yet", null)
            Hint("One file with your plans, workouts and records. Keep it somewhere safe.")
            MainButton("Save a copy now", icon = PulseIcons.Save) { onNoteSeen(); save.launch("pulse-training-${LocalDate.now()}.json") }
        }
        if (note != null) PulseCard { Text(note, style = MaterialTheme.typography.titleSmall) }
        RowGroup(listOf(
            { ListRow(
                PulseIcons.Undo,
                PulseTheme.colors.bodyLabel,
                "Put back a copy",
                "Choose a saved file",
                onClick = { onNoteSeen(); open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            ) },
            { SwitchRow("Remind me", "When the last copy is two weeks old", settings.backupReminder) { onSettings(settings.copy(backupReminder = it)) } },
        ))
        SectionTitle("When you sign out")
        Hint("Your training stays on this phone. Signing out of Google only removes the data that came from Google.")
        SideButton("Delete all training", color = MaterialTheme.colorScheme.error) { ask = true }
    }
    picked?.let { uri -> Ask(
        "Put back this copy?",
        "It takes the place of all training on this phone now. Save a copy first if you want to keep that.",
        "Put back",
        { picked = null },
    ) { picked = null; onRestore(uri) } }
    if (ask) Ask(
        "Delete all training?",
        "Every plan, workout and record on this phone is deleted. Only a saved copy can bring it back.",
        "Delete all",
        { ask = false },
    ) { ask = false; onDeleteAll() }
}

// ---------- settings ----------

@Composable
fun TrainSettingsScreen(
    settings: TrainingSettings,
    healthAvailable: Boolean,
    healthAllowed: suspend () -> Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSettings: (TrainingSettings) -> Unit,
    onBackup: () -> Unit,
    onImport: () -> Unit,
    onAnimations: () -> Unit,
    animationsText: String,
    askNotifications: () -> Unit,
) {
    val c = PulseTheme.colors
    val locale = locale()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { allowed = healthAllowed() }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        allowed = TrainingHealth.WRITE in granted
        onSettings(settings.copy(healthConnect = allowed))
    }
    var plates by remember { mutableStateOf(false) }
    DetailPage("Training", contentPadding, onBack) {
        SectionTitle("Weight")
        Segmented(WeightUnit.entries, settings.unit, { it.label }) { u ->
            if (u != settings.unit) onSettings(settings.copy(
                unit = u,
                barGrams = if (u == WeightUnit.Kg) 20_000 else Weights.grams(45.0, u),
                plates = if (u == WeightUnit.Kg) TrainingSettings.KG_PLATES else TrainingSettings.LB_PLATES,
            ))
        }
        SectionTitle("Effort")
        Segmented(EffortMode.entries, settings.effort, { it.label }) { onSettings(settings.copy(effort = it)) }
        Hint(
            when (settings.effort) {
                EffortMode.Rpe -> "10 means nothing left, 8 means 2 more reps were possible."
                EffortMode.RepsLeft -> "How many more reps you could have done."
                EffortMode.Off -> "Sets have no effort."
            },
        )
        SectionTitle("During a workout")
        RowGroup(listOf(
            { SwitchRow("Start rest by itself", "After each tick", settings.autoRest) { onSettings(settings.copy(autoRest = it)) } },
            { SwitchRow("Sound and buzz", "When a rest ends", settings.signal) { onSettings(settings.copy(signal = it)); if (it) askNotifications() } },
            { SwitchRow("Keep screen on", "While the workout is open", settings.keepScreenOn) { onSettings(settings.copy(keepScreenOn = it)) } },
            { ListRow(PulseIcons.Gym, c.moveLabel, "My bar and plates", "${Weights.text(settings.barGrams, settings.unit)} bar", onClick = { plates = true }) },
        ), dividerInset = 16)
        SectionTitle("Reminder")
        RowGroup(listOf(
            { SwitchRow(
                "Workout reminder",
                "On training days",
                settings.reminderOn,
            ) { onSettings(settings.copy(reminderOn = it)); if (it) askNotifications() } },
            {
                ListRow(PulseIcons.Bell, c.energyLabel, "Send at", "Quiet hours still count", Fmt.clock(settings.reminderAt), onClick = {
                    TimePickerDialog(
                        context,
                        { _, h, m -> onSettings(settings.copy(reminderAt = LocalTime.of(h, m))) },
                        settings.reminderAt.hour,
                        settings.reminderAt.minute,
                        DateFormat.is24HourFormat(context),
                    ).show()
                })
            },
        ), dividerInset = 16)
        SectionTitle("Data")
        RowGroup(buildList {
            if (healthAvailable) add {
                SwitchRow(
                    "Save to Health Connect",
                    "Finished workouts: name and time, so other apps see them. Also reads your body weight.",
                    settings.healthConnect && allowed,
                ) { on ->
                    if (!on) onSettings(settings.copy(healthConnect = false)) else if (allowed) onSettings(settings.copy(healthConnect = true)) else ask.launch(TrainingHealth.PERMISSIONS)
                }
            }
            add { ListRow(
                PulseIcons.Save,
                c.bodyLabel,
                "Backup",
                settings.lastBackupMs?.let { "Last copy ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().short(locale)}" } ?: "No copy yet",
                onClick = onBackup,
            ) }
            add { ListRow(PulseIcons.File, c.sleepLabel, "Bring in old workouts", "From a file you saved", onClick = onImport) }
            add { ListRow(PulseIcons.Gym, c.activeLabel, "Exercise animations", animationsText, onClick = onAnimations) }
        }, dividerInset = 16)
    }
    if (plates) PlatesSheet(settings, { plates = false }, onSettings)
}

@Composable
private fun PlatesSheet(settings: TrainingSettings, onDismiss: () -> Unit, onSettings: (TrainingSettings) -> Unit) {
    val kg = settings.unit == WeightUnit.Kg
    val options = if (kg) listOf(
        25.0,
        20.0,
        15.0,
        10.0,
        5.0,
        2.5,
        2.0,
        1.25,
        1.0,
        0.5,
    ).map { Weights.grams(it, WeightUnit.Kg) } else listOf(45.0, 35.0, 25.0, 15.0, 10.0, 5.0, 2.5, 1.25).map { Weights.grams(it, WeightUnit.Lb) }
    val step = if (kg) 500 else Weights.grams(1.0, WeightUnit.Lb)
    PulseSheet(onDismiss) {
        Text("My bar and plates", style = MaterialTheme.typography.titleLarge)
        RowGroup(
            listOf({ NumberStepper("Bar", Weights.text(settings.barGrams, settings.unit), settings.barGrams, step, 0..60_000) { onSettings(settings.copy(barGrams = it)) } }),
            dividerInset = 16,
        )
        Hint("Plates you have, for each side")
        Choices(options, { it in settings.plates }, { Weights.text(it, settings.unit) }) { p ->
            val next = if (p in settings.plates) settings.plates - p else settings.plates + p
            if (next.isNotEmpty()) onSettings(settings.copy(plates = next.sortedDescending()))
        }
        MainButton("Done", onClick = onDismiss)
    }
}
