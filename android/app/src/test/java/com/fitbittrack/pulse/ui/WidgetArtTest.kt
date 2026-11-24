package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.domain.DatedGoals
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.SleepStage
import com.fitbittrack.pulse.domain.SleepStageType
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.widget.art.ArtData
import com.fitbittrack.pulse.ui.widget.art.ArtDataLoader
import com.fitbittrack.pulse.ui.widget.art.ArtLogic
import com.fitbittrack.pulse.ui.widget.art.WidgetConfig
import com.fitbittrack.pulse.ui.widget.art.WidgetDesign
import com.fitbittrack.pulse.ui.widget.art.WidgetLook
import com.fitbittrack.pulse.ui.widget.art.WidgetSize
import com.fitbittrack.pulse.ui.widget.art.WidgetTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

class WidgetArtTest {

    init { Locale.setDefault(Locale.US) }

    private val today: LocalDate = LocalDate.of(2026, 11, 23)

    private fun data(hour: Int, steps: Long?, sleep: Int? = 444) = ArtData(
        signedIn = true, now = today.atTime(hour, 0), sleepMinutes = sleep, syncedAgo = "2 min ago",
        goals = GoalCalendar.day(today, DaySummary(today, steps = steps), sleep, Goals()),
    )

    // ---- settings ----

    @Test fun `every design keeps its settings`() = WidgetDesign.entries.forEach { d ->
        val c = WidgetConfig(d, look = d.looks.last(), tap = WidgetTap.Trends, background = if (d.clear) 40 else 100, refresh = d.canRefresh, petals = setOf(GoalMetric.Steps, GoalMetric.Floors))
        assertEquals(c, WidgetConfig.decode(c.encode()))
    }

    @Test fun `damaged settings never crash`() {
        assertNull(WidgetConfig.decode(null))
        assertNull(WidgetConfig.decode(""))
        assertNull(WidgetConfig.decode("Nothing;Night"))
        assertEquals(WidgetConfig(WidgetDesign.Bloom), WidgetConfig.decode("Bloom;Purple;;x;Nowhere;9"))
        // A look that belongs to another design is not taken over.
        assertEquals(WidgetLook.Full, WidgetConfig.decode("Trail;Night;;100;Today;0")?.look)
        assertEquals(100, WidgetConfig.decode("Trail;Full;;10;Today;0")?.background)
    }

    @Test fun `every size has a design`() = WidgetSize.entries.forEach { assertTrue(WidgetDesign.bySize(it).isNotEmpty()) }

    // ---- words ----

    @Test fun `sky follows the day`() {
        assertEquals("7h 24m of sleep", ArtLogic.sky(data(7, 100)).headline)
        assertEquals("36 min under your goal", ArtLogic.sky(data(7, 100)).detail)
        assertEquals("No steps yet", ArtLogic.sky(data(7, null, null)).headline)
        assertEquals("2,588 steps to go", ArtLogic.sky(data(15, 7_412)).headline)
        assertEquals("About 25 min of walking", ArtLogic.sky(data(15, 7_412)).detail)
        assertEquals("Step goal reached", ArtLogic.sky(data(18, 12_000)).headline)
        assertEquals(ArtLogic.SkyTime.Night, ArtLogic.sky(data(23, 12_000)).time)
        assertEquals(ArtLogic.SkyTime.Night, ArtLogic.sky(data(2, 12)).time)
    }

    @Test fun `minutes to bed cross midnight`() {
        assertEquals(190, ArtLogic.minutesToBed(LocalTime.of(19, 20), LocalTime.of(22, 30)))
        assertEquals(60, ArtLogic.minutesToBed(LocalTime.of(23, 30), LocalTime.of(0, 30)))
        assertEquals(0, ArtLogic.minutesToBed(LocalTime.of(22, 30), LocalTime.of(22, 30)))
        assertEquals(0, ArtLogic.minutesToBed(LocalTime.of(2, 0), LocalTime.of(22, 30)))
        assertTrue(ArtLogic.minutesToBed(LocalTime.of(7, 0), LocalTime.of(22, 30)) > 900)
    }

    @Test fun `yesterday counts up to this minute`() {
        val hours = List(24) { if (it < 12) 100L else 600L }
        assertEquals(1_200L + 300, ArtLogic.yesterdayByNow(hours, LocalTime.of(12, 30)))
        assertNull(ArtLogic.yesterdayByNow(List(24) { null }, LocalTime.of(12, 30)))
        assertNull(ArtLogic.yesterdayByNow(emptyList(), LocalTime.of(12, 30)))
    }

