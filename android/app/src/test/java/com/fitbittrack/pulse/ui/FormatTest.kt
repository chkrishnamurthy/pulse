package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.Units
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Locale

class FormatTest {

    init { Locale.setDefault(Locale.US) }

    @Test fun `missing values show a dash`() {
        assertEquals(Fmt.NONE, Fmt.count(null as Long?))
        assertEquals(Fmt.NONE, Fmt.minutes(null))
        assertEquals(Fmt.NONE, Fmt.whole(null))
        assertEquals(Fmt.NONE, Fmt.signedTemp(null))
        assertEquals(Fmt.NONE, Fmt.distance(null, Units.Metric).first)
    }

    @Test fun minutes() {
        assertEquals("45m", Fmt.minutes(45))
        assertEquals("7h 05m", Fmt.minutes(425))
        assertEquals("0m", Fmt.minutes(0))
    }

    @Test fun `distance respects units`() {
        assertEquals("5.0" to "km", Fmt.distance(5_000.0, Units.Metric))
        assertEquals("3.1" to "mi", Fmt.distance(5_000.0, Units.Imperial))
    }

    @Test fun `skin temperature has a sign`() {
        assertEquals("+0.3°", Fmt.signedTemp(0.3))
        assertEquals("−0.4°", Fmt.signedTemp(-0.4))
    }

    @Test fun ago() {
        val now = Instant.parse("2026-11-22T12:00:00Z")
        assertEquals("never", Fmt.ago(null, now))
        assertEquals("just now", Fmt.ago(now.minusSeconds(20), now))
        assertEquals("12 min ago", Fmt.ago(now.minusSeconds(12 * 60), now))
        assertEquals("3 h ago", Fmt.ago(now.minusSeconds(3 * 3600), now))
        assertEquals("2 days ago", Fmt.ago(now.minusSeconds(50 * 3600), now))
    }
}
