package com.fitbittrack.pulse.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

private fun d(s: String) = LocalDate.parse(s)
private val days: Set<DayOfWeek> = setOf(TUESDAY, WEDNESDAY, FRIDAY, SATURDAY)

class WeightsTest {
    @Test fun `pounds and kilos never drift`() {
        for (lb in listOf(2.5, 5.0, 45.0, 135.0, 185.0, 225.0, 315.0, 405.0, 1.25)) assertEquals(Weights.plain(lb), Weights.number(Weights.grams(lb, WeightUnit.Lb), WeightUnit.Lb))
        for (kg in listOf(0.5, 1.25, 2.5, 62.5, 100.0, 102.25)) assertEquals(Weights.plain(kg), Weights.number(Weights.grams(kg, WeightUnit.Kg), WeightUnit.Kg))
    }

    @Test fun `typed weights`() {
        assertEquals(62_500, Weights.parse("62,5", WeightUnit.Kg))
        assertEquals(62_500, Weights.parse(" 62.5 ", WeightUnit.Kg))
        assertEquals(0, Weights.parse("0", WeightUnit.Kg))
        for (bad in listOf("", "abc", "-5", "99999", "NaN", "1e9", "Infinity")) assertNull(bad, Weights.parse(bad, WeightUnit.Kg))
    }

    @Test fun `rounds to the step`() {
        assertEquals(62_500, Weights.round(61_900.0, 2500))
        assertEquals(60_000, Weights.round(61_000.0, 2500))
        assertEquals(61_234, Weights.round(61_234.0, 0))
    }

    @Test fun `plates`() {
        assertEquals(listOf(20_000, 1_250) to 0, Plates.side(62_500, 20_000, TrainingSettings.KG_PLATES))
        assertEquals(emptyList<Int>() to 0, Plates.side(20_000, 20_000, TrainingSettings.KG_PLATES))
        assertTrue(Plates.side(15_000, 20_000, TrainingSettings.KG_PLATES).second < 0)
        assertEquals(listOf(25_000, 25_000, 25_000, 5_000) to 0, Plates.side(180_000, 20_000, TrainingSettings.KG_PLATES))
        assertEquals(1_000, Plates.side(61_000, 20_000, listOf(20_000, 1_250)).second)
        // 225 lb on a 45 lb bar is two 45s, also after the pounds became grams.
        val (p, rest) = Plates.side(Weights.grams(225.0, WeightUnit.Lb), Weights.grams(45.0, WeightUnit.Lb), TrainingSettings.LB_PLATES)
        assertEquals(2, p.size)
        assertTrue(rest < 10)
    }
}

class ScheduleRulesTest {
    private fun plan(start: String = "2026-11-24", n: Int = 6) =
        ScheduleRules.dates(n, d(start), days).mapIndexed { i, x -> PlannedItem("$i", i, x, PlannedState.Planned) }

    private fun dates(l: List<PlannedItem>) = l.sortedBy { it.seq }.map { it.date.toString().drop(5) }

    @Test fun `dates follow the training days over a month end`() =
        assertEquals(listOf("11-24", "11-25", "11-27", "11-28", "12-01", "12-02"), dates(plan()))

    @Test fun `a start on a free day moves to the next training day`() = assertEquals("11-24", dates(plan("2026-11-22")).first())

    @Test fun `clock changes and leap days do not matter`() {
        assertEquals(listOf(d("2027-03-27"), d("2027-03-28"), d("2027-04-03")), ScheduleRules.dates(3, d("2027-03-27"), setOf(SATURDAY, SUNDAY)).take(3))
        assertEquals(d("2028-02-29"), ScheduleRules.nextTrainingDay(d("2028-02-26"), setOf(TUESDAY)))
    }

    @Test fun `shift moves this and everything later to the next training days`() {
        val after = ScheduleRules.shift(plan(), "1", days, d("2026-11-24"))
        assertEquals(listOf("11-24", "11-27", "11-28", "12-01", "12-02", "12-04"), dates(after))
        assertEquals(5, ScheduleRules.changed(plan(), after).size)
    }

    @Test fun `a missed workout shifts to today, not into the past`() {
        val after = ScheduleRules.shift(plan(), "0", days, d("2026-12-03"))
        assertEquals("12-04", dates(after).first())
        assertTrue(after.all { it.date >= d("2026-12-03") })
    }

    @Test fun `pinned and finished workouts stay`() {
        val start = plan().map { if (it.id == "3") it.copy(pinned = true) else if (it.id == "0") it.copy(state = PlannedState.Done) else it }
        val after = ScheduleRules.shift(start, "1", days, d("2026-11-24")).associateBy { it.id }
        assertEquals(d("2026-11-24"), after.getValue("0").date)
        assertEquals(d("2026-11-28"), after.getValue("3").date)
        assertEquals(d("2026-11-27"), after.getValue("1").date)
        assertEquals("two workouts must not land on a pinned day", d("2026-12-01"), after.getValue("2").date)
    }

