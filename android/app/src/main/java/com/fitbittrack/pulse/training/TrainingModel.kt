package com.fitbittrack.pulse.training

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What a set of an exercise counts. */
@Serializable
enum class Track(val label: String) { WeightReps("Weight + reps"), Reps("Reps"), Time("Time") }

@Serializable
enum class Equipment(val label: String) {
    Barbell("Barbell"), Dumbbell("Dumbbell"), Machine("Machine"), Cable("Cable"), Kettlebell("Kettlebell"), Body("Body"), Band("Band"), Other("Other"),
}

/** Muscle groups, as wide as the weekly set count needs them. */
@Serializable
enum class Muscle(val label: String) {
    Chest("Chest"), Back("Back"), Shoulders("Shoulders"), Arms("Arms"), FrontLegs("Front of legs"), BackLegs("Back of legs"), Calves("Calves"), Core("Core"), Other("Other"),
}

@Serializable
enum class WeightUnit(val label: String, val grams: Double) { Kg("kg", 1000.0), Lb("lb", 453.59237) }

/** How effort is shown. It is always saved as RPE. */
@Serializable
enum class EffortMode(val label: String) { Rpe("RPE 6–10"), RepsLeft("Reps left"), Off("Off") }

/** Weights are saved as whole grams, so kilos and pounds never drift. */
object Weights {
    fun grams(value: Double, unit: WeightUnit): Int = (value * unit.grams).roundToInt()

    fun value(grams: Int, unit: WeightUnit): Double = (grams / unit.grams * 100).roundToLong() / 100.0

    /** "62.5", "225", never "224.99". */
    fun number(grams: Int, unit: WeightUnit): String = plain(value(grams, unit))

    fun text(grams: Int, unit: WeightUnit): String = "${number(grams, unit)} ${unit.label}"

    fun plain(v: Double): String =
        if (abs(v - v.roundToLong()) < 0.005) "${v.roundToLong()}" else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')

    /** What was typed, with a comma or a point. Null when it is not a sensible weight. */
    fun parse(text: String, unit: WeightUnit): Int? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..2000.0 }?.let { grams(it, unit) }

    /** Nearest weight that can be loaded in steps of [step] grams. */
    fun round(grams: Double, step: Int): Int = if (step <= 0) grams.roundToInt() else ((grams / step).roundToLong() * step).toInt()
}

/** Which kind a set is. Only [counts] sets are used for records, volume and muscle sets. */
@Serializable
enum class SetKind(val label: String, val short: String, val counts: Boolean) {
    Warmup("Warm-up", "W", false), Working("Working", "", true), Drop("Drop set", "D", true), Max("As many as you can", "M", true), Failure("To failure", "F", true),
}

@Serializable
enum class LoadKind(val label: String) { Percent("% of best"), Effort("Effort"), Fixed("Fixed") }

@Serializable
enum class Growth(val label: String, val about: String) {
    FillReps("Fill the reps, then add weight", "When every set reaches the top reps"),
    AddWeekly("Add weight each time", "When every set reaches the lower reps"),
    None("Keep it the same", "You decide the weight"),
}

/** One exercise in a training day of a plan. For timed exercises the reps are seconds. */
@Serializable
data class Slot(
    val id: String,
    val exerciseId: String,
    val warmups: Int = 0,
    val working: Int = 3,
    val drops: Int = 0,
    val repsMin: Int = 8,
    val repsMax: Int = 10,
    val restSec: Int = 120,
    val load: LoadKind = LoadKind.Effort,
    /** RPE × 10, percent, or grams, as [load] says. */
    val loadValue: Int = 80,
    val lastMax: Boolean = false,
    val growth: Growth = Growth.FillReps,
    /** Slots with the same letter, one after the other, are a superset. */
    val superset: String? = null,
) {
    val sets: Int get() = warmups + working + drops
}

@Serializable
data class PlanDay(val id: String, val name: String, val slots: List<Slot> = emptyList()) {
    val sets: Int get() = slots.sumOf { it.working + it.drops }

    /** About how long: 40 seconds a set and its rest, a little for each change of exercise. */
    val minutes: Int get() = (slots.sumOf { s -> s.sets * (40 + if (s.superset == null) s.restSec else s.restSec / 2) } / 60.0 + slots.size).roundToInt()
}

@Serializable
data class PlanWeek(val days: List<PlanDay>, val easy: Boolean = false)

