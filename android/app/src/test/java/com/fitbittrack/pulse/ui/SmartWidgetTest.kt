package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.ui.widget.SmartState
import com.fitbittrack.pulse.ui.widget.WidgetDataLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

class SmartWidgetTest {

    init { Locale.setDefault(Locale.US) }
    private val goals = Goals(dailySteps = 10_000, sleepMinutes = 480)

    @Test fun `morning shows last night's sleep`() {
        val s = WidgetDataLoader.smart(1_000, 10_000, 450, goals, LocalTime.of(6, 45), now = LocalTime.of(8, 0))
        assertEquals(SmartState.Kind.Sleep, s.kind)
        assertEquals("7h 30m slept", s.headline)
        assertEquals("30m under your goal.", s.detail)
    }

    @Test fun `daytime shows steps to go`() {
        val s = WidgetDataLoader.smart(8_420, 10_000, 450, goals, null, now = LocalTime.of(14, 20))
        assertEquals(SmartState.Kind.Steps, s.kind)
        assertEquals("1,580 steps to go", s.headline)
    }

    @Test fun `goal reached`() {
        val s = WidgetDataLoader.smart(12_000, 10_000, null, goals, null, now = LocalTime.of(15, 0))
        assertEquals("Step goal reached", s.headline)
    }

    @Test fun `no data yet`() {
        val s = WidgetDataLoader.smart(null, 10_000, null, goals, null, now = LocalTime.of(10, 0))
        assertEquals(SmartState.Kind.Empty, s.kind)
    }

    @Test fun `evening nudges bedtime`() {
        val s = WidgetDataLoader.smart(9_000, 10_000, 450, goals, null, now = LocalTime.of(22, 0))
        assertEquals(SmartState.Kind.Evening, s.kind)
    }
}
