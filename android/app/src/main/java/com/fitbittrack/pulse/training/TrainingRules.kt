package com.fitbittrack.pulse.training

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/*
 * Plain rules: no database, no clock, no Android. The same input always gives the same result, and every rule has a test.
 */

// ---------- schedule ----------

enum class PlannedState { Planned, Done, Skipped }

/** One workout on the calendar. [seq] is its place in the plan and never changes when its date does. */
data class PlannedItem(val id: String, val seq: Int, val date: LocalDate, val state: PlannedState, val pinned: Boolean = false)

object ScheduleRules {

    /** The first training day on or after [from]. */
    fun nextTrainingDay(from: LocalDate, days: Set<DayOfWeek>): LocalDate {
        if (days.isEmpty()) return from
        var d = from
        while (d.dayOfWeek !in days) d = d.plusDays(1)
        return d
    }

    /** [count] training days in a row from [start] on, leaving out [taken]. */
    fun dates(count: Int, start: LocalDate, days: Set<DayOfWeek>, taken: Set<LocalDate> = emptySet()): List<LocalDate> {
        val out = ArrayList<LocalDate>(count)
        var d = start
        while (out.size < count) {
            d = nextTrainingDay(d, days)
            if (d !in taken) out += d
            d = d.plusDays(1)
        }
        return out
    }

    fun moveOne(items: List<PlannedItem>, id: String, to: LocalDate): List<PlannedItem> =
        items.map { if (it.id == id && it.state == PlannedState.Planned) it.copy(date = to) else it }

    /**
     * Moves [id] and every later workout to the next training days, in order. Pinned and finished ones stay.
     * Never moves into the past: an old missed workout starts again at [today].
     */
    fun shift(items: List<PlannedItem>, id: String, days: Set<DayOfWeek>, today: LocalDate): List<PlannedItem> {
        val first = items.firstOrNull { it.id == id } ?: return items
        val start = maxOf(first.date.plusDays(1), today)
        return reflow(items, start, days) { it.seq >= first.seq }
    }

    /** Gives every open, unpinned workout that [which] picks a new date from [start] on, in plan order. */
    fun reflow(items: List<PlannedItem>, start: LocalDate, days: Set<DayOfWeek>, which: (PlannedItem) -> Boolean): List<PlannedItem> {
        val moving = items.filter { it.state == PlannedState.Planned && !it.pinned && which(it) }.sortedBy { it.seq }
        val taken = items.filter { it.state == PlannedState.Planned && it !in moving }.map { it.date }.toSet()
        val dates = dates(moving.size, start, days, taken)
        val next = moving.mapIndexed { i, x -> x.id to dates[i] }.toMap()
        return items.map { x -> next[x.id]?.let { x.copy(date = it) } ?: x }
    }

    fun swap(items: List<PlannedItem>, a: String, b: String): List<PlannedItem> {
        val x = items.firstOrNull { it.id == a && it.state == PlannedState.Planned } ?: return items
        val y = items.firstOrNull { it.id == b && it.state == PlannedState.Planned } ?: return items
        return items.map { when (it.id) { a -> it.copy(date = y.date); b -> it.copy(date = x.date); else -> it } }
    }

    fun skip(items: List<PlannedItem>, id: String): List<PlannedItem> =
        items.map { if (it.id == id && it.state == PlannedState.Planned) it.copy(state = PlannedState.Skipped) else it }

    /** No training from [from] to [until], both included. Everything from [from] on continues afterwards. */
    fun pause(items: List<PlannedItem>, from: LocalDate, until: LocalDate, days: Set<DayOfWeek>): List<PlannedItem> =
        if (until < from) items else reflow(items, until.plusDays(1), days) { it.date >= from }

    /** Those whose date or state changed. */
    fun changed(before: List<PlannedItem>, after: List<PlannedItem>): List<Pair<PlannedItem, PlannedItem>> {
        val now = after.associateBy { it.id }
        return before.sortedBy { it.seq }.mapNotNull { b -> now[b.id]?.takeIf { it != b }?.let { b to it } }
    }
}

// ---------- strength ----------

/** A finished set, as the rules need it. */
data class DoneSet(val kind: SetKind, val grams: Int?, val reps: Int?, val seconds: Int? = null, val rpe10: Int? = null)

object Strength {
    const val MAX_REPS = 12

    /** Estimated best single lift in grams: weight × (1 + reps ÷ 30). One rep counts as itself; more than [MAX_REPS] says too little. */
    fun best(grams: Int?, reps: Int?): Double? {
        if (grams == null || grams <= 0 || reps == null || reps !in 1..MAX_REPS) return null
        return if (reps == 1) grams.toDouble() else grams * (1 + reps / 30.0)
    }

