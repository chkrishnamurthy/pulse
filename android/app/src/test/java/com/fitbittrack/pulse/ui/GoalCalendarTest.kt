package com.fitbittrack.pulse.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fitbittrack.pulse.data.local.SettingsStore
import com.fitbittrack.pulse.domain.DatedGoals
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.MetricMapper
import com.fitbittrack.pulse.ui.model.ReportRange
import com.fitbittrack.pulse.ui.model.Units
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class GoalCalendarTest {

    private val today = LocalDate.of(2026, 11, 23)
    private val goals = Goals() // 10,000 steps, 2,800 kcal, 8 h sleep, 150 zone min a week, 10 floors
    private val utc = ZoneId.of("UTC")
    private fun always(g: Goals = goals) = listOf(DatedGoals(LocalDate.ofEpochDay(0), g))

    private fun days(steps: Map<LocalDate, Long>, history: List<DatedGoals> = always(), from: LocalDate = today.minusDays(9)) =
        GoalCalendar.days(from, today, steps.map { (d, s) -> DaySummary(d, steps = s) }, emptyList(), history, utc)

    @Test fun `steps and calories count as reached exactly at the target`() {
        val d = GoalCalendar.day(today, DaySummary(today, steps = 10_000, caloriesKcal = 2_799.0), null, goals)
        assertTrue(d[GoalMetric.Steps].met)
        assertFalse(d[GoalMetric.Calories].met)
        assertEquals(99, d[GoalMetric.Calories].percent)
    }

    @Test fun `missing data is not zero and never reached`() {
        val d = GoalCalendar.day(today, null, null, goals)
        assertFalse(d.hasData)
        assertNull(d[GoalMetric.Steps].fraction)
        assertEquals(0, d.coreMet)
        assertFalse(d.met(null))
    }

    @Test fun `all goals needs steps, sleep, calories and zone minutes, not floors`() {
        val s = DaySummary(today, steps = 12_000, caloriesKcal = 2_900.0, activeZoneMinutes = 21, floors = 0)
        assertTrue(GoalCalendar.day(today, s, 480, goals).met(null))
        assertFalse(GoalCalendar.day(today, s, 479, goals).met(null))
    }

    @Test fun `each day is judged by the target it had`() {
        val change = today.minusDays(3)
        val history = DatedGoals.record(emptyList(), goals, goals.copy(dailySteps = 6_000), change)
        val all = days((0L..9L).associate { today.minusDays(it) to 7_000L }, history).associateBy { it.date }
        assertFalse(all.getValue(change.minusDays(1)).met(GoalMetric.Steps)) // still 10,000 then
        assertTrue(all.getValue(change).met(GoalMetric.Steps))
        assertEquals(10_000.0, all.getValue(today.minusDays(9))[GoalMetric.Steps].target, 0.0)
    }

    @Test fun `changing a goal twice on one day keeps one entry`() {
        val a = DatedGoals.record(emptyList(), goals, goals.copy(dailySteps = 8_000), today)
        val b = DatedGoals.record(a, goals.copy(dailySteps = 8_000), goals.copy(dailySteps = 9_000), today)
        assertEquals(listOf(10_000, 9_000), b.map { it.goals.dailySteps })
    }

    @Test fun `an unfinished today does not break the streak`() {
        val reached = (1L..4L).associate { today.minusDays(it) to 11_000L }
        assertEquals(4, GoalCalendar.currentStreak(days(reached + (today to 3_000L)), GoalMetric.Steps, today))
        assertEquals(5, GoalCalendar.currentStreak(days(reached + (today to 10_500L)), GoalMetric.Steps, today))
        // A day without data does break it.
        assertEquals(1, GoalCalendar.currentStreak(days(reached - today.minusDays(2)), GoalMetric.Steps, today))
        assertEquals(4, GoalCalendar.bestStreak(days(reached), GoalMetric.Steps))
    }

    @Test fun `the month grid starts on the right weekday and counts only days with data`() {
        val month = YearMonth.of(2026, 11) // 1 November 2026 is a Sunday
        val all = days(mapOf(today to 12_000L, today.minusDays(1) to 5_000L), from = month.atDay(1))
        val sunday = GoalCalendar.month(month, GoalMetric.Steps, all, today, DayOfWeek.SUNDAY)
        assertEquals(0, sunday.leadingBlanks)
        assertEquals(30, sunday.days.size)
        assertEquals(2, sunday.daysWithData)
        assertEquals(1, sunday.daysMet)
        assertEquals(8_500.0, sunday.average!!, 0.0)
        val monday = GoalCalendar.month(month, null, all, today, DayOfWeek.MONDAY)
        assertEquals(6, monday.leadingBlanks)
        assertEquals(DayOfWeek.MONDAY, monday.weekdays.first())
        assertNull(monday.average)
    }

    @Test fun `sleep counts for the day you woke up, longest main sleep only`() {
        val start = Instant.parse("2026-11-22T22:00:00Z")
        val night = SleepSession("a", start, start.plusSeconds(8 * 3600), emptyList(), 480, true, today)
        val nap = SleepSession("b", start.plusSeconds(14 * 3600), start.plusSeconds(15 * 3600), emptyList(), 60, false, today)
        val all = GoalCalendar.days(today.minusDays(1), today, emptyList(), listOf(night, nap), always(), utc)
        assertNull(all[0][GoalMetric.Sleep].value)
        assertTrue(all[1].met(GoalMetric.Sleep))
    }

    private class Memory : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = transform(state.value).also { state.value = it }
    }

    @Test fun `goals and their history are saved and read back`() = runBlocking {
        val store = SettingsStore(Memory())
        assertEquals(2_800, store.goals.first().dailyCalories)
        assertEquals(1, store.goalHistory.first().size)
        store.setGoals(goals.copy(dailyCalories = 3_000), today.minusDays(5))
        store.setGoals(goals.copy(dailyCalories = 3_000), today.minusDays(2)) // no change: no entry
        store.setGoals(goals.copy(dailyCalories = 2_500, dailySteps = 8_000), today)
        assertEquals(2_500, store.goals.first().dailyCalories)
        val history = store.goalHistory.first()
        assertEquals(listOf(2_800, 3_000, 2_500), history.map { it.goals.dailyCalories })
        assertEquals(3_000, GoalCalendar.goalsOn(history, today.minusDays(1)).dailyCalories)
        assertEquals(2_800, GoalCalendar.goalsOn(history, today.minusDays(30)).dailyCalories)
    }

    @Test fun `metric page leaves the unfinished day out of average and lowest`() {
        val sums = listOf(DaySummary(today, steps = 100), DaySummary(today.minusDays(1), steps = 9_000), DaySummary(today.minusDays(2), steps = 11_000))
        val ui = MetricMapper.metric(MetricKind.Steps, ReportRange.Week, today, sums, emptyList()) { 10_000.0 }
        assertEquals(10_000.0, ui.average!!, 0.0)
        assertEquals(9_000.0, ui.lowest!!.second, 0.0)
        assertEquals(1, ui.daysMet)
        assertEquals(3, ui.daysWithData)
        assertEquals(7, ui.days.size)
        assertEquals(20_100.0, ui.total!!, 0.0)
        // Measured values keep today, and are not added up.
        val rest = MetricMapper.metric(MetricKind.RestingHeart, ReportRange.Week, today, listOf(DaySummary(today, restingHeartRate = 58)), emptyList()) { null }
        assertEquals(58.0, rest.average!!, 0.0)
        assertNull(rest.total)
    }

    @Test fun `year view has one bar per month`() {
        val ui = MetricMapper.metric(MetricKind.Calories, ReportRange.Year, today, listOf(DaySummary(today, caloriesKcal = 2_000.0)), emptyList()) { 2_800.0 }
        assertTrue(ui.bars.size in 12..13)
        assertEquals(2_000.0, ui.bars.last().second!!, 0.0)
    }

    @Test fun `values are written with the right unit`() {
        assertEquals("–", MetricKind.Steps.text(null, Units.Metric))
        assertEquals("96%", MetricKind.Spo2.text(96.0, Units.Metric))
        assertEquals("5.0 mi", MetricKind.Distance.text(8_046.72, Units.Imperial))
    }
}
