package com.fitbittrack.pulse.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.training.EdbRules
import com.fitbittrack.pulse.training.EdbEntity
import com.fitbittrack.pulse.training.DatedSet
import com.fitbittrack.pulse.training.Effort
import com.fitbittrack.pulse.training.EffortMode
import com.fitbittrack.pulse.training.ExerciseEntity
import com.fitbittrack.pulse.training.Muscle
import com.fitbittrack.pulse.training.ProgressRules
import com.fitbittrack.pulse.training.SessionEntity
import com.fitbittrack.pulse.training.SessionState
import com.fitbittrack.pulse.training.SetEntity
import com.fitbittrack.pulse.training.SetKind
import com.fitbittrack.pulse.training.Strength
import com.fitbittrack.pulse.training.Track
import com.fitbittrack.pulse.training.TrainingSettings
import com.fitbittrack.pulse.training.WeightUnit
import com.fitbittrack.pulse.training.Weights
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.LineChart
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.components.Sparkline
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal enum class Span(val label: String, val days: Long) { Month("Month", 30), Quarter("3 months", 91), Year("Year", 365) }

/** "7,502.5". */
internal fun bigWeight(grams: Long, unit: WeightUnit): String {
    val v = (grams / unit.grams * 10).roundToLong() / 10.0
    return if (v == v.toLong().toDouble()) "%,d".format(v.toLong()) else "%,.1f".format(v)
}

internal fun oneDecimal(grams: Double, unit: WeightUnit): String = Weights.plain((grams / unit.grams * 10).roundToLong() / 10.0)

@Composable
fun ProgressScreen(
    ui: ProgressUi?,
    settings: TrainingSettings,
    skipped: (LocalDate) -> Int,
    bodyWeight: Pair<Instant, Int>?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onExercise: (String) -> Unit,
    onExercises: () -> Unit,
    onMuscles: () -> Unit,
    onHistory: () -> Unit,
) {
    val c = PulseTheme.colors
    val locale = locale()
    var span by rememberSaveable { mutableStateOf(Span.Month) }
    DetailPage("Progress", contentPadding, onBack) {
        if (ui == null) return@DetailPage
        if (ui.sessions.isEmpty()) { Hint("Finish your first workout to see records and charts here."); return@DetailPage }
        Segmented(Span.entries, span, { it.label }) { span = it }
        val from = ui.today.minusDays(span.days - 1)
        val inSpan = ui.sessions.filter { LocalDate.parse(it.session.date) >= from }
        val skip = skipped(from)
        TileRow {
            MetricTile(
                "Workouts",
                c.activeLabel,
                "${inSpan.size}",
                null,
                Modifier.weight(1f),
            ) { Small(if (skip > 0) "$skip skipped" else "in the last ${span.days} days") }
            MetricTile("Weeks in a row", c.activeLabel, "${ui.streak}", null, Modifier.weight(1f)) { Small("Best ${ui.bestStreak}") }
        }
        TileRow {
            val lifted = inSpan.sumOf { it.totals?.volume ?: 0L }
            MetricTile("Lifted", c.activeLabel, bigWeight(lifted, settings.unit).substringBefore('.'), settings.unit.label, Modifier.weight(1f))
            if (bodyWeight != null) MetricTile(
                "Body weight",
                c.bodyLabel,
                oneDecimal(bodyWeight.second.toDouble(), settings.unit),
                settings.unit.label,
                Modifier.weight(1f),
            ) { Small(Fmt.ago(bodyWeight.first)) }
            else MetricTile("Sets", c.activeLabel, Fmt.count(inSpan.sumOf { it.totals?.sets ?: 0 }), null, Modifier.weight(1f))
        }
        SectionTitle("Records")
        if (ui.records.isEmpty()) Hint("Log sets with weight and 1 to 12 reps to set records.")
        else RowGroup(ui.records.take(5).map { b ->
            { ListRow(
                PulseIcons.Star,
                c.moveLabel,
                ui.names[b.exerciseId] ?: "Exercise",
                b.date.near(ui.today, locale),
                "${oneDecimal(b.grams, settings.unit)} ${settings.unit.label}",
                onClick = { onExercise(b.exerciseId) },
            ) }
        })
        RowGroup(listOf(
            { ListRow(PulseIcons.Gym, c.activeLabel, "By exercise", "Charts and best sets", onClick = onExercises) },
            { ListRow(PulseIcons.Target, c.sleepLabel, "By muscle", "Sets each week", onClick = onMuscles) },
            { ListRow(PulseIcons.Today, c.bodyLabel, "All workouts", "Look back and correct", onClick = onHistory) },
        ))
        Hint("Records are estimated best lifts: weight × (1 + reps ÷ 30).")
    }
}

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

