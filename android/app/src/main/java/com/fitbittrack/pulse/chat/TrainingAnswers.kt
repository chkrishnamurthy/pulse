package com.fitbittrack.pulse.chat

import com.fitbittrack.pulse.training.DatedSet
import com.fitbittrack.pulse.training.ImportNames
import com.fitbittrack.pulse.training.ProgressRules
import com.fitbittrack.pulse.training.WeightUnit
import com.fitbittrack.pulse.training.Weights
import com.fitbittrack.pulse.ui.model.Tone
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** What the chat may know about training, from the training database on this phone. */
data class TrainingChatData(
    val today: LocalDate, val sets: List<DatedSet>, val names: Map<String, String>, val workoutDays: List<LocalDate>,
    /** Name and day of the next planned workout. */
    val next: Pair<String, LocalDate>?, val planTitle: String?, val unit: WeightUnit, val locale: Locale = Locale.getDefault(),
)

/** Questions about lifting and the plan. Plain rules, like the rest of the chat. Null when a question is about something else. */
object TrainingAnswers {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val nextWorkout = rx("(next|today'?s|tomorrow'?s) (workout|training|session)|when (do|should|will) i (train|lift|work ?out)|(what|which) (workout|training).*(today|next|tomorrow)|when is my (next )?(workout|training)")
    private val records = rx("\\b(my )?(records?|prs?|personal bests?)\\b")
    private val lifts = rx("bench|press|squat|dead ?lift|\\brows?\\b|pull ?down|push ?down|\\bcurls?\\b|lunge|\\bdips?\\b|pull ?ups?|chin ?ups?|push ?ups?|extension|raises?\\b|\\bfl(y|ies|ye)\\b|thrust|shrug|plank|clean\\b|snatch|kettlebell|dumbbell|barbell")

    fun answer(question: String, d: TrainingChatData): Answer? {
        val q = question.trim()
        return when {
            nextWorkout.containsMatchIn(q) -> next(d)
            lifts.containsMatchIn(q) -> lift(q, d)
            records.containsMatchIn(q) && d.sets.isNotEmpty() -> best(d)
            else -> null
        }
    }

    private fun day(date: LocalDate, d: TrainingChatData): String = when (date) {
        d.today -> "today"
        d.today.plusDays(1) -> "tomorrow"
        else -> "on " + date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", d.locale))
    }

    private fun number(grams: Double, d: TrainingChatData) = Weights.plain((grams / d.unit.grams * 10).roundToLong() / 10.0)

    private fun plain(title: String, big: String, direct: String, used: List<Pair<String, String>>, data: Boolean, ask: List<String>) =
        Answer(Topic.Strength, Tone.Active, title, null, big, null, emptyList(), null, direct, used, emptyList(), ask, data)

    private fun next(d: TrainingChatData): Answer {
        val n = d.next ?: return plain("Next workout", "–", "No workout is planned. Open Train to start a plan.", emptyList(), false, listOf("How many workouts did I do this week?"))
        val (title, date) = n
        val text = if (date < d.today) "$title was planned for ${date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", d.locale))} and is still open." else "Your next workout is $title, ${day(date, d)}."
        return plain("Next workout", title, text, listOf("Plan" to (d.planTitle ?: "–")), true, listOf("What are my records?"))
    }

    /** The logged exercise that fits the question best. */
    internal fun find(q: String, d: TrainingChatData): String? {
        val words = ImportNames.plain(q).split(" ").toSet()
        val times = d.sets.groupBy { it.exerciseId }.mapValues { (_, l) -> l.map { it.sessionId }.distinct().size }
        return d.names.entries.map { (id, n) ->
            val name = ImportNames.plain(n).split(" ").toSet()
            Triple(id, (name intersect words).size, times[id] ?: 0)
        }.filter { it.second > 0 }.maxWithOrNull(compareBy({ it.second }, { it.third }))?.first
    }

    private fun lift(q: String, d: TrainingChatData): Answer {
        val id = find(q, d) ?: return plain("Training", "–", "Pulse has no logged sets for that exercise yet. After your first workout with it you can ask again.", emptyList(), false, listOf("When is my next workout?"))
        val name = d.names.getValue(id)
        val mine = d.sets.filter { it.exerciseId == id }
        val curve = ProgressRules.curve(mine)
        if (curve.isEmpty()) {
            val top = mine.filter { it.set.kind.counts }.maxOfOrNull { it.set.reps ?: it.set.seconds ?: 0 } ?: 0
            return plain(name, "$top", "Your best set of $name is $top. Pulse needs weight and 1 to 12 reps to estimate a best lift.", listOf("Workouts" to "${mine.map { it.sessionId }.distinct().size}"), true, listOf("When is my next workout?"))
        }
        val best = curve.maxOf { it.second }
        val first = curve.first()
        val last = curve.last()
        val change = last.second - first.second
        val since = first.first.format(DateTimeFormatter.ofPattern("d MMMM", d.locale))
        val direct = "Your estimated best lift for $name is ${number(best, d)} ${d.unit.label}." + when {
            curve.size < 2 -> " Log it once more to see how it changes."
            change >= 250 -> " The last workout reached ${number(last.second, d)} ${d.unit.label}, up ${number(change, d)} ${d.unit.label} since $since."
            change <= -250 -> " The last workout reached ${number(last.second, d)} ${d.unit.label}, ${number(-change, d)} ${d.unit.label} less than on $since."
            else -> " The last workout was about the same as on $since."
        }
        val bars = curve.takeLast(8).map { (date, v) -> Bar("${date.dayOfMonth}", v / d.unit.grams, v >= best - 1) }
        return Answer(
            Topic.Strength, Tone.Active, name, "${curve.size} ${if (curve.size == 1) "workout" else "workouts"}", number(best, d), d.unit.label, if (bars.size > 1) bars else emptyList(),
            if (bars.size > 1) "Each bar is one workout. The bright one is the best." else null, direct,
            listOf("Exercise" to name, "Workouts" to "${curve.size}", "Rule" to "weight × (1 + reps ÷ 30)"), emptyList(),
            listOf("When is my next workout?", "What are my records?"), true, fromZero = false,
        )
    }

    private fun best(d: TrainingChatData): Answer? {
        val top = ProgressRules.bests(d.sets).values.sortedByDescending { it.grams }.take(3)
        if (top.isEmpty()) return null
        val text = top.joinToString(", ") { "${d.names[it.exerciseId]} ${number(it.grams, d)} ${d.unit.label}" }
        return plain("Your records", "${top.size}", "Your best estimated lifts: $text.", top.map { (d.names[it.exerciseId] ?: "Exercise") to "${number(it.grams, d)} ${d.unit.label}" }, true, listOf("When is my next workout?"))
    }
}
