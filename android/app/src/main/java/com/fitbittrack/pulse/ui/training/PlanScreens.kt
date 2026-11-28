package com.fitbittrack.pulse.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.training.Effort
import com.fitbittrack.pulse.training.EnrollState
import com.fitbittrack.pulse.training.EnrollmentEntity
import com.fitbittrack.pulse.training.ExerciseEntity
import com.fitbittrack.pulse.training.Growth
import com.fitbittrack.pulse.training.LoadKind
import com.fitbittrack.pulse.training.PlanDay
import com.fitbittrack.pulse.training.PlanDoc
import com.fitbittrack.pulse.training.PlanEdits
import com.fitbittrack.pulse.training.ProgramEntity
import com.fitbittrack.pulse.training.ProgressRules
import com.fitbittrack.pulse.training.ScheduleRules
import com.fitbittrack.pulse.training.Scope
import com.fitbittrack.pulse.training.Slot
import com.fitbittrack.pulse.training.Track
import com.fitbittrack.pulse.training.WeightUnit
import com.fitbittrack.pulse.training.Weights
import com.fitbittrack.pulse.training.days
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.components.Space
import com.fitbittrack.pulse.ui.screens.NumberStepper
import com.fitbittrack.pulse.ui.screens.SwitchRow
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle

@Composable
fun PlansScreen(
    programs: List<ProgramEntity>, plan: PlanOn?, enrollments: List<EnrollmentEntity>, contentPadding: PaddingValues,
    onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit,
) {
    val c = PulseTheme.colors
    val locale = locale()
    val on = plan?.program
    val ended = enrollments.filter { it.state == EnrollState.Finished }.map { it.programId }.toSet()
    DetailPage("My plans", contentPadding, onBack, actions = { IconAct(PulseIcons.Plus, "New plan", onClick = onNew) }) {
        if (on != null) {
            SectionTitle("On now")
            PulseCard(onClick = { onOpen(on.id) }) {
                Column {
                    Text(on.title, style = MaterialTheme.typography.titleLarge)
                    Hint("${words(on.daysPerWeek, "day")} a week, week ${plan.week + 1} of ${plan.doc.weeks.size}")
                }
                val pct = ProgressRules.percent(plan.done, plan.planned.size)
                ProgressBar(pct / 100f, c.active, height = 10)
                InfoRow("${plan.done} of ${plan.planned.size} done" + if (plan.skipped > 0) ", ${plan.skipped} skipped" else "", "$pct%")
            }
        }
        val saved = programs.filter { it.id != on?.id }
        if (saved.isNotEmpty()) {
            SectionTitle("Saved")
            RowGroup(saved.map { p ->
                {
                    val last = enrollments.firstOrNull { it.programId == p.id && it.endedAtMs != null }?.endedAtMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                    ListRow(
                        if (p.id in ended) PulseIcons.Check else PulseIcons.Note,
                        if (p.id in ended) c.good else c.activeLabel,
                        p.title,
                        "${words(p.daysPerWeek, "day")} a week" + if (p.id in ended && last != null) ", finished ${last.short(locale)}" else "",
                        onClick = { onOpen(p.id) },
                    )
                }
            })
        }
        if (programs.isEmpty()) Hint("You have no plans yet. Make one, then fill each day with exercises.")
        MainButton("Make a new plan", onClick = onNew)
    }
}

