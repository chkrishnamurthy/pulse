package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.ui.model.DayStat
import com.fitbittrack.pulse.ui.reports.Reports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReportsTest {

    private val d0 = LocalDate.of(2026, 11, 16)
    private fun day(i: Int, steps: Long?, sleep: Int? = null, rhr: Int? = null, azm: Long? = null, dist: Double? = null) =
        DayStat(d0.plusDays(i.toLong()), steps, sleep, rhr, azm, dist)

    @Test fun `averages ignore missing days instead of counting them as zero`() {
        val r = Reports.build(listOf(day(0, 10_000), day(1, null), day(2, 6_000)), stepGoal = 8_000)
        assertEquals(8_000L, r.avgSteps)
        assertEquals(2, r.daysWithSteps)
        assertEquals(1, r.daysOnStepGoal)
    }

    @Test fun `empty range gives nulls not crashes`() {
        val r = Reports.build(emptyList(), 10_000)
        assertNull(r.avgSteps); assertNull(r.avgSleepMinutes); assertNull(r.bestStepsDay)
        assertNull(r.totalZoneMinutes); assertNull(r.totalDistanceMeters)
        assertEquals(0, r.daysWithSteps)
    }

    @Test fun `resting heart rate trend uses first and last known values`() {
        val r = Reports.build(listOf(day(0, 1, rhr = null), day(1, 1, rhr = 61), day(2, 1, rhr = 59), day(3, 1, rhr = null)), 10)
        assertEquals(61, r.restingStart); assertEquals(59, r.restingEnd)
    }

    @Test fun `best day and longest sleep`() {
        val r = Reports.build(listOf(day(0, 5_000, sleep = 400), day(1, 12_000, sleep = 380), day(2, 9_000, sleep = 470)), 10_000)
        assertEquals(d0.plusDays(1), r.bestStepsDay?.date)
        assertEquals(d0.plusDays(2), r.longestSleepDay?.date)
    }

    @Test fun `fillGaps adds empty days so charts keep their spacing`() {
        val filled = Reports.fillGaps(listOf(day(0, 1), day(3, 1)), d0, d0.plusDays(4))
        assertEquals(5, filled.size)
        assertNull(filled[1].steps); assertNull(filled[4].steps)
        assertEquals(d0.plusDays(3), filled[3].date)
    }

    @Test fun `monthly groups by calendar month with averages and sums`() {
        val days = listOf(
            DayStat(LocalDate.of(2026, 10, 30), 10_000, 400, 60, 20, 1_000.0),
            DayStat(LocalDate.of(2026, 10, 31), 6_000, null, 62, 10, 3_000.0),
            DayStat(LocalDate.of(2026, 11, 1), 8_000, 420, null, null, null),
        )
        val m = Reports.monthly(days)
        assertEquals(2, m.size)
        assertEquals(LocalDate.of(2026, 10, 1), m[0].date)
        assertEquals(8_000L, m[0].steps)
        assertEquals(400, m[0].sleepMinutes)
        assertEquals(61, m[0].restingHeartRate)
        assertEquals(30L, m[0].zoneMinutes)
        assertEquals(4_000.0, m[0].distanceMeters!!, 0.001)
        assertNull(m[1].distanceMeters)
    }
}
