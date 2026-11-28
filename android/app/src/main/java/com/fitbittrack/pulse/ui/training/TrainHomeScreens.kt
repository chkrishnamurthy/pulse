package com.fitbittrack.pulse.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.training.PlannedEntity
import com.fitbittrack.pulse.training.SlotPreview
import com.fitbittrack.pulse.training.Track
import com.fitbittrack.pulse.training.TrainingSettings
import com.fitbittrack.pulse.training.Weights
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.CardShape
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.FitText
import com.fitbittrack.pulse.ui.components.IconBadge
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalDate
import java.time.format.TextStyle

/** The card on Today. Small, because Today shows the whole day on one screen. */
@Composable
fun TrainTodayCard(home: TrainHome?, onOpen: () -> Unit, onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val next = home?.plan?.next
    val day = next?.let { LocalDate.parse(it.date) }
    val (title, note) = when {
        home == null -> "Training" to " "
        home.active != null -> home.active.title to "Workout is open"
        next == null || day == null -> "Training" to (if (home.empty) "Make a plan or start a workout" else "No workout planned")
        day <= home.today -> (if (day == home.today) "${next.title} today" else "${next.title}, missed ${day.tiny(locale())}") to
            (home.plan.day(next)?.takeIf { it.slots.isNotEmpty() }?.let { "${words(it.slots.size, "exercise")}, about ${it.minutes} min" } ?: "No exercises yet")
        else -> "Next: ${next.title}" to day.near(home.today, locale())
    }
    val action = when {
        home?.active != null -> "Go on"
        home != null && next != null && day != null && day <= home.today -> "Start"
        else -> null
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(CardShape)
            .background(scheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = "Open training", onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = if (action == null) 16.dp else 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(PulseIcons.Gym, PulseTheme.colors.activeLabel)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) Button(
            onClick = onStart,
            contentPadding = PaddingValues(horizontal = 18.dp),
            modifier = Modifier.heightIn(min = 40.dp),
        ) { Text(action) }
        else Icon(PulseIcons.Forward, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun TrainScreen(
    home: TrainHome, contentPadding: PaddingValues, onBack: () -> Unit, onBefore: (PlannedEntity) -> Unit, onLive: () -> Unit, onEmpty: () -> Unit,
    onPlans: () -> Unit,
    onNewPlan: () -> Unit,
    onSchedule: () -> Unit,
    onExercises: () -> Unit,
    onProgress: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = locale()
    DetailPage("Train", contentPadding, onBack, actions = {
        MoreMenu("More", listOf(Act("Settings") { onSettings() }, Act("Backup") { onBackup() }, Act("Bring in old workouts") { onImport() }))
    }) {
        if (home.empty) {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Start your training", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Plans, workouts and progress. Everything is saved on this phone and works without internet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = muted,
                )
            }
            BigChoice(PulseIcons.Note, c.active, "Make a plan", "Choose weeks, days and exercises", onNewPlan)
            BigChoice(PulseIcons.Gym, c.move, "Start an empty workout", "Log now, plan later", onEmpty)
            BigChoice(PulseIcons.File, c.sleep, "Bring in old workouts", "From a file you saved", onImport)
            return@DetailPage
        }
        val plan = home.plan
        val next = plan?.next
        PulseCard {
            when {
                home.active != null -> {
                    Column {
                        Text("Open workout", style = MaterialTheme.typography.bodyMedium, color = muted)
                        Text(home.active.title, style = MaterialTheme.typography.headlineMedium)
                    }
                    MainButton("Go on", onClick = onLive)
                }
                next != null -> {
                    val day = LocalDate.parse(next.date)
                    val missed = day < home.today
                    Text(
                        if (missed) "Missed ${day.short(locale)}" else day.near(home.today, locale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (missed) c.moveLabel else muted,
                    )
                    Column {
                        Text(next.title, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "${plan.program.title}, week ${next.week + 1} of ${plan.doc.weeks.size}" + if (next.easy < 100) ", easy week" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                    MainButton(if (day > home.today) "See workout" else "Start workout") { onBefore(next) }
                }
                else -> {
                    Text("No plan is on", style = MaterialTheme.typography.titleMedium)
                    Hint("Start a plan, or log a workout without one.")
                    MainButton(if (home.hasPlans) "Choose a plan" else "Make a plan", onClick = if (home.hasPlans) onPlans else onNewPlan)
                }
            }
        }
        PulseCard {
            CardHeader("This week", if (home.weekAll == 0) null else "${home.weekDone} of ${home.weekAll} done")
            WeekMarks(home.week)
        }
        RowGroup(listOf(
            { ListRow(PulseIcons.Note, c.activeLabel, "My plans", plan?.let { "${it.program.title} is on" } ?: "None is on", onClick = onPlans) },
            { ListRow(
                PulseIcons.Calendar,
                c.sleepLabel,
                "Schedule",
                next?.let { "Next: ${LocalDate.parse(it.date).tiny(locale)}, ${it.title}" } ?: "Nothing planned",
                onClick = onSchedule,
            ) },
            { ListRow(PulseIcons.Gym, c.moveLabel, "Exercises", "${home.exercises}, and ${home.own} of your own", onClick = onExercises) },
            { ListRow(
                PulseIcons.Trends,
                c.bodyLabel,
                "Progress",
                if (home.recordsThisWeek > 0) words(home.recordsThisWeek, "new record") + " this week" else "Records, charts and muscles",
                onClick = onProgress,
            ) },
        ))
        if (home.active == null) SideButton("Start an empty workout", onClick = onEmpty)
    }
}

@Composable
private fun BigChoice(icon: ImageVector, tint: Color, title: String, sub: String, onClick: () -> Unit) {
    PulseCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon, tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Hint(sub)
            }
            Icon(PulseIcons.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun WeekMarks(days: List<WeekDay>) {
    val scheme = MaterialTheme.colorScheme
    val locale = locale()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { d ->
            val said = when (d.mark) { DayMark.Done -> "done"; DayMark.Planned -> "planned"; DayMark.Skipped -> "skipped"; DayMark.None -> "no workout" }
            Column(
                Modifier.weight(1f).padding(vertical = 4.dp).clearAndSetSemantics { contentDescription = "${d.date.short(locale)}: $said" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FitText(
                    d.date.dayOfWeek.getDisplayName(
                        TextStyle.SHORT,
                        locale,
                    ), MaterialTheme.typography.bodySmall.copy(fontWeight = if (d.today) FontWeight.Bold else FontWeight.Normal),
                    if (d.today) scheme.onSurface else scheme.onSurfaceVariant,
                )
                MarkCircle(d.mark, d.today)
            }
        }
    }
}

/** What is coming, with today's weights, before the workout begins. */
@Composable
fun BeforeScreen(
    planned: PlannedEntity,
    plan: PlanOn?,
    rows: List<SlotPreview>?,
    lastLabel: String?,
    settings: TrainingSettings,
    today: LocalDate,
    contentPadding: PaddingValues,
    onBack: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onExercise: (String) -> Unit, onStart: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = locale()
    val day = LocalDate.parse(planned.date)
    DetailPage(planned.title, contentPadding, onBack, actions = {
        TextButton(onClick = onMove) { Text("Move") }
        TextButton(onClick = onEdit) { Text("Edit") }
    }) {
        PulseCard {
            Column {
                Text(
                    listOfNotNull(plan?.program?.title, "week ${planned.week + 1}", day.near(today, locale).let { if (day == today || day == today.plusDays(1) || day == today.minusDays(1)) it.lowercase() else it }).joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                Text(
                    if (rows == null) " " else "${words(rows.size, "exercise")}, ${words(rows.sumOf { it.slot.working + it.slot.drops }, "set")}",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            if (lastLabel != null) InfoRow("Last time", lastLabel)
            if (planned.easy < 100) InfoRow("Weights", "${planned.easy}%, an easy week")
        }
        if (rows == null) return@DetailPage
        if (rows.isEmpty()) {
            Hint("This day has no exercises yet.")
            MainButton("Add exercises", icon = PulseIcons.Plus, onClick = onEdit)
            return@DetailPage
        }
        SectionTitle("Exercises")
        RowGroup(rows.map { p ->
            {
                ListRow(
                    PulseIcons.Gym, PulseTheme.colors.activeLabel, p.name,
                    subtitle = lastText(p.last, p.exercise?.track, settings.unit)?.let { "Last $it" } ?: target(p),
                    value = p.suggestion.grams?.let { Weights.text(it, settings.unit) }, onClick = { onExercise(p.slot.exerciseId) },
                )
            }
        })
        MainButton(if (day > today) "Start it today" else "Start workout", onClick = onStart)
    }
}

private fun target(p: SlotPreview): String {
    val reps = if (p.slot.repsMin == p.slot.repsMax) "${p.slot.repsMin}" else "${p.slot.repsMin}–${p.slot.repsMax}"
    return "${words(p.slot.working, "set")} of $reps" + if (p.exercise?.track == Track.Time) " seconds" else ""
}
