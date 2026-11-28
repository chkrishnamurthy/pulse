package com.fitbittrack.pulse.ui.training

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.fitbittrack.pulse.training.TrainingGraph
import com.fitbittrack.pulse.training.ExerciseCatalog
import com.fitbittrack.pulse.training.EdbRules
import com.fitbittrack.pulse.training.EdbEntity
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitbittrack.pulse.training.Effort
import com.fitbittrack.pulse.training.EffortMode
import com.fitbittrack.pulse.training.Equipment
import com.fitbittrack.pulse.training.Plates
import com.fitbittrack.pulse.training.SetEntity
import com.fitbittrack.pulse.training.SetKind
import com.fitbittrack.pulse.training.Track
import com.fitbittrack.pulse.training.TrainingSettings
import com.fitbittrack.pulse.training.Weights
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.FitText
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.Space
import com.fitbittrack.pulse.ui.components.Sparkline
import com.fitbittrack.pulse.ui.components.StatusPill
import com.fitbittrack.pulse.ui.theme.PulseTheme
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** Which number of a set is being typed. */
internal enum class Field { Weight, Reps, Seconds, Effort }

/** The time now, twice a second, for the clocks. */
@Composable
private fun rememberTick(): Long {
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(500) } }
    return now
}

private fun left(ends: Long?, now: Long): Int = ends?.let { ceil((it - now) / 1000.0).toInt().coerceAtLeast(0) } ?: 0

class LiveActions(
    val tick: (SetEntity, Boolean) -> Unit,
    val change: (String, (SetEntity) -> SetEntity) -> Unit,
    val addSet: (String) -> Unit,
    val deleteSet: (String) -> Unit,
    val addExercise: () -> Unit,
    val swap: (LiveExercise) -> Unit,
    val remove: (String) -> Unit,
    val move: (String, Boolean) -> Unit,
    val open: (String) -> Unit,
    val addRest: (Int) -> Unit,
    val skipRest: () -> Unit,
)

@Composable
fun LiveWorkoutScreen(
    ui: LiveUi,
    settings: TrainingSettings,
    bpm: Int?,
    contentPadding: PaddingValues,
    act: LiveActions,
    onBack: () -> Unit,
    onRest: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
) {
    val now = rememberTick()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var ask by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<Pair<String, Field>?>(null) }
    var why by remember { mutableStateOf<LiveExercise?>(null) }
    val list = rememberLazyListState()

    // A finished exercise makes room for the next one.
    val current = ui.current?.entity?.id
    var seen by rememberSaveable { mutableStateOf(current) }
    LaunchedEffect(current) {
        if (current != null && seen != null && current != seen) ui.exercises.indexOfFirst { it.entity.id == current }.takeIf { it >= 0 }?.let { list.animateScrollToItem(it + 1) }
        seen = current
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        TopBar(
            ui.session.title, onBack, close = true,
            sub = { Text(span(((now - ui.session.startMs) / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = muted) },
        ) {
            if (bpm != null) Bpm(bpm)
            TextButton(onClick = { ask = "finish" }) { Text("Finish") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize(), state = list,
                contentPadding = PaddingValues(
                    start = Space.page,
                    end = Space.page,
                    top = 8.dp,
                    bottom = Space.section,
                ), verticalArrangement = Arrangement.spacedBy(Space.gap),
            ) {
                item(key = "progress") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProgressBar(if (ui.all == 0) 0f else ui.done.toFloat() / ui.all, PulseTheme.colors.active)
                        Text(
                            if (ui.all == 0) "Add your first exercise" else "${ui.done} of ${words(ui.all, "set")} done",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                }
                items(ui.exercises, key = { it.entity.id }) { e ->
                    ExerciseCard(e, ui.exercises, settings, act, onEdit = { s, f -> edit = s to f }, onWhy = { why = e })
                }
                item(key = "add") { SideButton("Add exercise", icon = PulseIcons.Plus, onClick = act.addExercise) }
                item(key = "discard") {
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) { TextButton(onClick = { ask = "discard" }) { Text("Throw this workout away", color = MaterialTheme.colorScheme.error) } }
                }
            }
        }
        RestBar(ui, now, contentPadding, act, onRest)
    }

    edit?.let { (id, field) ->
        val e = ui.exercises.firstOrNull { x -> x.sets.any { it.id == id } }
        val s = e?.sets?.firstOrNull { it.id == id }
        if (e == null || s == null) edit = null
        else NumberSheet(e, s, field, settings, onDismiss = { edit = null }) { change -> act.change(id, change); edit = null }
    }
    why?.let { e -> WhySheet(e, settings, onDismiss = { why = null }, onOpen = { why = null; act.open(e.entity.exerciseId) }) }
    when (ask) {
        "finish" -> if (ui.done == 0) Ask(
            "Nothing is ticked yet",
            "Tick at least one set, or throw this workout away.",
            "Throw away",
            { ask = null },
            no = "Go on",
        ) { ask = null; onDiscard() }
        else Ask(
            "Finish this workout?",
            if (ui.done < ui.all) "${words(ui.all - ui.done, "set")} without a tick will not be saved." else "All sets are done.",
            "Finish", { ask = null }, danger = false, no = "Go on",
        ) { ask = null; onFinish() }
        "discard" -> Ask(
            "Throw this workout away?",
            "It will not count. The planned day stays open.",
            "Throw away",
            { ask = null },
            no = "Keep",
        ) { ask = null; onDiscard() }
    }
}

