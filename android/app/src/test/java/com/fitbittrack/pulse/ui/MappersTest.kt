package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.SleepStage
import com.fitbittrack.pulse.domain.SleepStageType
import com.fitbittrack.pulse.domain.SyncState
import com.fitbittrack.pulse.domain.SyncStatus
import com.fitbittrack.pulse.domain.Workout
import com.fitbittrack.pulse.ui.model.Mappers
import com.fitbittrack.pulse.ui.model.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

class MappersTest {

    init { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private val today = LocalDate.of(2026, 11, 22)
    private val zone = ZoneId.of("UTC")
    private fun at(d: LocalDate, h: Int, m: Int = 0): Instant = d.atTime(h, m).atZone(zone).toInstant()

    private fun night(wake: LocalDate, wakeDate: LocalDate? = null): SleepSession {
        val s = at(wake.minusDays(1), 23)
        val stages = listOf(
            SleepStage(SleepStageType.LIGHT, s, s.plusSeconds(60 * 60)),
            SleepStage(SleepStageType.DEEP, s.plusSeconds(60 * 60), s.plusSeconds(120 * 60)),
            SleepStage(SleepStageType.AWAKE, s.plusSeconds(120 * 60), s.plusSeconds(130 * 60)),
            SleepStage(SleepStageType.REM, s.plusSeconds(130 * 60), s.plusSeconds(190 * 60)),
        )
        return SleepSession("n-$wake", s, s.plusSeconds(190 * 60), stages, wakeDate = wakeDate)
    }

    @Test fun `today maps steps, goals and sleep, and keeps missing values null`() {
        val ui = Mappers.today(today, DaySummary(today, steps = 4_000), emptyList(), List(24) { null }, night(today), Goals(dailySteps = 8_000, weeklyActiveZoneMinutes = 140), LocalTime.NOON)
        assertEquals(4_000L, ui.steps)
        assertEquals(8_000, ui.stepGoal)
        assertEquals(20, ui.zoneMinutesDailyTarget)
        assertNull(ui.restingHeartRate)
        assertEquals(180, ui.sleepMinutes) // 190 min in bed − 10 awake
        assertEquals(LocalTime.of(23, 0), ui.sleepStart)
        assertNotNull(ui.sleepShares)
        assertEquals(7, ui.restingHeartRateLast7.size)
    }

    @Test fun `insight compares resting heart rate with the 30-day normal`() {
        val history = (1..10).map { DaySummary(today.minusDays(it.toLong()), restingHeartRate = 61) }
        val down = Mappers.insight(DaySummary(today, restingHeartRate = 58), history, null, Goals())
        assertEquals("Resting heart rate is down", down?.title); assertTrue(down!!.positive)
        val up = Mappers.insight(DaySummary(today, restingHeartRate = 65), history, null, Goals())
        assertFalse(up!!.positive)
    }

    @Test fun `no insight without enough history or sleep`() {
        assertNull(Mappers.insight(DaySummary(today, restingHeartRate = 58), emptyList(), null, Goals()))
    }

    @Test fun `sleep blocks are measured from bedtime and skip unknown stages`() {
        val ui = Mappers.sleep(listOf(night(today)), emptyList(), Goals())
        assertEquals(4, ui.blocks.size)
        assertEquals(Stage.Light, ui.blocks[0].stage)
        assertEquals(0, ui.blocks[0].startMinute)
        assertEquals(190, ui.totalMinutesInBed)
        assertEquals(10, ui.minutesByStage[Stage.Awake])
    }

    @Test fun `sleep uses Google's wake date for the body summary when given`() {
        val wake = today.minusDays(1) // e.g. after travelling across time zones
        val ui = Mappers.sleep(
            listOf(night(today, wakeDate = wake)),
            listOf(DaySummary(wake, spo2Percent = 95.0), DaySummary(today, spo2Percent = 99.0)),
            Goals(),
        )
        assertEquals(95.0, ui.spo2Percent!!, 0.0)
    }

    @Test fun `empty sleep gives an empty screen model, not a crash`() {
        val ui = Mappers.sleep(emptyList(), emptyList(), Goals())
        assertNull(ui.minutesAsleep); assertTrue(ui.blocks.isEmpty()); assertEquals(0, ui.totalMinutesInBed)
    }

    @Test fun `workout tags and duration`() {
        val w = Workout("1", "RUNNING", "", at(today, 7), at(today, 7, 40), activeSeconds = 30 * 60)
        val ui = Mappers.activity(listOf(w), emptyList(), Goals())
        assertEquals("RUN", ui.workouts[0].tag)
        assertEquals("Running", ui.workouts[0].name)
        assertEquals(30L, ui.workouts[0].durationMinutes)
        assertNull(ui.weekZoneMinutes)
    }

    @Test fun `dayStats joins sleep by wake day`() {
        val stats = Mappers.dayStats(listOf(DaySummary(today, steps = 100)), listOf(night(today)))
        assertEquals(1, stats.size)
        assertEquals(180, stats[0].sleepMinutes)
    }

    @Test fun `sync status mapping`() {
        val now = Instant.parse("2026-11-22T12:00:00Z")
        val first = Mappers.sync(SyncStatus(SyncState.Running(0.5f, "Downloading"), null, null), isSample = false, now = now)
        assertTrue(first.running); assertTrue(first.firstSyncPending); assertEquals(0.5f, first.progress!!, 0f)
        assertEquals("Downloading · 50%", first.progressLabel)
        val failed = Mappers.sync(SyncStatus(SyncState.Failed("Sign in", true), now.minusSeconds(600), null), now = now)
        assertTrue(failed.needsSignIn); assertFalse(failed.firstSyncPending)
        assertEquals("Synced 10 min ago", failed.lastSuccessLabel)
        val sample = Mappers.sync(SyncStatus(), isSample = true, now = now)
        assertFalse(sample.firstSyncPending)
    }
}