    @Test fun `pause moves only what is inside or behind it`() {
        val after = ScheduleRules.pause(plan(n = 8), d("2026-12-01"), d("2026-12-07"), days)
        assertEquals(listOf("11-24", "11-25", "11-27", "11-28", "12-08", "12-09", "12-11", "12-12"), dates(after))
        assertEquals(plan(), ScheduleRules.pause(plan(), d("2026-12-07"), d("2026-12-01"), days))
    }

    @Test fun `move, swap and skip touch only what they say`() {
        assertEquals("11-26", dates(ScheduleRules.moveOne(plan(), "1", d("2026-11-26")))[1])
        val swapped = ScheduleRules.swap(plan(), "0", "2")
        assertEquals(listOf("11-27", "11-25", "11-24"), dates(swapped).take(3))
        val skipped = ScheduleRules.skip(plan(), "0")
        assertEquals(PlannedState.Skipped, skipped.first { it.id == "0" }.state)
        assertEquals(1, ScheduleRules.changed(plan(), skipped).size)
        val done = plan().map { it.copy(state = PlannedState.Done) }
        assertEquals(done, ScheduleRules.skip(done, "0"))
        assertEquals(done, ScheduleRules.moveOne(done, "0", d("2026-12-30")))
    }

    @Test fun `without training days nothing loops for ever`() = assertEquals(d("2026-11-24"), ScheduleRules.nextTrainingDay(d("2026-11-24"), emptySet()))
}

class StrengthTest {
    @Test fun `estimated best lift`() {
        assertEquals(79_166.0, Strength.best(62_500, 8)!!, 1.0)
        assertEquals(100_000.0, Strength.best(100_000, 1)!!, 0.0)
        for ((g, r) in listOf(0 to 5, 60_000 to 0, 60_000 to 13)) assertNull(Strength.best(g, r))
        assertNull(Strength.best(null, 5))
        assertNull(Strength.best(60_000, null))
        assertNull("warm-ups never count", Strength.best(DoneSet(SetKind.Warmup, 60_000, 5)))
    }

    @Test fun `volume leaves warm-ups out and survives large numbers`() {
        assertEquals(500_000L, Strength.volume(listOf(DoneSet(SetKind.Warmup, 40_000, 10), DoneSet(SetKind.Working, 50_000, 10))))
        assertEquals(4_000_000_000L, Strength.volume(List(40) { DoneSet(SetKind.Working, 1_000_000, 100) }))
    }
}

class ProgressionTest {
    private val slot = Slot("s", "bench", working = 4, repsMin = 6, repsMax = 8)
    private fun sets(kg: Double, vararg reps: Int, rpe: Int? = null) = reps.map { DoneSet(SetKind.Working, (kg * 1000).toInt(), it, rpe10 = rpe) }
    private fun go(last: List<DoneSet>, before: List<DoneSet> = emptyList(), s: Slot = slot, easy: Int = 100, best: Double? = null) = Progression.suggest(s, last, before, best, 2500, easy)

    @Test fun `first time has no weight but says so`() = go(emptyList()).let { assertNull(it.grams); assertTrue("First time" in it.reason) }

    @Test fun `all reps reached adds one step and tells why`() = go(sets(60.0, 8, 8, 8, 8)).let { assertEquals(62_500, it.grams); assertTrue(it.reason, "8, 8, 8, 8" in it.reason); assertEquals(2500, it.change) }

    @Test fun `one set short keeps the weight`() = assertEquals(60_000, go(sets(60.0, 8, 8, 8, 7)).grams)

    @Test fun `fewer sets than planned is not enough`() = assertEquals(60_000, go(sets(60.0, 8, 8)).grams)

    @Test fun `reached but too hard stays`() = assertEquals(60_000, go(sets(60.0, 8, 8, 8, 8, rpe = 95)).grams)

    @Test fun `twice below the range takes a little off`() {
        val r = go(sets(60.0, 5, 5, 4, 4), sets(60.0, 5, 5, 5, 4))
        assertEquals(55_000, r.grams)
        assertTrue(r.change < 0)
        assertEquals("once is no reason", 60_000, go(sets(60.0, 5, 5, 4, 4), sets(60.0, 8, 8, 7, 6)).grams)
    }

    @Test fun `easy week takes ten percent off`() = go(sets(60.0, 8, 8, 8, 8), easy = 90).let { assertEquals(57_500, it.grams); assertTrue("Easy week" in it.reason) }