internal fun span(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) "${s / 3600} h ${s / 60 % 60} min" else "${s / 60} min ${s % 60} sec"
}

@Composable
internal fun Bpm(bpm: Int) {
    val c = PulseTheme.colors
    Row(
        Modifier.background(
            c.heart.copy(alpha = 0.14f),
            CircleShape,
        ).padding(horizontal = 12.dp, vertical = 7.dp).semantics(mergeDescendants = true) { contentDescription = "Heart rate $bpm" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(PulseIcons.Heart, contentDescription = null, tint = c.heartLabel, modifier = Modifier.size(16.dp))
        Text("$bpm bpm", style = MaterialTheme.typography.labelMedium, color = c.heartLabel, maxLines = 1)
    }
}

@Composable
private fun ExerciseCard(
    e: LiveExercise,
    all: List<LiveExercise>,
    settings: TrainingSettings,
    act: LiveActions,
    onEdit: (String, Field) -> Unit,
    onWhy: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val track = e.exercise?.track ?: Track.WeightReps
    val i = all.indexOfFirst { it.entity.id == e.entity.id }
    val effort = settings.effort != EffortMode.Off && track != Track.Time
    val context = LocalContext.current
    val edb by produceState<EdbEntity?>(null, e.entity.exerciseId) { value = TrainingGraph.repository(context).edbFor(e.entity.exerciseId) }
    var how by remember { mutableStateOf(false) }
    PulseCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExerciseThumb(EdbRules.edbIdOf(e.entity.exerciseId) ?: edb?.id, e.entity.name, 56, moving = true) { how = true }
            Column(Modifier.weight(1f)) {
                if (e.entity.superset != null) Text(
                    "Superset ${e.entity.superset}",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseTheme.colors.activeLabel,
                )
                Text(e.entity.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                val note = e.entity.target.ifEmpty { lastRows(e.last, track, settings.unit)?.let { "Last $it" } ?: "First time" }
                Text(note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            MoreMenu("More for ${e.entity.name}", buildList {
                if (e.entity.reason.isNotEmpty()) add(Act("Why this weight?") { onWhy() })
                add(Act("Show exercise") { act.open(e.entity.exerciseId) })
                if (i > 0) add(Act("Move up") { act.move(e.entity.id, true) })
                if (i < all.lastIndex) add(Act("Move down") { act.move(e.entity.id, false) })
                add(Act("Remove exercise", danger = true) { act.remove(e.entity.id) })
            })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Head("Set", Modifier.width(26.dp))
            Head("Last", Modifier.weight(1f))
            if (track == Track.WeightReps) Head(settings.unit.label, Modifier.width(56.dp))
            Head(if (track == Track.Time) "Seconds" else "Reps", Modifier.width(if (track == Track.Time) 68.dp else 42.dp))
            if (effort) Head(if (settings.effort == EffortMode.Rpe) "RPE" else "Left", Modifier.width(42.dp))
            Spacer(Modifier.width(48.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          var n = 0
          e.sets.forEachIndexed { k, s ->
            if (s.kind.counts) n++
            // What was done in this set last time, shown beside it.
            val before = e.last.getOrNull(e.sets.take(k).count { it.kind.counts })?.takeIf { s.kind.counts }?.let { r ->
                when {
                    track == Track.Time -> "${r.seconds ?: 0}"
                    r.grams != null -> "${Weights.number(r.grams, settings.unit)} × ${r.reps ?: 0}"
                    else -> "${r.reps ?: 0}"
                }
            }
            val label = if (s.kind == SetKind.Working) "$n" else s.kind.short
            SetRow(s, label, n, before, track, effort, settings, act, e.entity.name, onEdit)
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
            SideButton("Add set", Modifier.weight(1f), PulseIcons.Plus) { act.addSet(e.entity.id) }
            SideButton("Swap", Modifier.weight(1f), PulseIcons.Swap) { act.swap(e) }
        }
    }
    if (how) HowToSheet(e.entity.exerciseId, e.entity.name, edb, { how = false }) { how = false; act.open(e.entity.exerciseId) }
}

/** How to do an exercise, without leaving the sets: the animation and the steps. */
@Composable
private fun HowToSheet(exerciseId: String, name: String, edb: EdbEntity?, onDismiss: () -> Unit, onOpen: () -> Unit) = PulseSheet(onDismiss) {
    val context = LocalContext.current
    val steps by produceState(edb?.stepList().orEmpty(), exerciseId, edb) {
        if (value.isEmpty()) value = withContext(Dispatchers.IO) { runCatching { ExerciseCatalog.steps(context, exerciseId) }.getOrDefault(emptyList()) }
    }
    Text(name, style = MaterialTheme.typography.titleLarge)
    ExerciseAnimation(EdbRules.edbIdOf(exerciseId) ?: edb?.id, name)
    steps.take(4).forEachIndexed { i, s ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = PulseTheme.colors.activeLabel)
            Text(s, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
        SideButton("All about it", Modifier.weight(1f), onClick = onOpen)
        MainButton("Back to sets", Modifier.weight(1f), onClick = onDismiss)
    }
}

@Composable
private fun Head(text: String, modifier: Modifier) = FitText(text, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant, modifier)

@Composable
private fun SetRow(
    s: SetEntity,
    label: String,
    n: Int,
    last: String?,
    track: Track,
    effort: Boolean,
    settings: TrainingSettings,
    act: LiveActions,
    name: String,
    onEdit: (String, Field) -> Unit,
) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val spoken = if (s.kind == SetKind.Working) "set $n" else s.kind.label.lowercase()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (s.done) c.active.copy(alpha = 0.16f) else Color.Transparent).heightIn(min = 52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.width(26.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Kind of set") { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (s.kind == SetKind.Working) scheme.onSurface else c.moveLabel)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = scheme.surfaceContainerHigh) {
                SetKind.entries.forEach { k -> DropdownMenuItem(
                    text = { Text(k.label, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { menu = false; act.change(s.id) { it.copy(kind = k) } },
                ) }
                DropdownMenuItem(
                    text = { Text("Delete set", style = MaterialTheme.typography.bodyLarge, color = scheme.error) },
                    onClick = { menu = false; act.deleteSet(s.id) },
                )
            }
        }
        FitText(last ?: "–", MaterialTheme.typography.bodyMedium, scheme.onSurfaceVariant, Modifier.weight(1f))
        if (track == Track.WeightReps) Cell(
            s.grams?.let { Weights.number(it, settings.unit) },
            "Weight of $spoken, $name",
            s.done,
            56,
        ) { onEdit(s.id, Field.Weight) }
        if (track == Track.Time) Cell(s.seconds?.toString(), "Seconds of $spoken, $name", s.done, 68) { onEdit(s.id, Field.Seconds) }
        else Cell(s.reps?.toString(), "Reps of $spoken, $name", s.done, 42) { onEdit(s.id, Field.Reps) }
        if (effort) Cell(s.rpe10?.let { Effort.text(it, settings.effort) }, "Effort of $spoken, $name", s.done, 42) { onEdit(s.id, Field.Effort) }
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Checkbox) {
                // A set without its number can't be done: ask for it first.
                if (!s.done && track == Track.Time && s.seconds == null) onEdit(s.id, Field.Seconds)
                else if (!s.done && track == Track.WeightReps && s.grams == null) onEdit(s.id, Field.Weight)
                else if (!s.done && track != Track.Time && s.reps == null) onEdit(s.id, Field.Reps)
                else act.tick(s, !s.done)
            }.semantics { contentDescription = "Done: $spoken, $name"; selected = s.done },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(40.dp).then(if (s.done) Modifier.background(c.activeLabel, CircleShape) else Modifier.border(2.dp, scheme.outline, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (s.done) Icon(PulseIcons.Check, contentDescription = null, tint = scheme.surfaceContainer, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun RowScope.Cell(value: String?, label: String, done: Boolean, width: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.width(width.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp)
            .background(
                if (done) Color.Transparent else scheme.surfaceContainerHigh,
                RoundedCornerShape(12.dp),
            ).semantics(mergeDescendants = true) { contentDescription = "$label: ${value ?: "empty"}" },
        contentAlignment = Alignment.Center,
    ) { FitText(
        value ?: "–",
        MaterialTheme.typography.titleSmall,
        if (value == null) scheme.onSurfaceVariant else scheme.onSurface,
        Modifier.padding(horizontal = 2.dp, vertical = 10.dp),
    ) }
}

@Composable
private fun RestBar(ui: LiveUi, now: Long, contentPadding: PaddingValues, act: LiveActions, onRest: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sec = left(ui.session.restEndsMs, now)
    if (sec <= 0) { Spacer(Modifier.height(contentPadding.calculateBottomPadding())); return }
    val soft = scheme.onPrimary.copy(alpha = 0.16f)
    Row(
        Modifier.fillMaxWidth().background(scheme.primary).clickable(
            onClickLabel = "Show the rest",
            onClick = onRest,
        ).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + contentPadding.calculateBottomPadding()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Rest", style = MaterialTheme.typography.bodySmall, color = scheme.onPrimary.copy(alpha = 0.8f))
            Text(clock(sec), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp), color = scheme.onPrimary)
        }
        SoftButton("−15", Modifier.semantics { contentDescription = "15 seconds less" }, soft, scheme.onPrimary) { act.addRest(-15) }
        SoftButton("+15", Modifier.semantics { contentDescription = "15 seconds more" }, soft, scheme.onPrimary) { act.addRest(15) }
        SoftButton("Skip", Modifier.semantics { contentDescription = "Skip rest" }, soft, scheme.onPrimary) { act.skipRest() }
    }
}

/** The rest, large, with the next set and the heart rate. */
@Composable
fun RestScreen(
    ui: LiveUi?,
    settings: TrainingSettings,
    bpm: Int?,
    trail: List<Int>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAdd: (Int) -> Unit,
    onSkip: () -> Unit,
) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val now = rememberTick()
    val sec = left(ui?.session?.restEndsMs, now)
    val total = ui?.session?.restTotalSec?.coerceAtLeast(1) ?: 1
    // Over or skipped: back to the sets.
    LaunchedEffect(ui?.session?.id, sec == 0) { if (ui != null && sec == 0) { delay(300); onBack() } }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopBar("Rest", onBack, close = true)
        Column(Modifier.padding(horizontal = Space.page, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(232.dp)) {
                    val w = 12.dp.toPx()
                    val at = Offset(w / 2, w / 2)
                    val box = Size(size.width - w, size.height - w)
                    drawArc(c.track, 0f, 360f, false, at, box, style = Stroke(w))
                    drawArc(c.active, -90f, 360f * (sec.toFloat() / total).coerceIn(0f, 1f), false, at, box, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(clock(sec), style = MaterialTheme.typography.displayLarge)
                    Hint("of ${clock(total)}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                SoftButton("−15 sec", Modifier.weight(1f), height = 56) { onAdd(-15) }
                SoftButton("+15 sec", Modifier.weight(1f), height = 56) { onAdd(15) }
                SoftButton("Skip", Modifier.weight(1f), height = 56) { onSkip() }
            }
            ui?.next?.let { (e, i) ->
                val s = e.sets[i]
                PulseCard {
                    Column {
                        Text(
                            "Next, ${if (s.kind == SetKind.Working) "set ${e.sets.take(i + 1).count { it.kind.counts }}" else s.kind.label.lowercase()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(e.entity.name, style = MaterialTheme.typography.titleLarge)
                        Hint(listOfNotNull(
                            s.grams?.let { Weights.text(it, settings.unit) },
                            s.reps?.let { words(it, "rep") } ?: s.seconds?.let { "$it seconds" },
                        ).joinToString(", ").ifEmpty { e.entity.target })
                    }
                }
            }
            if (bpm != null) PulseCard {
                StatusPill("Live from your band", c.good, c.goodContainer)
                BigValue("$bpm", "bpm", 36)
                if (trail.size > 2) Sparkline(trail.map { it.toFloat() }, c.heart)
                trail.maxOrNull()?.let { Hint("Highest $it in the last ${trail.size} readings") }
            }
        }
    }
}

/** A big number pad. For weights it shows the plates, for effort a row of choices. */
@Composable
internal fun NumberSheet(
    e: LiveExercise,
    s: SetEntity,
    field: Field,
    settings: TrainingSettings,
    onDismiss: () -> Unit,
    onSave: ((SetEntity) -> SetEntity) -> Unit,
) {
    val unit = settings.unit
    val scheme = MaterialTheme.colorScheme
    val step = e.exercise?.stepGrams ?: 2500
    if (field == Field.Effort) {
        PulseSheet(onDismiss) {
            Text("How hard was it?", style = MaterialTheme.typography.titleLarge)
            Choices((12..20).map { it * 5 }, { it == s.rpe10 }, { Effort.text(it, settings.effort) }) { v -> onSave { it.copy(rpe10 = v) } }
            Hint(if (settings.effort == EffortMode.Rpe) "10 means nothing left. 8 means 2 more reps were possible." else "How many more reps you could have done.")
            SideButton("No effort") { onSave { it.copy(rpe10 = null) } }
        }
        return
    }
    val start = when (field) {
        Field.Weight -> s.grams?.let { Weights.number(it, unit) }
        Field.Reps -> s.reps?.toString()
        else -> s.seconds?.toString()
    }.orEmpty()
    var text by remember { mutableStateOf(start) }
    // The first key replaces the old number.
    var fresh by remember { mutableStateOf(true) }
    val grams = if (field == Field.Weight) (if (text.isEmpty()) null else Weights.parse(text, unit)) else null
    val max = if (field == Field.Reps) 999 else 36_000
    val number = if (field == Field.Weight) null else text.toIntOrNull()?.takeIf { it in 0..max }
    val ok = text.isEmpty() || grams != null || number != null
    fun type(k: String) {
        val base = if (fresh) "" else text
        fresh = false
        val next = if (k == "." && base.isEmpty()) "0." else base + k
        if (k == "." && "." in base) return
        if (next.length <= 6 && (next.substringAfter('.', "").length <= 2)) text = next
    }
    fun nudge(up: Boolean) {
        fresh = true
        text = if (field == Field.Weight) Weights.number(((grams ?: 0) + if (up) step else -step).coerceIn(0, Weights.grams(2000.0, unit)), unit)
        else (((number ?: 0) + if (up) 1 else -1).coerceIn(0, max)).toString()
    }
    fun save() = onSave { x ->
        when (field) {
            Field.Weight -> x.copy(grams = grams)
            Field.Reps -> x.copy(reps = number?.takeIf { it > 0 })
            else -> x.copy(seconds = number?.takeIf { it > 0 })
        }
    }
    PulseSheet(onDismiss) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Hint(e.entity.name)
                BigValue(text.ifEmpty { "–" }, when (field) { Field.Weight -> unit.label; Field.Reps -> "reps"; else -> "seconds" }, 36)
            }
            if (field == Field.Weight && e.exercise?.equipment == Equipment.Barbell) Hint("Bar ${Weights.text(settings.barGrams, unit)}")
        }
        if (field == Field.Weight && e.exercise?.equipment == Equipment.Barbell && grams != null) PlateCard(grams, settings)
        val keys = listOf(
            listOf(
                "1",
                "2",
                "3",
                "−",
            ),
            listOf("4", "5", "6", "+"),
            listOf("7", "8", "9", "Clear"),
            listOf(if (field == Field.Weight) "." else "", "0", "⌫", "OK"),
        )
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val big = k == "OK"
                    val label = when (k) {
                        "−" -> if (field == Field.Weight) "−${Weights.number(step, unit)}" else "−1"
                        "+" -> if (field == Field.Weight) "+${Weights.number(step, unit)}" else "+1"
                        else -> k
                    }
                    if (k.isEmpty()) Spacer(Modifier.weight(1f)) else Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(if (big && ok) scheme.primary else if (big) scheme.surfaceContainerHighest else scheme.surfaceContainer)
                            .clickable(enabled = !big || ok, role = Role.Button) {
                                when (k) {
                                    "−" -> nudge(false)
                                    "+" -> nudge(true)
                                    "Clear" -> { text = ""; fresh = false }
                                    "⌫" -> { text = text.dropLast(1); fresh = false }
                                    "OK" -> save()
                                    else -> type(k)
                                }
                            }.semantics(mergeDescendants = true) { if (k == "⌫") contentDescription = "Delete" },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = MaterialTheme.typography.titleMedium, color = if (big && ok) scheme.onPrimary else scheme.onSurface, maxLines = 1) }
                }
            }
        }
        if (!ok) Hint("This number is too large.", scheme.error)
    }
}

