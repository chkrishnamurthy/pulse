package com.fitbittrack.pulse.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.training.PlannedEntity
import com.fitbittrack.pulse.training.PlannedState
import com.fitbittrack.pulse.training.ScheduleRules
import com.fitbittrack.pulse.training.SessionEntity
import com.fitbittrack.pulse.training.UndoEntity
import com.fitbittrack.pulse.training.days
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.screens.SwitchRow
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

class WorkoutCalendarActions(
    val month: (YearMonth) -> Unit,
    val before: (PlannedEntity) -> Unit,
    val move: (PlannedEntity) -> Unit,
    val session: (String) -> Unit,
    val undo: (UndoEntity) -> Unit,
    val train: () -> Unit,
)

/** The Calendar page with the Workouts chip on. Shown in place of the goal cards. */
@Composable
fun WorkoutCalendar(ui: WorkoutMonth, act: WorkoutCalendarActions) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val locale = locale()
    var day by rememberSaveable { mutableStateOf<Long?>(null) }

    ui.next?.let { n ->
        val date = LocalDate.parse(n.date)
        PulseCard {
            CardHeader(
                if (date < ui.today) "Missed ${date.short(locale)}" else date.near(ui.today, locale),
                n.title,
                if (date < ui.today) c.moveLabel else scheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { act.before(n) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (date > ui.today) "See" else "Start") }
                SoftButton("Move", Modifier.weight(1f)) { act.move(n) }
            }
        }
    }
    ui.undo?.let { u ->
        PulseCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { act.undo(u) }) { Text("Undo") }
            }
        }
    }
    PulseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (ui.month != YearMonth.from(ui.today)) TextButton(onClick = { act.month(YearMonth.from(ui.today)) }) { Text("Today") }
            IconAct(PulseIcons.Previous, "Previous month") { act.month(ui.month.minusMonths(1)) }
            IconAct(PulseIcons.Forward, "Next month") { act.month(ui.month.plusMonths(1)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                (0L..6L).forEach { Text(
                    ui.firstDay.plus(it).getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                ) }
            }
            val blanks = (ui.month.atDay(1).dayOfWeek.value - ui.firstDay.value + 7) % 7
            (0 until (blanks + ui.month.lengthOfMonth() + 6) / 7).forEach { r ->
                Row(Modifier.fillMaxWidth()) {
                    (0 until 7).forEach { col ->
                        val n = r * 7 + col - blanks + 1
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                            if (n in 1..ui.month.lengthOfMonth()) MonthCell(ui.month.atDay(n), ui) { day = it.toEpochDay() }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
            Key("Done", Modifier.background(c.active, CircleShape))
            Key("Planned", Modifier.border(2.dp, c.active, CircleShape))
            Key("Skipped", Modifier.border(2.dp, scheme.outline, CircleShape))
        }
    }
    val done = ui.marks.count { it.value == DayMark.Done }
    val due = ui.marks.count { (d, m) -> m != DayMark.None && d <= ui.today && !(m == DayMark.Planned && d == ui.today) }
    PulseCard {
        CardHeader("Workouts", if (due == 0) null else "$done of ${words(due, "day")}")
        ProgressBar(if (due == 0) 0f else done.toFloat() / due, c.active, height = 10)
        Hint(if (ui.marks.isEmpty()) "No workouts this month." else "Days with a workout, out of the days that were planned so far.")
    }
    TileRow {
        MetricTile("Weeks in a row", scheme.onSurfaceVariant, "${ui.streak}", if (ui.streak == 1) "week" else "weeks", Modifier.weight(1f))
        MetricTile("Best so far", scheme.onSurfaceVariant, "${ui.bestStreak}", if (ui.bestStreak == 1) "week" else "weeks", Modifier.weight(1f))
    }
    if (ui.next == null && ui.marks.isEmpty()) SideButton("Open Train", icon = PulseIcons.Gym, onClick = act.train)

    day?.let { LocalDate.ofEpochDay(it) }?.let { d ->
        val sessions = ui.sessions[d].orEmpty()
        val planned = ui.planned[d].orEmpty()
        if (sessions.isEmpty() && planned.isEmpty()) day = null
        else DaySheet(d, ui.today, sessions, planned, { day = null }, act)
    }
}

@Composable
private fun Key(text: String, modifier: Modifier) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    Box(Modifier.size(8.dp).then(modifier))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MonthCell(date: LocalDate, ui: WorkoutMonth, onClick: (LocalDate) -> Unit) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val locale = locale()
    val mark = ui.marks[date] ?: DayMark.None
    val today = date == ui.today
    val said = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)) + ", " + when (mark) {
        DayMark.Done -> "done: ${ui.titles[date]}"
        DayMark.Planned -> "planned: ${ui.titles[date]}"
        DayMark.Skipped -> "skipped: ${ui.titles[date]}"
        DayMark.None -> "no workout"
    }
    val ring = when (mark) {
        DayMark.Done -> Modifier.border(4.dp, c.active, CircleShape)
        DayMark.Planned -> Modifier.border(2.dp, c.active, CircleShape)
        DayMark.Skipped -> Modifier.border(2.dp, scheme.outline, CircleShape)
        DayMark.None -> Modifier
    }
    Box(
        Modifier.fillMaxSize().clip(CircleShape).then(if (mark == DayMark.None) Modifier else Modifier.clickable { onClick(date) })
            .clearAndSetSemantics { contentDescription = said; if (mark != DayMark.None) { role = Role.Button; onClick { onClick(date); true } } }.then(ring),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().padding(7.dp).background(if (today) scheme.primary else if (mark == DayMark.Done) c.active.copy(alpha = 0.26f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (mark == DayMark.Done || today) FontWeight.Bold else FontWeight.Medium,
                color = if (today) scheme.onPrimary else if (mark == DayMark.None || mark == DayMark.Skipped) scheme.onSurfaceVariant else scheme.onSurface,
            )
        }
    }
}