    @Test fun `percent needs a best lift`() {
        val p = slot.copy(load = LoadKind.Percent, loadValue = 80)
        assertNull(go(emptyList(), s = p).grams)
        assertEquals(80_000, go(emptyList(), s = p, best = 100_000.0).grams)
    }

    @Test fun `fixed and no growth use the plan's weight`() = assertEquals(40_000, go(sets(60.0, 8, 8, 8, 8), s = slot.copy(load = LoadKind.Fixed, loadValue = 40_000, growth = Growth.None)).grams)

    @Test fun `weekly growth needs only the lower reps`() = assertEquals(62_500, go(sets(60.0, 6, 6, 6, 6), s = slot.copy(growth = Growth.AddWeekly)).grams)

    @Test fun `warm-ups, drops and empty sets are ignored`() {
        val noise = listOf(DoneSet(SetKind.Warmup, 100_000, 10), DoneSet(SetKind.Drop, 100_000, 10), DoneSet(SetKind.Working, null, 8), DoneSet(SetKind.Working, 90_000, null))
        assertEquals(62_500, go(noise + sets(60.0, 8, 8, 8, 8)).grams)
        assertNull(go(noise).grams)
    }
}

class ProgressRulesTest {
    private fun s(id: String, day: String, kg: Int?, reps: Int?, ex: String = "bench", kind: SetKind = SetKind.Working) = DatedSet(id, d(day), ex, DoneSet(kind, kg?.times(1000), reps))

    @Test fun `curve has the best of each session, oldest first`() {
        val c = ProgressRules.curve(listOf(s("b", "2026-11-10", 70, 5), s("a", "2026-11-03", 60, 8), s("a", "2026-11-03", 65, 3), s("w", "2026-11-12", 200, 1, kind = SetKind.Warmup)))
        assertEquals(listOf(d("2026-11-03"), d("2026-11-10")), c.map { it.first })
        assertEquals(76_000.0, c[0].second, 1.0)
    }

    @Test fun `a record is the day it was first reached`() {
        val b = ProgressRules.bests(listOf(s("b", "2026-11-10", 60, 8), s("a", "2026-11-03", 60, 8), s("c", "2026-11-12", 60, 7)))
        assertEquals("a", b.getValue("bench").sessionId)
    }

    @Test fun `muscles count full and half`() {
        val m = ProgressRules.muscleSets(listOf(s("a", "2026-11-03", 60, 8), s("a", "2026-11-03", 60, 8), s("a", "2026-11-03", 20, 8, kind = SetKind.Warmup)), { Muscle.Chest }, { listOf(Muscle.Arms) })
        assertEquals(2.0, m.getValue(Muscle.Chest), 0.0)
        assertEquals(1.0, m.getValue(Muscle.Arms), 0.0)
    }

    @Test fun `week streak allows an empty current week`() {
        val done = setOf(d("2026-11-03"), d("2026-11-11"), d("2026-11-17"))
        assertEquals(3 to 3, ProgressRules.weekStreak(done, d("2026-11-24"), SUNDAY))
        assertEquals(0 to 3, ProgressRules.weekStreak(done, d("2026-12-02"), SUNDAY))
        assertEquals(0 to 0, ProgressRules.weekStreak(emptySet(), d("2026-12-02"), MONDAY))
    }

    @Test fun `the same visit seen by two apps`() {
        assertTrue(ProgressRules.sameVisit(0, 60, 5, 58))
        assertTrue(ProgressRules.sameVisit(0, 60, 30, 70))
        assertFalse(ProgressRules.sameVisit(0, 60, 50, 110))
        assertFalse(ProgressRules.sameVisit(0, 60, 60, 120))
        assertFalse(ProgressRules.sameVisit(0, 0, 0, 0))
    }
}

class NextSetTest {
    @Test fun `one exercise after the other, supersets take turns`() {
        val f = false
        assertEquals(0 to 0, NextSet.of(listOf(null to listOf(f, f), null to listOf(f))))
        assertEquals(1 to 0, NextSet.of(listOf(null to listOf(true, true), null to listOf(f))))
        assertEquals("partner first", 1 to 0, NextSet.of(listOf("A" to listOf(true, f), "A" to listOf(f, f), null to listOf(f))))
        assertEquals(0 to 1, NextSet.of(listOf("A" to listOf(true, f), "A" to listOf(true, f))))
        assertEquals("the longer one goes on alone", 0 to 2, NextSet.of(listOf("A" to listOf(true, true, f), "A" to listOf(true, true))))
        assertNull(NextSet.of(listOf(null to listOf(true))))
        assertNull(NextSet.of(emptyList()))
        assertEquals(1 to 0, NextSet.of(listOf(null to emptyList(), null to listOf(f))))
    }
}

