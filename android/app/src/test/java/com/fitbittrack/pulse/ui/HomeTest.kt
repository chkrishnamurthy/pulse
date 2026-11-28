package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.air.AirReading
import com.fitbittrack.pulse.air.AirSource
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.goals.GoalProgress
import com.fitbittrack.pulse.ui.model.Mappers
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.HomeLive
import com.fitbittrack.pulse.ui.model.SyncUi
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.prefs.decodeRingGoals
import com.fitbittrack.pulse.ui.prefs.decodeTodayCards
import com.fitbittrack.pulse.ui.screens.BandLook
import com.fitbittrack.pulse.ui.screens.airFrom
import com.fitbittrack.pulse.ui.screens.airTag
import com.fitbittrack.pulse.ui.screens.bandLook
import com.fitbittrack.pulse.ui.screens.goalLeft
import com.fitbittrack.pulse.ui.screens.hourBars
import com.fitbittrack.pulse.ui.screens.homeGoals
import com.fitbittrack.pulse.ui.screens.GoalLook
import com.fitbittrack.pulse.ui.screens.goalLook
import com.fitbittrack.pulse.ui.screens.homeRows
import com.fitbittrack.pulse.ui.screens.joinWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HomeTest {

    private fun goal(value: Double?, target: Double = 100.0) = GoalProgress(GoalMetric.Steps, value, target)
    private fun sync(level: Int? = null, word: String? = null, tracker: String = "12 min ago") = SyncUi(
        running = false, progressLabel = null, error = null, needsSignIn = false, lastSuccessLabel = "Synced 1 min ago",
        lastTrackerLabel = tracker, batteryPercent = level, batteryWord = word, firstSyncPending = false,
    )

    @Test fun `a goal says what is left in real numbers`() {
        assertEquals("2,760 steps to go", goalLeft(GoalMetric.Steps, 7_240.0, 10_000.0, false, Units.Metric))
        assertEquals("960 kcal to go", goalLeft(GoalMetric.Calories, 1_840.0, 2_800.0, false, Units.Metric))
        assertEquals("58m short", goalLeft(GoalMetric.Sleep, 422.0, 480.0, false, Units.Metric))
        assertEquals("Goal reached", goalLeft(GoalMetric.Steps, 10_400.0, 10_000.0, true, Units.Metric))
        // Live steps past the goal: only the band can finish it.
        assertEquals("Nearly there, waiting for the band", goalLeft(GoalMetric.Steps, 10_050.0, 10_000.0, false, Units.Metric))
        assertEquals("No data yet", goalLeft(GoalMetric.Steps, null, 10_000.0, false, Units.Metric))
    }

    @Test fun `hour bars are scaled to the busiest hour so far`() {
        val hours = List<Long?>(24) { h -> when (h) { 8 -> 500L; 9 -> 1_000L; 10 -> null; 15 -> 9_000L; else -> 0L } }
        val bars = hourBars(hours, 12)
        assertEquals(0.5f, bars[8])
        assertEquals(1f, bars[9])
        assertNull(bars[10]) // no data
        assertNull(bars[15]) // later than now
        assertEquals(0f, bars[0])
        assertEquals(listOf<Float?>(0f, null), hourBars(listOf(0L, 5L), 0))
    }

    @Test fun `battery and air share a row only when they follow each other`() {
        val (r, b, a) = listOf(TodayCard.Ring, TodayCard.Band, TodayCard.Air)
        val w = TodayCard.Week
        assertEquals(listOf(listOf(r), listOf(b, a), listOf(w)), homeRows(listOf(r, b, a, w)))
        assertEquals(listOf(listOf(a, b), listOf(r)), homeRows(listOf(a, b, r)))
        assertEquals(listOf(listOf(b), listOf(r), listOf(a)), homeRows(listOf(b, r, a)))
        assertEquals(emptyList<List<TodayCard>>(), homeRows(emptyList()))
    }

    @Test fun `cards saved by the old Today screen start fresh, a hidden air card stays hidden`() {
        val fresh = TodayCard.entries.map { it to true }
        assertEquals(fresh, decodeTodayCards("Dial:1,Sleep:0,RestingHeart:1"))
        assertEquals(fresh, decodeTodayCards("Dial:1,Air:1,Sleep:0"))
        assertEquals(fresh.map { (c, on) -> c to (on && c != TodayCard.Air) }, decodeTodayCards("Dial:1,Air:0,Sleep:0"))
        assertEquals(fresh, decodeTodayCards(""))
    }

    @Test fun `saved order and switches are kept, new cards come last and off, Training comes behind the circle and on`() {
        val cards = decodeTodayCards("Band:1,Ring:0,Air:1")
        assertEquals(listOf(TodayCard.Band to true, TodayCard.Ring to false, TodayCard.Train to true, TodayCard.Air to true), cards.take(4))
        assertEquals(TodayCard.entries.toSet(), cards.map { it.first }.toSet())
        assertEquals(setOf(false), cards.drop(4).map { it.second }.toSet())
    }

    @Test fun `the circle starts with steps and calories and always has a goal`() {
        assertEquals(setOf(GoalMetric.Steps, GoalMetric.Calories), decodeRingGoals(null))
        assertEquals(GoalCalendar.CIRCLE, decodeRingGoals("Nonsense"))
        assertEquals(setOf(GoalMetric.Steps, GoalMetric.Sleep), decodeRingGoals("Steps,Sleep"))
        assertEquals(GoalCalendar.CIRCLE, decodeRingGoals("Floors")) // not a circle goal
    }

    @Test fun `steps has its own card and zone minutes are listed only when added`() {
        val items = GoalMetric.entries.map { GoalProgress(it, 1.0, 2.0) }
        assertEquals(listOf(GoalMetric.Sleep, GoalMetric.Calories), homeGoals(items, GoalCalendar.CIRCLE).map { it.metric })
        assertEquals(listOf(GoalMetric.Sleep, GoalMetric.Calories, GoalMetric.Zone), homeGoals(items, setOf(GoalMetric.Zone)).map { it.metric })
    }

    @Test fun `today carries the newest heart rate and its time`() {
        val day = LocalDate.of(2026, 11, 24)
        val at = day.atTime(8, 31).atZone(ZoneId.systemDefault()).toInstant()
        val ui = Mappers.today(day, null, emptyList(), emptyList(), null, Goals(), LocalTime.NOON, HeartRateSample(at, 72))
        assertEquals(72, ui.latestBpm)
        assertEquals(at, ui.latestBpmAt)
        assertNull(Mappers.today(day, null, emptyList(), emptyList(), null, Goals(), LocalTime.NOON).latestBpm)
    }

    @Test fun `the battery tile says the percent, the word, or that nothing came`() {
        assertEquals(BandLook("76%", "12 min ago", false), bandLook(sync(76), 20, false))
        assertEquals(BandLook("20%", "Charge soon", true), bandLook(sync(20), 20, false))
        assertEquals(BandLook("21%", "12 min ago", false), bandLook(sync(21), 20, false))
        assertEquals(BandLook("High", "Just now", false), bandLook(sync(word = "high", tracker = "just now"), 20, false))
        assertEquals(BandLook("Low", "Charge soon", true), bandLook(sync(word = "low"), 20, false))
        assertEquals(BandLook("–", "Not reported yet", false), bandLook(sync(), 20, false))
        assertEquals(BandLook("–", "Connect to see it", false), bandLook(sync(), 20, true))
        assertEquals("At the last sync", bandLook(sync(50, tracker = "unknown"), 20, false).note)
        // A percent wins over a word that says something else.
        assertEquals(false, bandLook(sync(60, "low"), 20, false).low)
    }

    @Test fun `only a recent station is called live`() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        val station = AirSource.Station("A", 900.0, emptyList())
        assertEquals("Live", airTag(AirReading(42, now.minusSeconds(3_600), station), now))
        assertEquals("Measured", airTag(AirReading(42, now.minusSeconds(3_601), station), now))
        assertEquals("Estimate", airTag(AirReading(42, now, AirSource.Estimate), now))
    }

    @Test fun `the air tile says where and when the reading is from`() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        val station = AirReading(42, now.minusSeconds(1_200), AirSource.Station("A", 1_240.0, emptyList()))
        assertEquals("About 1.2 km away, 20 min ago", airFrom(station, Units.Metric, now))
        assertEquals("For your area, 12:00 pm", airFrom(AirReading(42, now, AirSource.Estimate), Units.Metric, now, ZoneId.of("UTC")))
        assertEquals("Estimate, 12:00 pm", airFrom(AirReading(42, now, AirSource.Estimate), Units.Metric, now, ZoneId.of("UTC"), short = true))
    }

    @Test fun `live steps are marked and the band's numbers carry their age`() {
        val now = Instant.parse("2026-11-24T10:00:00Z")
        val live = HomeLive(steps = 102, bandUpTo = now.minusSeconds(480))
        assertEquals(GoalLook(102.0, true, "Live"), goalLook(goal(90.0), live, now))
        assertEquals(GoalLook(120.0, false, "8 min"), goalLook(goal(120.0), live, now)) // the band is ahead
        assertEquals(GoalLook(90.0, false, "8 min"), goalLook(goal(90.0), live.copy(steps = null), now))
        assertEquals(GoalLook(102.0, true, "Live"), goalLook(goal(null), live, now))
        assertEquals(GoalLook(null, false, null), goalLook(goal(null), HomeLive(), now))
        // Sleep is not from the last minutes: no age, and never live.
        assertEquals(GoalLook(90.0, false, null), goalLook(GoalProgress(GoalMetric.Sleep, 90.0, 100.0), live, now))
        assertEquals(GoalLook(90.0, false, "8 min"), goalLook(GoalProgress(GoalMetric.Calories, 90.0, 100.0), live, now))
    }

    @Test fun `the age of a number is short`() {
        val now = Instant.parse("2026-11-24T10:00:00Z")
        assertEquals(
            listOf("now", "now", "20 sec", "1 min", "59 min", "2 h", "1 day", "3 days"),
            listOf(-5L, 9, 29, 60, 3_599, 7_300, 90_000, 260_000).map { Fmt.age(now.minusSeconds(it), now) },
        )
    }

    @Test fun `goal names read as a sentence`() {
        assertEquals("Steps and calories", joinWords(listOf("Steps", "Calories")))
        assertEquals("Steps, sleep and zone minutes", joinWords(listOf("Steps", "Sleep", "Zone minutes")))
        assertEquals("Steps", joinWords(listOf("Steps")))
    }
}