@Composable
private fun PlateCard(total: Int, settings: TrainingSettings) {
    val scheme = MaterialTheme.colorScheme
    val c = PulseTheme.colors
    val (plates, rest) = Plates.side(total, settings.barGrams, settings.plates)
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = scheme.surfaceContainer)
    PulseCard {
        if (rest < 0) { Hint("Lighter than the bar (${Weights.text(settings.barGrams, settings.unit)})."); return@PulseCard }
        val spoken = "Each side: " + plates.joinToString(", ") { Weights.text(it, settings.unit) }
        Canvas(Modifier.fillMaxWidth().height(88.dp).semantics { contentDescription = spoken }) {
            val mid = size.height / 2
            drawRoundRect(scheme.outline, Offset(0f, mid - 5.dp.toPx()), Size(size.width, 10.dp.toPx()), CornerRadius(5.dp.toPx()))
            var x = size.width * 0.22f
            val heaviest = settings.plates.maxOrNull()?.toFloat() ?: 1f
            plates.forEach { p ->
                val h = size.height * (0.36f + 0.6f * (p / heaviest))
                val w = 34.dp.toPx()
                drawRoundRect(
                    if (p >= heaviest * 0.4f) c.sleepLabel else scheme.onSurfaceVariant,
                    Offset(x, mid - h / 2),
                    Size(w, h),
                    CornerRadius(7.dp.toPx()),
                )
                val t = measurer.measure(Weights.number(p, settings.unit), label)
                drawText(t, topLeft = Offset(x + w / 2 - t.size.width / 2, mid - t.size.height / 2))
                x += w + 3.dp.toPx()
            }
        }
        Hint(if (plates.isEmpty()) "Only the bar." else "Each side: " + plates.groupBy { it }.entries.joinToString(" + ") { (p, l) -> (if (l.size > 1) "${l.size} × " else "") + Weights.text(p, settings.unit) })
        if (rest > 0) Hint("${Weights.text(rest, settings.unit)} can't be loaded with your plates.", c.moveLabel)
    }
}