/** A whole plan. Never changed once saved: an edit makes a new one, so history stays as it was. */
@Serializable
data class PlanDoc(val weeks: List<PlanWeek>) {
    fun day(week: Int, day: Int): PlanDay? = weeks.getOrNull(week)?.days?.getOrNull(day)

    companion object {
        /** [weeks] empty weeks with the same days. Every fourth one is an easy week when [easyWeeks]. */
        fun empty(weeks: Int, days: Int, easyWeeks: Boolean, id: () -> String): PlanDoc {
            val names = (0 until days).map { "Day ${it + 1}" }
            return PlanDoc((0 until weeks).map { w -> PlanWeek(names.map { PlanDay(id(), it) }, easy = easyWeeks && (w + 1) % 4 == 0) })
        }
    }
}

/** How far a change to a training day goes. */
enum class Scope { ThisWeek, FromNow, Whole }

object PlanEdits {
    /** Puts [changed] in place of the day at [week]/[day]. The same day of other weeks follows as [scope] says. Past weeks keep theirs unless the whole plan is meant. */
    fun putDay(doc: PlanDoc, week: Int, day: Int, changed: PlanDay, scope: Scope): PlanDoc = doc.copy(
        weeks = doc.weeks.mapIndexed { w, x ->
            val hit = when (scope) { Scope.ThisWeek -> w == week; Scope.FromNow -> w >= week; Scope.Whole -> true }
            if (!hit || day !in x.days.indices) x else x.copy(days = x.days.mapIndexed { d, old -> if (d == day) changed.copy(id = old.id) else old })
        },
    )

    fun addDay(doc: PlanDoc, week: Int, name: String, id: () -> String): PlanDoc =
        doc.copy(weeks = doc.weeks.mapIndexed { w, x -> if (w >= week) x.copy(days = x.days + PlanDay(id(), name)) else x })

    fun removeDay(doc: PlanDoc, week: Int, day: Int): PlanDoc =
        doc.copy(weeks = doc.weeks.mapIndexed { w, x -> if (w >= week && x.days.size > 1) x.copy(days = x.days.filterIndexed { d, _ -> d != day }) else x })

    /** A copy of [week] right after it. */
    fun copyWeek(doc: PlanDoc, week: Int, id: () -> String): PlanDoc {
        val src = doc.weeks.getOrNull(week) ?: return doc
        return doc.copy(weeks = doc.weeks.toMutableList().apply { add(week + 1, src.copy(days = src.days.map { it.copy(id = id()) })) })
    }

    fun removeWeek(doc: PlanDoc, week: Int): PlanDoc =
        if (doc.weeks.size <= 1) doc else doc.copy(weeks = doc.weeks.filterIndexed { w, _ -> w != week })

    fun toggleEasy(doc: PlanDoc, week: Int): PlanDoc =
        doc.copy(weeks = doc.weeks.mapIndexed { w, x -> if (w == week) x.copy(easy = !x.easy) else x })

    fun move(day: PlanDay, from: Int, to: Int): PlanDay {
        if (from !in day.slots.indices || to !in day.slots.indices) return day
        return day.copy(slots = tidy(day.slots.toMutableList().apply { add(to, removeAt(from)) }))
    }

    /** Joins a slot with the next one into a superset, or takes it out again. */
    fun toggleSuperset(day: PlanDay, i: Int): PlanDay {
        val s = day.slots.getOrNull(i) ?: return day
        if (s.superset != null) return day.copy(slots = tidy(day.slots.mapIndexed { k, x -> if (k == i) x.copy(superset = null) else x }))
        val next = day.slots.getOrNull(i + 1) ?: return day
        val letter = next.superset ?: ('A'..'Z').map { it.toString() }.first { l -> day.slots.none { it.superset == l } }
        return day.copy(slots = tidy(day.slots.mapIndexed { k, x -> if (k == i || k == i + 1) x.copy(superset = letter) else x }))
    }

    /** A superset is at least two neighbours with the same letter; anything else stands alone. */
    fun tidy(slots: List<Slot>): List<Slot> = slots.mapIndexed { i, s ->
        val l = s.superset ?: return@mapIndexed s
        if (slots.getOrNull(i - 1)?.superset == l || slots.getOrNull(i + 1)?.superset == l) s else s.copy(superset = null)
    }
}
