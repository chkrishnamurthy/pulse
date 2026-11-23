package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.data.remote.Parsers
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.reminders.ReminderKind
import com.fitbittrack.pulse.reminders.ReminderLogic
import com.fitbittrack.pulse.reminders.ReminderPrefs
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.Units
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderLogicTest {

    private val p = ReminderPrefs()

    @Test fun `next time is today when ahead and tomorrow when passed`() {
        val now = LocalDateTime.of(2026, 11, 23, 18, 0)
        assertEquals(Duration.ofHours(1), ReminderLogic.delayUntil(now, LocalTime.of(19, 0)))
        assertEquals(Duration.ofHours(23), ReminderLogic.delayUntil(now, LocalTime.of(17, 0)))
        assertEquals(Duration.ofHours(24), ReminderLogic.delayUntil(now, LocalTime.of(18, 0)))
    }

    @Test fun `quiet hours run over midnight`() {
        assertTrue(ReminderLogic.inQuiet(LocalTime.of(23, 0), p))
        assertTrue(ReminderLogic.inQuiet(LocalTime.of(6, 59), p))
        assertFalse(ReminderLogic.inQuiet(LocalTime.of(7, 0), p))
        assertFalse(ReminderLogic.inQuiet(LocalTime.of(12, 0), p))
        assertFalse(ReminderLogic.inQuiet(LocalTime.of(23, 0), p.copy(quietOn = false)))
    }

    @Test fun `bedtime reminder comes 30 minutes before bedtime`() {
        assertEquals(LocalTime.of(22, 0), ReminderLogic.timeOf(ReminderKind.Bedtime, p))
        assertEquals(LocalTime.of(7, 30), ReminderLogic.timeOf(ReminderKind.Morning, p))
    }

    @Test fun `evening text lists what is left and stays silent when done`() {
        val today = LocalDate.of(2026, 11, 23)
        val goals = Goals()
        val core = setOf(GoalMetric.Steps, GoalMetric.Calories)
        val behind = GoalCalendar.day(today, DaySummary(today, steps = 7_860, caloriesKcal = 3_000.0), null, goals, core)
        assertEquals("Left today: 2,140 steps.", ReminderLogic.eveningText(behind, Units.Metric))
        val done = GoalCalendar.day(today, DaySummary(today, steps = 11_000, caloriesKcal = 3_000.0), null, goals, core)
        assertNull(ReminderLogic.eveningText(done, Units.Metric))
        val none = GoalCalendar.day(today, null, null, goals, core)
        assertTrue(ReminderLogic.eveningText(none, Units.Metric)!!.contains("not sent"))
    }

    @Test fun `morning text`() {
        assertEquals("You slept 7 h 12 min. Resting heart rate 58. Today: 10,000 steps.", ReminderLogic.morningText(432, 58, 10_000))
        assertEquals("No sleep data yet. Today: 8,000 steps.", ReminderLogic.morningText(null, null, 8_000))
    }

    @Test fun `band alerts`() {
        val on = p.copy(lowBatteryOn = true, lowBatteryAt = 20, staleOn = true)
        assertTrue(ReminderLogic.lowBattery(20, on))
        assertFalse(ReminderLogic.lowBattery(21, on))
        assertFalse(ReminderLogic.lowBattery(null, on))
        assertFalse(ReminderLogic.lowBattery(5, p))
        val hour = 3_600_000L
        assertTrue(ReminderLogic.stale(0L, 7 * hour, on))
        assertFalse(ReminderLogic.stale(0L, 5 * hour, on))
        assertFalse(ReminderLogic.stale(null, 7 * hour, on))
        assertFalse(ReminderLogic.stale(0L, 7 * hour, p))
    }

    @Test fun `battery is read from the tracker that synced last`() {
        val json = Json.parseToJsonElement(
            """{"pairedDevices":[
              {"deviceType":"TRACKER","lastSyncTime":"2026-11-23T10:00:00Z","batteryLevel":62,"batteryStatus":"MEDIUM"},
              {"deviceType":"TRACKER","lastSyncTime":"2026-11-20T10:00:00Z","batteryLevel":5},
              {"deviceType":"SCALE","lastSyncTime":"2026-11-23T11:00:00Z","batteryLevel":99}]}""",
        ).jsonObject["pairedDevices"]!!.let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonObject } }
        assertEquals(62, Parsers.battery(json)?.level)
        assertEquals("MEDIUM", Parsers.battery(json)?.status)
        assertNull(Parsers.battery(emptyList()))
        val wordOnly = Json.parseToJsonElement("""{"deviceType":"TRACKER","batteryStatus":"LOW"}""").jsonObject
        assertNull(Parsers.battery(listOf(wordOnly))?.level)
        assertEquals("LOW", Parsers.battery(listOf(wordOnly))?.status)
        assertNull(Parsers.battery(listOf(Json.parseToJsonElement("""{"deviceType":"TRACKER"}""").jsonObject)))
    }
}