    fun best(s: DoneSet): Double? = if (s.kind.counts) best(s.grams, s.reps) else null

    /** Weight times reps of the sets that count. */
    fun volume(sets: List<DoneSet>): Long = sets.filter { it.kind.counts }.sumOf { (it.grams ?: 0).toLong() * (it.reps ?: 0) }
}

/** What Pulse suggests for the working sets, and why, in one sentence. */
data class Suggestion(val grams: Int?, val reason: String, val change: Int = 0)

object Progression {

    /**
     * [last] and [before] are the working sets of the last two times, newest first. [bestGrams] is the estimated best lift.
     * [easy] is the part of the weight to use in an easy week, in percent.
     */
    fun suggest(slot: Slot, last: List<DoneSet>, before: List<DoneSet>, bestGrams: Double?, step: Int, easy: Int = 100): Suggestion {
        val s = plain(slot, last.filter(::usable), before.filter(::usable), bestGrams, step)
        if (easy >= 100 || s.grams == null) return s
        return Suggestion(Weights.round(s.grams * easy / 100.0, step), "Easy week: $easy% of the normal weight.", 0)
    }

    private fun usable(s: DoneSet) = s.kind.counts && s.kind != SetKind.Drop && s.grams != null && s.reps != null

    private fun plain(slot: Slot, last: List<DoneSet>, before: List<DoneSet>, bestGrams: Double?, step: Int): Suggestion {
        when (slot.load) {
            LoadKind.Fixed -> if (slot.growth == Growth.None || last.isEmpty()) return Suggestion(slot.loadValue.takeIf { it > 0 }, "Your plan says this weight.")
            LoadKind.Percent -> return if (bestGrams == null) Suggestion(null, "Log this exercise once, then Pulse can work out the percent.")
            else Suggestion(Weights.round(bestGrams * slot.loadValue / 100.0, step), "${slot.loadValue}% of your estimated best lift.")
            LoadKind.Effort -> Unit
        }
        if (last.isEmpty()) return Suggestion(null, "First time. Choose a weight that leaves about two reps.")
        val weight = last.maxOf { it.grams!! }
        val top = last.filter { it.grams == weight }
        val reps = top.map { it.reps!! }
        val list = reps.joinToString(", ")
        val efforts = top.mapNotNull { it.rpe10 }
        // Harder than the plan asks: more weight would be too soon.
        val tooHard = slot.load == LoadKind.Effort && efforts.isNotEmpty() && efforts.average() > slot.loadValue + 5
        val enough = top.size >= slot.working
        return when (slot.growth) {
            Growth.None -> Suggestion(weight, "The same weight as last time.")
            Growth.FillReps, Growth.AddWeekly -> {
                val goal = if (slot.growth == Growth.FillReps) slot.repsMax else slot.repsMin
                when {
                    enough && reps.all { it >= goal } && !tooHard ->
                        Suggestion(weight + step, "Last time you did $list. That reaches $goal in every set, so it is time to add weight.", step)
                    enough && reps.all { it >= goal } -> Suggestion(weight, "You reached the reps, but it was harder than planned. The same weight once more.")
                    reps.all { it < slot.repsMin } && missed(before, slot.repsMin, weight) ->
                        Suggestion(Weights.round(weight * 0.9, step), "Twice in a row below ${slot.repsMin} reps. Take a little off and build up again.", -(weight - Weights.round(weight * 0.9, step)))
                    else -> Suggestion(weight, "Last time you did $list. Aim for $goal in every set, then add weight.")
                }
            }
        }
    }

    private fun missed(before: List<DoneSet>, min: Int, weight: Int): Boolean =
        before.isNotEmpty() && before.maxOf { it.grams!! } >= weight && before.filter { it.grams == before.maxOf { x -> x.grams!! } }.all { it.reps!! < min }
}

/** Which set comes next. In a superset the exercises take turns: first sets of all, then second sets of all. */
object NextSet {
    /** [exercises]: for each exercise in order, its superset letter (or null) and which sets are done. Returns exercise and set. */
    fun of(exercises: List<Pair<String?, List<Boolean>>>): Pair<Int, Int>? {
        val i = exercises.indexOfFirst { (_, sets) -> sets.any { !it } }
        if (i < 0) return null
        val letter = exercises[i].first ?: return i to exercises[i].second.indexOfFirst { !it }
        return exercises.withIndex().filter { it.value.first == letter }
            .flatMap { (e, x) -> x.second.mapIndexedNotNull { s, done -> if (done) null else e to s } }
            .minWith(compareBy({ it.second }, { it.first }))
    }
}