@Composable
fun ExerciseScreen(
    e: ExerciseEntity?,
    edb: EdbEntity?,
    sets: List<DatedSet>,
    steps: List<String>,
    settings: TrainingSettings,
    today: LocalDate,
    contentPadding: PaddingValues,
    onBack: () -> Unit, onFavourite: () -> Unit, onEdit: () -> Unit, onSession: (String) -> Unit,
) {
    val c = PulseTheme.colors
    val locale = locale()
    val unit = settings.unit
    var span by rememberSaveable { mutableStateOf(Span.Quarter) }
    DetailPage(e?.name ?: "Exercise", contentPadding, onBack, actions = {
        if (e != null) {
            IconAct(
                PulseIcons.Star,
                if (e.favourite) "Remove from favourites" else "Favourite",
                if (e.favourite) c.move else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onFavourite,
            )
            TextButton(onClick = onEdit) { Text("Edit") }
        }
    }) {
        if (e == null) return@DetailPage
        val edbId = EdbRules.edbIdOf(e.id) ?: edb?.id
        if (edbId != null) ExerciseAnimation(edbId, e.name)
        if (edb != null) PulseCard {
            InfoRow("Main muscle", edb.targetList().joinToString(", ").replaceFirstChar { it.uppercaseChar() }.ifEmpty { e.muscle.label })
            if (edb.secondaryList().isNotEmpty()) InfoRow("Also", edb.secondaryList().joinToString(", ").replaceFirstChar { it.uppercaseChar() })
            InfoRow("Equipment", edb.equipmentList().joinToString(", ").replaceFirstChar { it.uppercaseChar() }.ifEmpty { e.equipment.label })
        }
        val counted = sets.filter { it.set.kind.counts }
        if (counted.isEmpty()) {
            if (edb == null) Hint(e.about)
            PulseCard {
                Text("Not done yet", style = MaterialTheme.typography.titleMedium)
                Hint("After the first workout you see your sets, records and a chart here.")
            }
        } else {
            val curve = ProgressRules.curve(sets)
            if (curve.isNotEmpty()) {
                Segmented(Span.entries, span, { it.label }) { span = it }
                val shown = curve.filter { it.first >= today.minusDays(span.days) }
                val best = curve.maxBy { it.second }
                PulseCard {
                    Hint("Estimated best lift")
                    BigValue(oneDecimal(best.second, unit), unit.label, 40)
                    val change = if (curve.size > 1) best.second - curve.first().second else 0.0
                    if (change >= 50) Text(
                        "${oneDecimal(change, unit)} ${unit.label} above your first of ${words(curve.size, "workout")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.good,
                    )
                    if (shown.size > 1) {
                        val a = shown.first().first.toEpochDay().toFloat()
                        val w = (shown.last().first.toEpochDay() - a).coerceAtLeast(1f)
                        LineChart(
                            shown.map { (d, v) -> (d.toEpochDay() - a) / w to (v / unit.grams).toFloat() }, c.active,
                            "Estimated best lift from ${oneDecimal(shown.first().second, unit)} to ${oneDecimal(shown.last().second, unit)} ${unit.label}",
                            xLabels = listOf(
                                0f to shown.first().first.format(DateTimeFormatter.ofPattern("d MMM", locale)),
                                1f to shown.last().first.format(DateTimeFormatter.ofPattern("d MMM", locale)),
                            ),
                        )
                    } else Hint(if (shown.isEmpty()) "No workouts in this time." else "One more workout draws the chart.")
                }
                val table = listOf(1, 3, 5, 8, 10, 12).mapNotNull { r -> ProgressRules.heaviestFor(sets, r)?.let { r to it } }
                if (table.isNotEmpty()) {
                    SectionTitle("Best sets")
                    PulseCard { table.forEach { (r, g) -> InfoRow(words(r, "rep") + " or more", Weights.text(g, unit)) } }
                }
            } else PulseCard {
                val top = when (e.track) {
                    Track.Time -> counted.mapNotNull { it.set.seconds }.maxOrNull()?.let { "$it seconds" }
                    else -> counted.mapNotNull { it.set.reps }.maxOrNull()?.let { words(it, "rep") }
                }
                Hint("Best set")
                BigValue(top ?: "–", null, 40)
            }
            SectionTitle("Last workouts")
            RowGroup(counted.groupBy { it.sessionId }.values.sortedByDescending { it.first().date }.take(6).map { l ->
                { ListRow(
                    PulseIcons.Today,
                    c.bodyLabel,
                    l.first().date.near(today, locale),
                    lastText(l.map { it.set }, e.track, unit),
                    onClick = { onSession(l.first().sessionId) },
                ) }
            })
            Hint("Estimated best lift is weight × (1 + reps ÷ 30). Only sets of 1 to ${Strength.MAX_REPS} reps count. One rep counts as itself.")
        }
        val how = edb?.stepList()?.takeIf { it.isNotEmpty() } ?: steps
        if (how.isNotEmpty()) {
            SectionTitle("How to do it")
            PulseCard {
                how.forEachIndexed { i, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = c.activeLabel)
                        Text(s, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Hint(e.about + if (e.alsoTrains.isNotEmpty()) ". Also trains " + e.alsoTrains.joinToString(", ") { it.label.lowercase() } + "." else ".")
    }
}

@Composable
fun MusclesScreen(
    ui: ProgressUi?,
    count: (LocalDate, LocalDate) -> Map<Muscle, Double>,
    settings: TrainingSettings,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRange: (Muscle, IntRange) -> Unit,
) {
    val c = PulseTheme.colors
    var last by rememberSaveable { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Muscle?>(null) }
    DetailPage("By muscle", contentPadding, onBack) {
        if (ui == null) return@DetailPage
        Segmented(listOf(false, true), last, { if (it) "Last week" else "This week" }) { last = it }
        val start = ui.today.minusDays(((ui.today.dayOfWeek.value - ui.firstDay.value + 7) % 7).toLong()).let { if (last) it.minusWeeks(1) else it }
        val sets = count(start, start.plusDays(6))
        val shown = Muscle.entries.filter { it != Muscle.Other || (sets[it] ?: 0.0) > 0 }
        PulseCard {
            CardHeader("Hard sets", "Warm-ups not counted")
            shown.sortedByDescending { sets[it] ?: 0.0 }.forEach { m -> MuscleBar(m, sets[m] ?: 0.0, settings.range(m)) }
        }
        val low = shown.filter { it != Muscle.Other && (sets[it] ?: 0.0) < settings.range(it).first }
        if (last && low.isNotEmpty()) PulseCard {
            Text(
                low.joinToString(", ") { it.label } + if (low.size == 1) " is below your range" else " are below your range",
                style = MaterialTheme.typography.titleSmall,
            )
            Hint("The pale band is the range you aim for. Change it below.")
        }
        Hint("A set counts fully for the main muscle and half for muscles that help.")
        SectionTitle("My ranges")
        RowGroup(Muscle.entries.filter { it != Muscle.Other }.map { m -> { ListRow(
            PulseIcons.Target,
            c.activeLabel,
            m.label,
            value = settings.range(m).let { "${it.first} to ${it.last}" },
            onClick = { edit = m },
        ) } })
    }
    edit?.let { m ->
        var lo by remember(m) { mutableIntStateOf(settings.range(m).first) }
        var hi by remember(m) { mutableIntStateOf(settings.range(m).last) }
        PulseSheet({ edit = null }) {
            Text("${m.label}: sets a week", style = MaterialTheme.typography.titleLarge)
            RowGroup(listOf(
                { com.fitbittrack.pulse.ui.screens.NumberStepper("At least", "$lo", lo, 1, 0..40) { lo = it; hi = maxOf(hi, it) } },
                { com.fitbittrack.pulse.ui.screens.NumberStepper("At most", "$hi", hi, 1, 0..40) { hi = it; lo = minOf(lo, it) } },
            ), dividerInset = 16)
            MainButton("Save") { onRange(m, lo..hi); edit = null }
        }
    }
}

@Composable
private fun MuscleBar(m: Muscle, v: Double, range: IntRange) {
    val c = PulseTheme.colors
    val max = maxOf(20.0, v, range.last.toDouble())
    val text = Weights.plain(v)
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "${m.label}: $text sets, range ${range.first} to ${range.last}" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            Text(m.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "$text of ${range.first} to ${range.last}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (v < range.first) c.moveLabel else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp).background(c.track, RoundedCornerShape(8.dp))) {
            val w = maxWidth
            Box(Modifier.offset(x = w * (range.first / max).toFloat()).width(w * ((range.last - range.first) / max).toFloat()).height(8.dp).background(c.active.copy(alpha = 0.28f)))
            Box(Modifier.width(w * (v / max).toFloat()).height(8.dp).background(if (v < range.first) c.move else c.active, RoundedCornerShape(8.dp)))
        }
    }
}

@Composable
fun HistoryScreen(
    ui: ProgressUi?,
    deleted: List<SessionEntity>,
    settings: TrainingSettings,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    var restore by remember { mutableStateOf<SessionEntity?>(null) }
    val c = PulseTheme.colors
    val locale = locale()
    var text by rememberSaveable { mutableStateOf("") }
    val all = ui?.sessions.orEmpty()
    val found = if (text.isBlank()) all else all.filter { h -> text.trim().lowercase().let { q -> q in h.session.title.lowercase() || q in h.session.planTitle.orEmpty().lowercase() || ui!!.rows.any { it.sessionId == h.session.id && q in it.exerciseName.lowercase() } } }
    ListPage("All workouts", contentPadding, onBack) {
        if (ui != null && all.isEmpty() && deleted.isEmpty()) { item { Hint("No finished workouts yet.") }; return@ListPage }
        item { TextBox(text, "Search workouts or exercises", { text = it }, icon = PulseIcons.Search) }
        found.groupBy { YearMonth.from(LocalDate.parse(it.session.date)) }.forEach { (m, list) ->
            item(key = "m$m") { SectionTitle(m.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))) }
            itemsIndexed(list, key = { _, h -> h.session.id }) { i, h ->
                CardSlice(i == 0, i == list.lastIndex) {
                    val s = h.session
                    ListRow(
                        if (h.record) PulseIcons.Star else PulseIcons.Gym,
                        if (h.record) c.moveLabel else c.activeLabel,
                        s.title,
                        listOfNotNull(
                            LocalDate.parse(s.date).near(ui!!.today, locale),
                            h.totals?.let { words(it.sets, "set") },
                            h.totals?.volume?.takeIf { it > 0 }?.let { "${bigWeight(it, settings.unit)} ${settings.unit.label}" },
                        ).joinToString(", "),
                        if (s.timeUnsure || s.endMs == null) null else "${Duration.ofMillis(s.endMs - s.startMs).toMinutes()} min",
                        onClick = { onOpen(s.id) },
                    )
                }
            }
        }
        if (deleted.isNotEmpty() && text.isBlank()) {
            item(key = "deleted") { SectionTitle("Deleted in the last 30 days") }
            itemsIndexed(deleted, key = { _, s -> "d" + s.id }) { i, s ->
                CardSlice(i == 0, i == deleted.lastIndex) {
                    ListRow(
                        PulseIcons.Undo,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        s.title,
                        "${LocalDate.parse(s.date).short(locale)}. Tap to bring it back.",
                        onClick = { restore = s },
                    )
                }
            }
        }
        item { Box(Modifier.padding(top = 12.dp)) { Hint(if (found.isEmpty()) "Nothing found." else "Tap a workout to see or correct it. Walks and runs from your band stay under Health, Workouts.") } }
    }
    restore?.let { r -> Ask(
        "Bring back ${r.title}?",
        "It counts again for your history and records.",
        "Bring back",
        { restore = null },
        danger = false,
    ) { onRestore(r.id); restore = null } }
}