@Composable
internal fun WeekdayPicker(days: Set<DayOfWeek>, first: DayOfWeek, onChange: (Set<DayOfWeek>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val locale = locale()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (0L..6L).map { first.plus(it) }.forEach { d ->
            val on = d in days
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (on) scheme.primary else scheme.surfaceContainerHigh)
                    .clickable(role = Role.Checkbox) { onChange(if (on) days - d else days + d) }
                    .semantics(mergeDescendants = true) { contentDescription = d.getDisplayName(TextStyle.FULL, locale); selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(
                d.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) scheme.onPrimary else scheme.onSurface,
            ) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateSheet(start: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

@Composable
fun NewPlanScreen(first: DayOfWeek, contentPadding: PaddingValues, onBack: () -> Unit, onMake: (String, Int, Set<DayOfWeek>, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var weeks by rememberSaveable { mutableIntStateOf(8) }
    var days by rememberSaveable { mutableStateOf("1,3,5") }
    var easy by rememberSaveable { mutableStateOf(true) }
    val set = days.split(",").mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet()
    DetailPage("New plan", contentPadding, onBack) {
        PulseCard { TextBox(name, "Plan name", { name = it }) }
        RowGroup(listOf(
            { NumberStepper("How long", words(weeks, "week"), weeks, 1, 1..26) { weeks = it } },
            {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint("Training days")
                    WeekdayPicker(set, first) { d -> days = d.map { it.value }.sorted().joinToString(",") }
                }
            },
            { SwitchRow("Easy week", "Every 4th week at 90% of the weight", easy) { easy = it } },
        ), dividerInset = 16)
        MainButton("Make ${words(weeks, "week")}", enabled = name.isNotBlank() && set.isNotEmpty()) { onMake(name, weeks, set, easy) }
        Hint(if (set.isEmpty()) "Choose at least one training day." else "You fill each day in the next step. Nothing is final.")
    }
}

@Composable
fun PlanScreen(
    program: ProgramEntity,
    doc: PlanDoc,
    plan: PlanOn?,
    first: DayOfWeek,
    today: LocalDate,
    lastDays: Set<DayOfWeek>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDay: (Int, Int) -> Unit,
    onSave: (PlanDoc) -> Unit,
    newId: () -> String,
    onStart: (LocalDate, Set<DayOfWeek>) -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onRestart: (Int) -> Unit,
    onRename: (String) -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = PulseTheme.colors
    val locale = locale()
    val on = plan != null
    var week by rememberSaveable { mutableIntStateOf(plan?.week ?: 0) }
    val w = week.coerceIn(doc.weeks.indices)
    var ask by remember { mutableStateOf<String?>(null) }
    val days = doc.weeks[w].days
    DetailPage(program.title, contentPadding, onBack, actions = {
        MoreMenu("More", buildList {
            add(Act("Rename") { ask = "rename" })
            add(Act("Make a copy") { onCopy() })
            if (on) {
                add(Act("Pause") { onPause() })
                add(Act("Start again from week ${w + 1}") { ask = "restart" })
                add(Act("Stop this plan", danger = true) { ask = "stop" })
            }
            add(Act("Delete plan", danger = true) { ask = "delete" })
        })
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            doc.weeks.forEachIndexed { i, x -> FilterChip((if (i == w) "Week ${i + 1}" else "${i + 1}") + if (x.easy) ", easy" else "", i == w) { week = i } }
        }
        RowGroup(days.mapIndexed { d, day ->
            {
                val date = plan?.planned?.firstOrNull { it.week == w && it.day == d }?.let { LocalDate.parse(it.date).tiny(locale) }
                val what = if (day.slots.isEmpty()) "No exercises yet" else "${words(day.slots.size, "exercise")}, ${words(day.sets, "set")}"
                ListRow(PulseIcons.Gym, c.activeLabel, day.name, listOfNotNull(date, what).joinToString(", "), onClick = { onDay(w, d) })
            }
        })
        SideButton("Add a day", icon = PulseIcons.Plus) { ask = "addDay" }
        PulseCard {
            CardHeader("Week ${w + 1}", words(days.sumOf { it.sets }, "set"))
            InfoRow("Training days", "${days.size}")
            InfoRow("Exercises", "${days.sumOf { it.slots.size }}")
            InfoRow("Time", "about ${days.sumOf { it.minutes }.let { m -> if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }}")
        }
        RowGroup(
            listOf({ SwitchRow("Easy week", "This week at 90% of the weight", doc.weeks[w].easy) { onSave(PlanEdits.toggleEasy(doc, w)) } }),
            dividerInset = 16,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
            SideButton("Copy week", Modifier.weight(1f), PulseIcons.Copy) { onSave(PlanEdits.copyWeek(doc, w, newId)); week = w + 1 }
            SideButton("Delete week", Modifier.weight(1f), PulseIcons.Close, enabled = doc.weeks.size > 1) { ask = "deleteWeek" }
        }
        if (!on) MainButton("Start this plan") { ask = "start" }
    }
    when (ask) {
        "rename" -> AskText("Rename plan", program.title, "Save", { ask = null }) { onRename(it); ask = null }
        "addDay" -> AskText(
            "Add a day from week ${w + 1} on",
            "Day ${days.size + 1}",
            "Add",
            { ask = null },
        ) { onSave(PlanEdits.addDay(doc, w, it, newId)); ask = null }
        "stop" -> Ask("Stop this plan?", "Open workouts leave the calendar. Finished ones stay.", "Stop", { ask = null }) { onStop(); ask = null }
        "delete" -> Ask(
            "Delete this plan?",
            if (on) "It stops and leaves your plans. Finished workouts stay." else "It leaves your plans. Finished workouts stay.",
            "Delete",
            { ask = null },
        ) { ask = null; onRemove() }
        "deleteWeek" -> Ask(
            "Delete week ${w + 1}?",
            "Later weeks move one place forward.",
            "Delete",
            { ask = null },
        ) { onSave(PlanEdits.removeWeek(doc, w)); week = (w - 1).coerceAtLeast(0); ask = null }
        "restart" -> Ask(
            "Start again from week ${w + 1}?",
            "Open workouts get new dates from today on. Finished ones stay.",
            "Start again",
            { ask = null },
            danger = false,
        ) { onRestart(w); ask = null }
        "start" -> StartSheet(program, doc, today, first, lastDays, { ask = null }) { s, d -> ask = null; onStart(s, d) }
    }
}

@Composable
private fun StartSheet(
    program: ProgramEntity,
    doc: PlanDoc,
    today: LocalDate,
    first: DayOfWeek,
    lastDays: Set<DayOfWeek>,
    onDismiss: () -> Unit,
    onStart: (LocalDate, Set<DayOfWeek>) -> Unit,
) {
    val locale = locale()
    var start by remember { mutableStateOf(today) }
    var days by remember { mutableStateOf(lastDays.ifEmpty { listOf(
        setOf(1),
        setOf(1),
        setOf(1, 4),
        setOf(1, 3, 5),
        setOf(1, 2, 4, 5),
        setOf(1, 2, 3, 5, 6),
        setOf(1, 2, 3, 4, 5, 6),
        setOf(1, 2, 3, 4, 5, 6, 7),
    )[program.daysPerWeek.coerceIn(0, 7)].map(DayOfWeek::of).toSet() }) }
    var pick by remember { mutableStateOf(false) }
    val n = doc.weeks.sumOf { it.days.size }
    val end = if (days.isEmpty()) null else ScheduleRules.dates(n, start, days).lastOrNull()
    PulseSheet(onDismiss) {
        Text("Start ${program.title}", style = MaterialTheme.typography.titleLarge)
        RowGroup(listOf({ ListRow(
            PulseIcons.Calendar,
            PulseTheme.colors.sleepLabel,
            "First day",
            "Tap to change",
            start.near(today, locale),
            onClick = { pick = true },
        ) }))
        PulseCard {
            Hint("Training days")
            WeekdayPicker(days, first) { days = it }
        }
        Hint(if (end == null) "Choose at least one training day." else "${words(n, "workout")}. The last one is ${end.short(locale)}.")
        MainButton("Start plan", enabled = days.isNotEmpty() && n > 0) { onStart(start, days) }
    }
    if (pick) DateSheet(start, { pick = false }) { start = it; pick = false }
}

/** Changes one day of a plan. Nothing is saved until the user says how far the change goes. */
@Composable
fun PlanDayScreen(
    day: PlanDay,
    week: Int,
    weeks: Int,
    exercises: Map<String, ExerciseEntity>,
    unit: WeightUnit,
    contentPadding: PaddingValues,
    dirty: Boolean,
    onBack: () -> Unit,
    onChange: (PlanDay) -> Unit,
    onAdd: () -> Unit,
    onSwap: (String) -> Unit,
    onSave: (Scope) -> Unit,
    canRemove: Boolean,
    onRemoveDay: () -> Unit,
) {
    var slot by rememberSaveable { mutableStateOf<String?>(null) }
    var ask by rememberSaveable { mutableStateOf<String?>(null) }
    val leave = { if (dirty) ask = "leave" else onBack() }
    androidx.activity.compose.BackHandler(enabled = dirty || slot != null) { if (slot != null) slot = null else ask = "leave" }

    val open = day.slots.firstOrNull { it.id == slot }
    if (open != null) {
        SlotScreen(
            open,
            exercises[open.exerciseId],
            unit,
            contentPadding,
            onBack = { slot = null },
            onSwap = { onSwap(open.id) },
        ) { s -> onChange(day.copy(slots = day.slots.map { if (it.id == s.id) s else it })) }
        return
    }
    DetailPage(day.name, contentPadding, onBack = leave, actions = {
        MoreMenu("More", buildList {
            add(Act("Rename day") { ask = "rename" })
            if (canRemove) add(Act("Delete day from week ${week + 1} on", danger = true) { ask = "delete" })
        })
    }) {
        if (day.slots.isEmpty()) Hint("Add the first exercise of this day.")
        else RowGroup(day.slots.mapIndexed { i, s ->
            {
                ListRow(
                    if (s.superset != null) PulseIcons.Link else PulseIcons.Gym,
                    PulseTheme.colors.activeLabel,
                    exercises[s.exerciseId]?.name ?: "Exercise",
                    (if (s.superset != null) "Superset ${s.superset}, " else "") + slotText(s, exercises[s.exerciseId]),
                    onClick = { slot = s.id },
                    trailing = {
                        MoreMenu("More for ${exercises[s.exerciseId]?.name ?: "exercise"}", buildList {
                            if (i > 0) add(Act("Move up") { onChange(PlanEdits.move(day, i, i - 1)) })
                            if (i < day.slots.lastIndex) add(Act("Move down") { onChange(PlanEdits.move(day, i, i + 1)) })
                            if (s.superset != null) add(Act("Take out of superset") { onChange(PlanEdits.toggleSuperset(day, i)) })
                            else if (i < day.slots.lastIndex) add(Act("Superset with the next") { onChange(PlanEdits.toggleSuperset(day, i)) })
                            add(Act("Swap exercise") { onSwap(s.id) })
                            add(Act("Remove", danger = true) { onChange(day.copy(slots = PlanEdits.tidy(day.slots.filter { it.id != s.id }))) })
                        })
                    },
                )
            }
        })
        if (day.slots.any { it.superset != null }) Hint("Superset: do the exercises back to back, then rest.")
        SideButton("Add exercise", icon = PulseIcons.Plus, onClick = onAdd)
        if (day.slots.isNotEmpty()) PulseCard {
            InfoRow("Sets", "${day.sets}")
            InfoRow("Time", "about ${day.minutes} min")
        }
        if (dirty) MainButton("Save changes") { if (weeks == 1) onSave(Scope.Whole) else ask = "scope" }
    }
    when (ask) {
        "rename" -> AskText("Rename day", day.name, "OK", { ask = null }) { onChange(day.copy(name = it)); ask = null }
        "delete" -> Ask("Delete this day?", "It is taken out of week ${week + 1} and all later weeks.", "Delete", { ask = null }) { ask = null; onRemoveDay() }
        "leave" -> Ask("Leave without saving?", "Your changes to this day are lost.", "Leave", { ask = null }, no = "Stay") { ask = null; onBack() }
        "scope" -> ScopeSheet(week, weeks, { ask = null }) { ask = null; onSave(it) }
    }
}

@Composable
private fun ScopeSheet(week: Int, weeks: Int, onDismiss: () -> Unit, onPick: (Scope) -> Unit) {
    var how by remember { mutableStateOf(Scope.FromNow) }
    PulseSheet(onDismiss) {
        Text("How far should this change go?", style = MaterialTheme.typography.titleLarge)
        RowGroup(buildList {
            add { RadioRow("Only week ${week + 1}", "All other weeks stay as they are", how == Scope.ThisWeek) { how = Scope.ThisWeek } }
            if (week < weeks - 1) add { RadioRow(
                if (week == 0) "All weeks" else "From now on",
                if (week == 0) "Weeks 1 to $weeks" else "Weeks ${week + 1} to $weeks. Earlier weeks stay as they were.",
                how == Scope.FromNow,
            ) { how = Scope.FromNow } }
            if (week > 0) add { RadioRow("The whole plan", "Earlier weeks too, for the next time you start it", how == Scope.Whole) { how = Scope.Whole } }
        })
        Hint("Workouts you already did never change.")
        val pick = if (how == Scope.FromNow && week == weeks - 1) Scope.ThisWeek else how
        val n = when (pick) { Scope.ThisWeek -> 1; Scope.FromNow -> weeks - week; Scope.Whole -> weeks }
        MainButton("Change ${words(n, "week")}") { onPick(pick) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = onDismiss) { Text("Cancel") } }
    }
}

internal fun slotText(s: Slot, e: ExerciseEntity?): String {
    val reps = if (s.repsMin == s.repsMax) "${s.repsMin}" else "${s.repsMin}–${s.repsMax}"
    val sets = words(s.working + s.drops, "set")
    val unit = if (e?.track == Track.Time) "seconds" else "reps"
    val rest = if (s.restSec > 0) ", rest ${clock(s.restSec)}" else ""
    return "$sets, $reps $unit$rest"
}

@Composable
private fun SlotScreen(
    s: Slot,
    e: ExerciseEntity?,
    unit: WeightUnit,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSwap: () -> Unit,
    onChange: (Slot) -> Unit,
) {
    val time = e?.track == Track.Time
    val weight = e?.track == Track.WeightReps
    val unitStep = if (time) 5 else 1
    DetailPage(e?.name ?: "Exercise", contentPadding, onBack, actions = { TextButton(onClick = onSwap) { Text("Swap") } }) {
        SectionTitle("Sets")
        RowGroup(buildList {
            add { NumberStepper("Warm-up sets", "${s.warmups}", s.warmups, 1, 0..6) { onChange(s.copy(warmups = it)) } }
            add { NumberStepper("Working sets", "${s.working}", s.working, 1, 1..12) { onChange(s.copy(working = it)) } }
            if (weight) add { NumberStepper("Drop sets", "${s.drops}", s.drops, 1, 0..4) { onChange(s.copy(drops = it)) } }
            add { NumberStepper(
                if (time) "Shortest time" else "Fewest reps",
                "${s.repsMin}" + if (time) " seconds" else "",
                s.repsMin,
                unitStep,
                1..(if (time) 3600 else 100),
            ) { onChange(s.copy(repsMin = it, repsMax = maxOf(it, s.repsMax))) } }
            add { NumberStepper(
                if (time) "Longest time" else "Most reps",
                "${s.repsMax}" + if (time) " seconds" else "",
                s.repsMax,
                unitStep,
                1..(if (time) 3600 else 100),
            ) { onChange(s.copy(repsMax = it, repsMin = minOf(it, s.repsMin))) } }
            add { NumberStepper("Rest", clock(s.restSec) + " min", s.restSec, 15, 0..600) { onChange(s.copy(restSec = it)) } }
            if (!time) add { SwitchRow("Last set: as many as you can", "Also called AMRAP", s.lastMax) { onChange(s.copy(lastMax = it)) } }
        }, dividerInset = 16)
        if (weight) {
            SectionTitle("How heavy")
            Segmented(
                LoadKind.entries,
                s.load,
                { it.label },
            ) { k -> onChange(s.copy(load = k, loadValue = when (k) { LoadKind.Effort -> 80; LoadKind.Percent -> 75; LoadKind.Fixed -> 20_000 })) }
            val step = e?.stepGrams ?: 2500
            RowGroup(listOf({
                when (s.load) {
                    LoadKind.Effort -> NumberStepper(
                        "Effort (RPE)",
                        "${Weights.plain(s.loadValue / 10.0)}, so ${Effort.about(s.loadValue)}",
                        s.loadValue,
                        5,
                        60..100,
                    ) { onChange(s.copy(loadValue = it)) }
                    LoadKind.Percent -> NumberStepper("Part of your best lift", "${s.loadValue}%", s.loadValue, 5, 30..100) { onChange(s.copy(loadValue = it)) }
                    LoadKind.Fixed -> NumberStepper(
                        "Weight",
                        Weights.text(s.loadValue, unit),
                        s.loadValue,
                        step,
                        0..Weights.grams(500.0, WeightUnit.Kg),
                    ) { onChange(s.copy(loadValue = it)) }
                }
            }), dividerInset = 16)
            if (s.load != LoadKind.Percent) {
                SectionTitle("How it grows")
                RowGroup(Growth.entries.map { g -> { RadioRow(
                    g.label,
                    if (g == Growth.FillReps) "When every set reaches ${s.repsMax}, add ${Weights.text(step, unit)}" else if (g == Growth.AddWeekly) "When every set reaches ${s.repsMin}, add ${Weights.text(step, unit)}" else g.about,
                    s.growth == g,
                ) { onChange(s.copy(growth = g)) } } })
            }
        }
        MainButton("Done", onClick = onBack)
    }
}