/** Which plates go on each side of the bar. */
object Plates {
    /** Plates for one side, heaviest first, and what could not be loaded (grams, both sides). */
    fun side(total: Int, bar: Int, plates: List<Int>): Pair<List<Int>, Int> {
        var left = (total - bar) / 2
        if (left < -TOLERANCE) return emptyList<Int>() to total - bar
        val out = mutableListOf<Int>()
        // Pounds as whole grams can be a gram off, which must not make the last plate "not fit".
        for (p in plates.sortedDescending()) while (p in 1..left + TOLERANCE && out.size < 20) { out += p; left -= p }
        return out to if (kotlin.math.abs(left) <= TOLERANCE) 0 else left * 2
    }

    private const val TOLERANCE = 10
}

/** "8, so 2 reps left". */
object Effort {
    fun repsLeft(rpe10: Int): Double = (100 - rpe10) / 10.0

    fun text(rpe10: Int, mode: EffortMode): String = when (mode) {
        EffortMode.RepsLeft -> Weights.plain(repsLeft(rpe10))
        else -> Weights.plain(rpe10 / 10.0)
    }

    fun about(rpe10: Int): String {
        val n = repsLeft(rpe10)
        return when { n <= 0 -> "nothing left"; n == 1.0 -> "1 rep left"; else -> "${Weights.plain(n)} reps left" }
    }
}

// ---------- progress ----------

data class DatedSet(val sessionId: String, val date: LocalDate, val exerciseId: String, val set: DoneSet)

data class Best(val exerciseId: String, val grams: Double, val date: LocalDate, val sessionId: String, val weight: Int, val reps: Int)

object ProgressRules {

    /** The best estimated lift of each session, oldest first. */
    fun curve(sets: List<DatedSet>): List<Pair<LocalDate, Double>> =
        sets.groupBy { it.sessionId }.mapNotNull { (_, l) -> l.mapNotNull { Strength.best(it.set) }.maxOrNull()?.let { l.first().date to it } }.sortedBy { it.first }

    /** The best lift of each exercise. The earliest day wins a tie, so a record is the day it was set. */
    fun bests(sets: List<DatedSet>): Map<String, Best> {
        val out = HashMap<String, Best>()
        sets.sortedBy { it.date }.forEach { s ->
            val b = Strength.best(s.set) ?: return@forEach
            val old = out[s.exerciseId]
            if (old == null || b > old.grams + 0.5) out[s.exerciseId] = Best(s.exerciseId, b, s.date, s.sessionId, s.set.grams!!, s.set.reps!!)
        }
        return out
    }

    /** Heaviest weight done for at least [reps] reps. */
    fun heaviestFor(sets: List<DatedSet>, reps: Int): Int? =
        sets.filter { it.set.kind.counts && (it.set.reps ?: 0) >= reps }.mapNotNull { it.set.grams }.filter { it > 0 }.maxOrNull()

    /** Sets that count, per muscle. A secondary muscle gets half a set. */
    fun muscleSets(sets: List<DatedSet>, primary: (String) -> Muscle?, secondary: (String) -> List<Muscle>): Map<Muscle, Double> {
        val out = HashMap<Muscle, Double>()
        sets.filter { it.set.kind.counts }.forEach { s ->
            primary(s.exerciseId)?.let { out[it] = (out[it] ?: 0.0) + 1.0 }
            secondary(s.exerciseId).forEach { out[it] = (out[it] ?: 0.0) + 0.5 }
        }
        return out
    }

    /** Weeks in a row with at least one workout, up to this week. This week may still be empty. */
    fun weekStreak(days: Set<LocalDate>, today: LocalDate, firstDay: DayOfWeek): Pair<Int, Int> {
        fun start(d: LocalDate): LocalDate = d.minusDays(((d.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
        val weeks = days.map(::start).toSortedSet()
        if (weeks.isEmpty()) return 0 to 0
        var best = 0; var run = 0; var prev: LocalDate? = null
        weeks.forEach { w -> run = if (prev != null && ChronoUnit.WEEKS.between(prev, w) == 1L) run + 1 else 1; best = maxOf(best, run); prev = w }
        val now = start(today)
        var d = if (now in weeks) now else now.minusWeeks(1)
        var cur = 0
        while (d in weeks) { cur++; d = d.minusWeeks(1) }
        return cur to best
    }

    /** True when two times overlap for at least half of the shorter one: the same visit to the gym, seen by two apps. */
    fun sameVisit(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean {
        val both = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
        val shorter = minOf(aEnd - aStart, bEnd - bStart)
        return both > 0 && shorter > 0 && both * 2 >= shorter
    }

    fun percent(part: Int, all: Int): Int = if (all <= 0) 0 else (part * 100.0 / all).roundToInt()
}