    @Test fun `pip has a mood for every day`() {
        assertEquals(ArtLogic.Mood.Asleep, ArtLogic.mood(data(23, 12_000)))
        assertEquals(ArtLogic.Mood.Proud, ArtLogic.mood(data(15, 12_000)))
        assertEquals(ArtLogic.Mood.Tired, ArtLogic.mood(data(15, 7_000, 300)))
        assertEquals(ArtLogic.Mood.Happy, ArtLogic.mood(data(15, 7_000)))
        assertEquals(ArtLogic.Mood.Calm, ArtLogic.mood(data(8, null, null)))
    }

    @Test fun `walking along a line`() {
        val line = listOf(0f to 0f, 10f to 0f, 10f to 10f)
        assertEquals(0 to (5f to 0f), ArtLogic.along(line, 0.25f))
        assertEquals(1 to (10f to 10f), ArtLogic.along(line, 1f))
        assertEquals(0 to (0f to 0f), ArtLogic.along(line, 0f))
        assertEquals(1 to (10f to 10f), ArtLogic.along(line, 7f))
    }

    @Test fun `short step counts`() {
        assertEquals("–", ArtLogic.thousands(null))
        assertEquals("940", ArtLogic.thousands(940))
        assertEquals("7.4k", ArtLogic.thousands(7_412))
        assertEquals("100k", ArtLogic.thousands(100_400))
    }

    // ---- body signals ----

    @Test fun `signals need a week before they judge`() {
        val day = DaySummary(today, restingHeartRate = 58, hrvMs = 46.0)
        assertTrue(ArtDataLoader.signals(day, emptyList()).all { it.side == null })
        assertEquals("Learning your usual", ArtLogic.signalsText(ArtDataLoader.signals(day, emptyList())).first)
    }

    @Test fun `a signal outside the usual range stands out`() {
        val before = (1L..20L).map { DaySummary(today.minusDays(it), restingHeartRate = 57 + (it % 3).toInt(), hrvMs = 44.0 + it % 4) }
        val calm = ArtDataLoader.signals(DaySummary(today, restingHeartRate = 58, hrvMs = 45.0), before)
        assertEquals("2 of 2 as usual" to null, ArtLogic.signalsText(calm))
        val up = ArtDataLoader.signals(DaySummary(today, restingHeartRate = 70, hrvMs = 45.0), before)
        assertEquals("1 of 2 as usual" to "Heart higher", ArtLogic.signalsText(up))
    }

    // ---- data ----

    private fun night(wake: LocalDate, minutes: Long): SleepSession {
        val end = wake.atTime(7, 0).toInstant(ZoneOffset.UTC)
        val start = end.minusSeconds(minutes * 60)
        return SleepSession("n$wake", start, end, listOf(SleepStage(SleepStageType.DEEP, start, start.plusSeconds(3600)), SleepStage(SleepStageType.LIGHT, start.plusSeconds(3600), end)))
    }

    private fun build(days: List<DaySummary>, sleeps: List<SleepSession>) = ArtDataLoader.build(
        LocalDateTime.of(today, LocalTime.of(15, 40)), days, sleeps, listOf(DatedGoals(LocalDate.EPOCH, Goals())), GoalCalendar.CORE,
        emptyList(), emptyList(), emptyList(), Units.Metric, LocalTime.of(23, 0), ZoneOffset.UTC,
    )

    @Test fun `streak, stars and garden agree`() {
        // Five complete days before today, then one that misses; today is not done yet.
        val days = (0L..9L).map { DaySummary(today.minusDays(it), steps = if (it in 1..5) 11_000 else 4_000, caloriesKcal = 3_000.0) }.reversed()
        val d = build(days, emptyList())
        assertEquals(5, d.streak)
        assertEquals(13, d.before13.size)
        assertEquals(listOf(false, true, true, true, true, true), d.before13.takeLast(6))
        assertEquals(23, d.monthGrowth.size)
        assertEquals(5, d.monthGrowth.count { it == 2 })
        assertEquals(1, d.monthGrowth.last())
        assertEquals(0, d.monthGrowth.first())
    }

    @Test fun `only last night counts as last night`() {
        val d = build(emptyList(), listOf(night(today.minusDays(1), 400)))
        assertNull(d.sleepMinutes)
        assertTrue(d.blocks.isEmpty())
        assertEquals(listOf(null, null, null, null, null, 400, null), d.nights7.map { it.second })
        val e = build(emptyList(), listOf(night(today, 480)))
        assertEquals(480, e.sleepMinutes)
        assertEquals(60, ArtLogic.deepMinutes(e.blocks))
    }

    @Test fun `no data at all`() {
        val d = build(emptyList(), emptyList())
        assertNull(d.steps)
        assertEquals(0, d.streak)
        assertEquals(0f, d.part(GoalMetric.Steps), 0f)
    }
}