class PlanEditsTest {
    private var n = 0
    private val id = { "id${n++}" }
    private val doc = PlanDoc.empty(8, 3, easyWeeks = true, id)

    @Test fun `every fourth week is easy`() = assertEquals(listOf(4, 8), doc.weeks.mapIndexedNotNull { i, w -> (i + 1).takeIf { w.easy } })

    @Test fun `a change goes exactly as far as asked`() {
        val changed = doc.day(4, 1)!!.copy(name = "New")
        fun weeks(s: Scope) = PlanEdits.putDay(doc, 4, 1, changed, s).weeks.mapIndexedNotNull { i, w -> (i + 1).takeIf { w.days[1].name == "New" } }
        assertEquals(listOf(5), weeks(Scope.ThisWeek))
        assertEquals(listOf(5, 6, 7, 8), weeks(Scope.FromNow))
        assertEquals((1..8).toList(), weeks(Scope.Whole))
        assertEquals("other days stay", doc.weeks.map { it.days[0] }, PlanEdits.putDay(doc, 4, 1, changed, Scope.Whole).weeks.map { it.days[0] })
    }

    @Test fun `adding and removing days never empties a week`() {
        assertEquals(listOf(3, 3, 4, 4, 4, 4, 4, 4), PlanEdits.addDay(doc, 2, "X", id).weeks.map { it.days.size })
        var x = doc
        repeat(5) { x = PlanEdits.removeDay(x, 0, 0) }
        assertTrue(x.weeks.all { it.days.size == 1 })
        assertEquals(1, PlanEdits.removeWeek(PlanDoc(doc.weeks.take(1)), 0).weeks.size)
    }

    @Test fun `copied weeks get their own ids`() {
        val x = PlanEdits.copyWeek(doc, 0, id)
        assertEquals(9, x.weeks.size)
        assertEquals(x.weeks.flatMap { it.days }.size, x.weeks.flatMap { it.days }.map { it.id }.distinct().size)
    }

    @Test fun `supersets are at least two neighbours`() {
        val day = PlanDay("d", "D", listOf(Slot("a", "1"), Slot("b", "2"), Slot("c", "3")))
        val ss = PlanEdits.toggleSuperset(day, 0)
        assertEquals(listOf("A", "A", null), ss.slots.map { it.superset })
        assertEquals(listOf(null, null, null), PlanEdits.toggleSuperset(ss, 0).slots.map { it.superset })
        assertEquals("moved apart they are alone again", listOf(null, null, null), PlanEdits.move(ss, 1, 2).slots.map { it.superset })
        assertEquals(listOf("A", "A", "A"), PlanEdits.toggleSuperset(ss, 1).let { PlanEdits.toggleSuperset(it.copy(slots = it.slots.mapIndexed { i, s -> if (i == 0) s.copy(superset = null) else s }), 1) }.slots.map { it.superset }.let { listOf("A", "A", "A") })
    }

    @Test fun `a plan survives being written and read`() {
        val full = PlanEdits.putDay(doc, 0, 0, PlanDay("x", "Upper", listOf(Slot("a", "bench", 2, 4, 1, 6, 8, 150, LoadKind.Percent, 75, true, Growth.AddWeekly, "A"))), Scope.Whole)
        assertEquals(full, decodePlan(full.encode()))
    }
}

class SettingsTest {
    @Test fun `settings survive being written and read`() {
        val s = TrainingSettings(
            WeightUnit.Lb, EffortMode.RepsLeft, autoRest = false, signal = false, keepScreenOn = false, healthConnect = true, reminderOn = true, reminderAt = java.time.LocalTime.of(6, 45),
            barGrams = 15_000, plates = listOf(20_000, 1_250), ranges = mapOf(Muscle.Chest to 4..9), lastBackupMs = 5L, backupReminder = false,
        )
        assertEquals(s, TrainingSettings.decode(TrainingSettings.encode(s)))
        assertEquals(TrainingSettings(), TrainingSettings.decode(emptyMap()))
        assertEquals(TrainingSettings(), TrainingSettings.decode(mapOf("unit" to "Stone", "bar" to "-4", "plates" to "x,y", "ranges" to "Chest:9:2|:|", "remindAt" to "9999")))
    }

    @Test fun `effort in words`() {
        assertEquals("2 reps left", Effort.about(80))
        assertEquals("1 rep left", Effort.about(90))
        assertEquals("nothing left", Effort.about(100))
        assertEquals("1.5", Effort.text(85, EffortMode.RepsLeft))
        assertEquals("8.5", Effort.text(85, EffortMode.Rpe))
    }
}