@Composable
private fun DaySheet(
    date: LocalDate,
    today: LocalDate,
    sessions: List<SessionEntity>,
    planned: List<PlannedEntity>,
    onDismiss: () -> Unit,
    act: WorkoutCalendarActions,
) {
    val c = PulseTheme.colors
    PulseSheet(onDismiss) {
        Text(date.near(today, locale()), style = MaterialTheme.typography.titleLarge)
        RowGroup(sessions.map<SessionEntity, @Composable () -> Unit> { s -> { ListRow(PulseIcons.Check, c.activeLabel, s.title, "Done", onClick = { onDismiss(); act.session(s.id) }) } } +
            planned.map { p -> { ListRow(
                PulseIcons.Gym,
                if (p.state == PlannedState.Skipped) MaterialTheme.colorScheme.onSurfaceVariant else c.activeLabel,
                p.title,
                if (p.state == PlannedState.Skipped) "Skipped" else "Planned, week ${p.week + 1}",
                onClick = { onDismiss(); act.before(p) },
            ) } })
        planned.firstOrNull { it.state == PlannedState.Planned }?.let { p -> SideButton("Move ${p.title}") { onDismiss(); act.move(p) } }
    }
}

/** What to do with a planned workout. */
@Composable
fun MoveSheet(
    p: PlannedEntity,
    plan: PlanOn,
    today: LocalDate,
    onDismiss: () -> Unit,
    onMove: (LocalDate) -> Unit,
    onShift: () -> Unit,
    onSwap: (String) -> Unit,
    onSkip: () -> Unit,
    onPin: () -> Unit,
) {
    val locale = locale()
    val date = LocalDate.parse(p.date)
    var how by remember { mutableStateOf("shift") }
    var pick by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    if (other) {
        PulseSheet({ other = false }) {
            Text("Swap ${p.title} with", style = MaterialTheme.typography.titleLarge)
            RowGroup(plan.open.filter { it.id != p.id }.take(12).map { o -> { ListRow(
                PulseIcons.Gym,
                PulseTheme.colors.activeLabel,
                o.title,
                LocalDate.parse(o.date).near(today, locale),
                onClick = { onSwap(o.id) },
            ) } })
        }
        return
    }
    PulseSheet(onDismiss) {
        Text("${p.title}, ${date.short(locale)}", style = MaterialTheme.typography.titleLarge)
        val shifted = remember(p, plan) { ScheduleRules.shift(plan.items, p.id, plan.enrollment.days(), today).firstOrNull { it.id == p.id }?.date }
        RowGroup(buildList {
            add { RadioRow("Move only this one", "Choose another day", how == "move") { how = "move" } }
            add { RadioRow(
                "Shift the rest of the plan",
                "This one to ${(shifted ?: date).short(locale)}, everything later follows",
                how == "shift",
            ) { how = "shift" } }
            if (plan.open.size > 1) add { RadioRow("Swap with another workout", "The two change places", how == "swap") { how = "swap" } }
            add { RadioRow("Skip it", "It stays in your history as skipped", how == "skip") { how = "skip" } }
        })
        MainButton(if (how == "move") "Choose a day" else if (how == "swap") "Choose a workout" else "See what changes") {
            when (how) { "move" -> pick = true; "shift" -> onShift(); "swap" -> other = true; else -> onSkip() }
        }
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) { TextButton(onClick = onPin) { Text(if (p.pinned) "Unpin this day" else "Pin to this day, so shifts leave it") } }
    }
    if (pick) DateSheet(maxOf(date, today), { pick = false }) { pick = false; onMove(it) }
}

