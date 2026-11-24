package com.fitbittrack.pulse.direct

import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.HeartRateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LiveStepsTest {

    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 11, 24)
    private fun at(t: String) = Instant.parse("2026-11-24T${t}Z")

    private fun trail(vararg p: Pair<String, Long>) = StepTrail().apply { p.forEach { (t, n) -> add(at(t), n) } }

    @Test fun `adds the phone's steps since the band's count`() {
        val t = trail("10:00:00" to 1_000, "10:05:00" to 1_200, "10:10:00" to 1_500)
        val band = BandTotal(day, 5_000, at("10:05:00"))
        assertEquals(LiveSteps(day, 5_300, 300), LiveStepMath.estimate(band, t, emptyList(), at("10:10:05"), zone))
    }

    @Test fun `nothing is added before the sensor has answered or after the band's newest count`() {
        val band = BandTotal(day, 5_000, at("10:05:00"))
        assertNull(LiveStepMath.estimate(band, StepTrail(), emptyList(), at("10:10:00"), zone))
        assertNull(LiveStepMath.estimate(null, trail("10:00:00" to 1), emptyList(), at("10:10:00"), zone))
        assertEquals(0L, LiveStepMath.added(band, trail("10:00:00" to 1_000, "10:04:00" to 1_100), emptyList(), at("10:06:00"), zone))
    }

    @Test fun `no guess when the band has not synced for hours`() {
        val t = trail("09:00:00" to 0, "12:30:00" to 900)
        assertEquals(0L, LiveStepMath.added(BandTotal(day, 5_000, at("10:00:00")), t, emptyList(), at("12:30:01"), zone))
    }

    @Test fun `steps before midnight don't count for the new day`() {
        val t = StepTrail().apply {
            add(Instant.parse("2026-11-23T23:50:00Z"), 100)
            add(at("00:00:00"), 400)
            add(at("00:10:00"), 450)
        }
        val band = BandTotal(day, 0, Instant.parse("2026-11-23T23:50:00Z"))
        assertEquals(50L, LiveStepMath.added(band, t, emptyList(), at("00:10:00"), zone))
    }

    @Test fun `Health Connect's phone steps fill the time before Pulse was open, once`() {
        val t = trail("10:10:00" to 5_000, "10:12:00" to 5_100)
        val band = BandTotal(day, 2_000, at("10:05:00"))
        val parts = listOf(
            StepPart(at("10:04:00"), at("10:06:00"), 100), // half of it
            StepPart(at("10:07:00"), at("10:08:00"), 80),
            StepPart(at("10:11:00"), at("10:12:00"), 60), // the sensor has these
        )
        assertEquals(50L + 80 + 100, LiveStepMath.added(band, t, parts, at("10:12:00"), zone))
    }

    @Test fun `the trail never goes back and spreads a gap evenly`() {
        val t = trail("10:00:00" to 100, "10:00:10" to 120)
        t.add(at("10:00:20"), 118) // the exact sensor is a little behind the quick one
        assertEquals(120L, t.last)
        t.add(at("10:10:20"), 720) // ten minutes away
        assertEquals(300L, t.since(at("10:05:20")))
        assertEquals(620L, t.since(at("09:00:00")))
        assertEquals(0L, t.since(at("11:00:00")))
    }

    @Test fun `a new sync time with the old count waits, so the number doesn't jump back`() {
        val a = BandAnchor()
        val old = BandTotal(day, 5_000, at("10:00:00"))
        val timeOnly = old.copy(upTo = at("10:15:00"))
        assertEquals(old, a.take(old, at("10:01:00")))
        assertEquals(old, a.take(timeOnly, at("10:15:10")))
        assertEquals(old, a.take(timeOnly, at("10:15:50")))
        // The steps arrived: use them at once.
        val full = timeOnly.copy(steps = 5_400)
        assertEquals(full, a.take(full, at("10:15:55")))
        // No steps at all since: believed after a minute.
        val still = full.copy(upTo = at("10:30:00"))
        assertEquals(full, a.take(still, at("10:30:05")))
        assertEquals(still, a.take(still, at("10:31:05")))
        assertNull(a.take(null, at("10:32:00")))
    }

    @Test fun `the newest band count wins`() {
        val local = LocalSnapshot(day, steps = 5_200, newestSteps = at("10:20:00"))
        assertEquals(BandTotal(day, 5_200, at("10:20:00")), LiveStepMath.bandTotal(day, 5_000, at("10:15:00"), local))
        assertEquals(BandTotal(day, 5_300, at("10:25:00")), LiveStepMath.bandTotal(day, 5_300, at("10:25:00"), local))
        assertEquals(BandTotal(day, 5_200, at("10:25:00")), LiveStepMath.bandTotal(day, 5_200, at("10:25:00"), local))
        assertEquals(BandTotal(day, 5_200, at("10:20:00")), LiveStepMath.bandTotal(day, null, null, local))
        assertEquals(BandTotal(day, 0, at("00:05:00")), LiveStepMath.bandTotal(day, null, at("00:05:00"), null))
        assertNull(LiveStepMath.bandTotal(day, 5_000, null, null)) // no idea how old that is
        // Yesterday's numbers say nothing about today.
        assertEquals(BandTotal(day, 5, at("00:05:00")), LiveStepMath.bandTotal(day, 5, at("00:05:00"), local.copy(day = day.minusDays(1))))
    }

    @Test fun `calories from Health Connect, shared time counted once`() {
        val from = at("00:00:00")
        assertEquals(100.0, Lanes.sumOnce(listOf(Triple(at("00:00:00"), at("01:00:00"), 60.0), Triple(at("01:00:00"), at("02:00:00"), 40.0)), from), 0.01)
        // The second starts half way through the first.
        assertEquals(90.0, Lanes.sumOnce(listOf(Triple(at("00:00:00"), at("01:00:00"), 60.0), Triple(at("00:30:00"), at("01:30:00"), 60.0)), from), 0.01)
        assertEquals(0.0, Lanes.sumOnce(emptyList(), from), 0.01)
        assertEquals(900.0, Lanes.mergeDay(DaySummary(day, caloriesKcal = 800.0), LocalSnapshot(day, caloriesKcal = 900.0), day)!!.caloriesKcal!!, 0.01)
        assertEquals(950.0, Lanes.mergeDay(DaySummary(day, caloriesKcal = 950.0), LocalSnapshot(day, caloriesKcal = 900.0), day)!!.caloriesKcal!!, 0.01)
    }

    @Test fun `the phone's own steps are never taken for the band's`() {
        assertTrue(Lanes.isPhoneSource("android"))
        assertTrue(Lanes.isPhoneSource("com.android.healthconnect.phone.jd5bdd37e1a8"))
        assertFalse(Lanes.isPhoneSource("com.sec.android.app.shealth"))
        assertFalse(Lanes.isBandSource("com.android.healthconnect.phone.jd5bdd37e1a8"))
    }

    @Test fun `the newer reading wins, and a quiet band is not live`() {
        val a = HeartRateSample(at("10:00:00"), 60)
        val b = HeartRateSample(at("10:01:00"), 70)
        assertEquals(b, Lanes.newer(a, b))
        assertEquals(b, Lanes.newer(b, a))
        assertEquals(a, Lanes.newer(a, null))
        assertNull(Lanes.newer(null, null))

        val live = LiveState.Live(HeartDevice("x", "Band", true), 72, onSkin = true, history = emptyList(), at = at("10:00:00"))
        assertEquals(72, live.bpmAt(at("10:00:10")))
        assertNull(live.bpmAt(at("10:00:11")))
        assertNull(live.copy(onSkin = false).bpmAt(at("10:00:01")))
    }
}