@Composable
private fun WhySheet(e: LiveExercise, settings: TrainingSettings, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val track = e.exercise?.track
    PulseSheet(onDismiss) {
        Column {
            Hint("Suggested today")
            BigValue(e.sets.firstOrNull { it.kind.counts }?.targetGrams?.let { Weights.number(it, settings.unit) } ?: "–", settings.unit.label, 36)
        }
        Text(e.entity.reason, style = MaterialTheme.typography.bodyLarge)
        PulseCard {
            CardHeader("Last time", e.last.firstOrNull()?.let { java.time.LocalDate.parse(it.date).short(locale()) })
            Hint(lastRows(e.last, track, settings.unit) ?: "Not done yet.")
        }
        Hint("You always decide. Change any number in the set.")
        SideButton("Show exercise", onClick = onOpen)
    }
}

/** Where a swap counts. */
@Composable
internal fun SwapSheet(name: String, planned: Boolean, onDismiss: () -> Unit, onPick: (Boolean) -> Unit) = PulseSheet(onDismiss) {
    Text("Swap to $name", style = MaterialTheme.typography.titleLarge)
    Hint("Your sets and reps stay. The weight is what you last used for this exercise.")
    MainButton("Just today") { onPick(false) }
    if (planned) SideButton("From now on, in the plan too") { onPick(true) }
}