/** Every change of date is shown before it is saved. */
@Composable
fun ScheduleDraftScreen(draft: ScheduleDraft?, contentPadding: PaddingValues, onBack: () -> Unit, onSave: () -> Unit) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val locale = locale()
    DetailPage("See what changes", contentPadding, onBack) {
        if (draft == null) return@DetailPage
        val changes = draft.changes
        if (changes.isEmpty()) {
            Hint("Nothing changes.")
            SideButton("Back", onClick = onBack)
            return@DetailPage
        }
        PulseCard {
            CardHeader(
                if (changes.any { it.second.state == PlannedState.Skipped }) "${words(changes.size, "workout")} skipped" else "${words(changes.size, "workout")} ${if (changes.size == 1) "moves" else "move"}",
                "Nothing is deleted",
            )
            changes.take(8).forEach { (a, b) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(draft.titles[a.id].orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(a.date.tiny(locale), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Icon(PulseIcons.Forward, contentDescription = "to", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Text(
                        if (b.state == PlannedState.Skipped) "Skipped" else b.date.short(locale),
                        style = MaterialTheme.typography.titleSmall,
                        color = c.activeLabel,
                    )
                }
            }
            if (changes.size > 8) Hint("and ${changes.size - 8} more")
        }
        if (draft.endBefore != null && draft.endAfter != null && draft.endBefore != draft.endAfter) PulseCard {
            InfoRow("Plan ended", draft.endBefore!!.short(locale))
            InfoRow("Plan now ends", draft.endAfter!!.short(locale))
        }
        if (draft.easy.isNotEmpty()) PulseCard { InfoRow("At 90% of the weight", words(draft.easy.size, "workout")) }
        MainButton("Save the new dates", onClick = onSave)
        SideButton("Cancel", onClick = onBack)
        Hint("You can undo this in the Calendar for 30 days.")
    }
}

@Composable
fun PauseScreen(plan: PlanOn?, today: LocalDate, contentPadding: PaddingValues, onBack: () -> Unit, onPause: (LocalDate, LocalDate, Boolean) -> Unit) {
    val c = PulseTheme.colors
    val locale = locale()
    var from by rememberSaveable { mutableLongStateOf(today.plusDays(1).toEpochDay()) }
    var until by rememberSaveable { mutableLongStateOf(today.plusDays(7).toEpochDay()) }
    var gently by rememberSaveable { mutableStateOf(true) }
    var pick by remember { mutableStateOf<String?>(null) }
    val f = LocalDate.ofEpochDay(from)
    val u = LocalDate.ofEpochDay(until)
    DetailPage("Pause the plan", contentPadding, onBack) {
        if (plan == null) { Hint("No plan is on."); return@DetailPage }
        RowGroup(listOf(
            { ListRow(PulseIcons.Calendar, c.sleepLabel, "From", "First day without training", f.short(locale), onClick = { pick = "from" }) },
            { ListRow(PulseIcons.Calendar, c.sleepLabel, "Until", "Last day without training", u.short(locale), onClick = { pick = "until" }) },
        ))
        val ok = u >= f
        val after = remember(plan, f, u) { ScheduleRules.pause(plan.items, f, u, plan.enrollment.days()) }
        val moved = plan.planned.count { it.state == PlannedState.Planned && !it.pinned && LocalDate.parse(it.date) >= f }
        val back = after.filter { it.state == PlannedState.Planned && it.date > u }.minByOrNull { it.date }
        if (ok) PulseCard {
            CardHeader("What happens")
            InfoRow("Workouts that move", "$moved")
            if (back != null) InfoRow(
                "You start again",
                back.date.short(locale) + (plan.planned.firstOrNull { it.id == back.id }?.let { ", week ${it.week + 1}" } ?: ""),
            )
            after.filter { it.state == PlannedState.Planned }.maxOfOrNull { it.date }?.let { InfoRow("Plan now ends", it.short(locale)) }
        } else Hint("The last day must not be before the first day.", MaterialTheme.colorScheme.error)
        RowGroup(listOf({ SwitchRow("Come back gently", "First week at 90% of the weight", gently) { gently = it } }), dividerInset = 16)
        MainButton("See what changes", enabled = ok && moved > 0) { onPause(f, u, gently) }
        SideButton("Cancel", onClick = onBack)
    }
    when (pick) {
        "from" -> DateSheet(f, { pick = null }) { from = it.toEpochDay(); if (u < it) until = it.plusDays(6).toEpochDay(); pick = null }
        "until" -> DateSheet(u, { pick = null }) { until = it.toEpochDay(); pick = null }
    }
}