/** A finished workout: the summary right after it, and later the place to correct numbers. */
@Composable
fun FinishedScreen(
    ui: LiveUi?,
    fresh: Boolean,
    progress: ProgressUi?,
    heart: List<HeartRateSample>,
    settings: TrainingSettings,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onFeeling: (Int) -> Unit,
    onChange: (String, (SetEntity) -> SetEntity) -> Unit,
    onDeleteSet: (String) -> Unit,
    onDelete: () -> Unit,
    onExercise: (String) -> Unit,
) {
    val c = PulseTheme.colors
    val locale = locale()
    var edit by remember { mutableStateOf<Pair<String, Field>?>(null) }
    var ask by remember { mutableStateOf(false) }
    DetailPage(if (fresh) "Well done" else ui?.session?.title ?: "Workout", contentPadding, onBack, actions = {
        MoreMenu("More", listOf(Act("Delete this workout", danger = true) { ask = true }))
    }) {
        if (ui == null || ui.session.state == SessionState.Discarded) return@DetailPage
        val s = ui.session
        val done = ui.exercises.flatMap { it.sets }.filter { it.done }
        val counted = done.filter { it.kind.counts }
        PulseCard {
            Column {
                Hint(listOfNotNull(
                    LocalDate.parse(s.date).format(DateTimeFormatter.ofPattern("EEE d MMMM", locale)),
                    s.planTitle,
                    s.week?.let { "week ${it + 1}" },
                ).joinToString(", "))
                Text(s.title, style = MaterialTheme.typography.headlineMedium)
            }
            Row(Modifier.fillMaxWidth()) {
                if (!s.timeUnsure && s.endMs != null) Stat("${Duration.ofMillis(s.endMs - s.startMs).toMinutes()}", "min", Modifier.weight(1f))
                Stat("${counted.size}", if (counted.size == 1) "set" else "sets", Modifier.weight(1f))
                Stat("${counted.sumOf { it.reps ?: 0 }}", "reps", Modifier.weight(1f))
                Stat(
                    bigWeight(counted.sumOf { (it.grams ?: 0).toLong() * (it.reps ?: 0) }, settings.unit),
                    "${settings.unit.label} lifted",
                    Modifier.weight(1.4f),
                )
            }
        }
        val records = progress?.bests?.values?.filter { it.sessionId == s.id }.orEmpty()
        if (records.isNotEmpty()) RowGroup(records.sortedByDescending { it.grams }.take(4).map { b ->
            { ListRow(
                PulseIcons.Star,
                c.moveLabel,
                "Record: ${progress?.names?.get(b.exerciseId) ?: "Exercise"}",
                "${Weights.text(b.weight, settings.unit)} for ${b.reps}. Best lift about ${oneDecimal(b.grams, settings.unit)} ${settings.unit.label}",
                onClick = { onExercise(b.exerciseId) },
            ) }
        })
        if (heart.size > 1) PulseCard {
            CardHeader("Heart rate", "Average ${heart.map { it.bpm }.average().roundToInt()} bpm")
            Sparkline(heart.map { it.bpm.toFloat() }, c.heart)
            Hint("Lowest ${heart.minOf { it.bpm }}, highest ${heart.maxOf { it.bpm }}. From your band, in 5-minute averages.")
        }
        PulseCard {
            CardHeader("How was it?")
            Segmented(listOf(1, 2, 3), s.feeling ?: 0, { listOf("", "Easy", "Good", "Hard")[it] }) { onFeeling(it) }
        }
        ui.exercises.filter { e -> e.sets.any { it.done } }.forEach { e ->
            val track = e.exercise?.track ?: if (e.sets.any { it.seconds != null }) Track.Time else Track.WeightReps
            PulseCard {
                CardHeader(e.entity.name, if (e.entity.superset != null) "Superset ${e.entity.superset}" else null)
                var n = 0
                e.sets.filter { it.done }.forEach { x ->
                    if (x.kind.counts) n++
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (x.kind == SetKind.Working) "$n" else x.kind.short,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (x.kind == SetKind.Working) MaterialTheme.colorScheme.onSurface else c.moveLabel,
                            modifier = Modifier.width(24.dp),
                        )
                        if (track == Track.WeightReps) SoftButton(
                            x.grams?.let { Weights.text(it, settings.unit) } ?: "–",
                            Modifier.weight(1f),
                        ) { edit = x.id to Field.Weight }
                        if (track == Track.Time) SoftButton("${x.seconds ?: 0} sec", Modifier.weight(1f)) { edit = x.id to Field.Seconds }
                        else SoftButton(x.reps?.let { words(it, "rep") } ?: "–", Modifier.weight(1f)) { edit = x.id to Field.Reps }
                        if (settings.effort != EffortMode.Off && track != Track.Time) SoftButton(
                            x.rpe10?.let { (if (settings.effort == EffortMode.Rpe) "RPE " else "Left ") + Effort.text(it, settings.effort) } ?: "–",
                            Modifier.weight(1f),
                        ) { edit = x.id to Field.Effort }
                        IconAct(PulseIcons.Close, "Delete set $n of ${e.entity.name}", MaterialTheme.colorScheme.onSurfaceVariant) { onDeleteSet(x.id) }
                    }
                }
            }
        }
        if (s.source != "pulse") Hint("Brought in from a file. Start time and length were not in it.")
        if (fresh) MainButton("Done", onClick = onBack) else Hint("Tap a number to correct it. Records and charts follow.")
    }
    edit?.let { (id, field) ->
        val e = ui?.exercises?.firstOrNull { x -> x.sets.any { it.id == id } }
        val s = e?.sets?.firstOrNull { it.id == id }
        if (e == null || s == null) edit = null
        else NumberSheet(e, s, field, settings, { edit = null }) { change -> onChange(id, change); edit = null }
    }
    if (ask) Ask(
        "Delete this workout?",
        "It leaves your history and records. Its planned day is open again.",
        "Delete",
        { ask = false },
    ) { ask = false; onDelete() }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) = Column(modifier) {
    Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    Hint(label)
}
