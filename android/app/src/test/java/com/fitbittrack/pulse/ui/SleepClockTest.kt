package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.ui.components.clockArcs
import com.fitbittrack.pulse.ui.components.clockFaceHours
import com.fitbittrack.pulse.ui.model.Stage
import com.fitbittrack.pulse.ui.model.StageBlock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class SleepClockTest {

    @Test fun `bedtime sits at its place on the clock face`() {
        // 11 pm is the 11 o'clock position: 330 degrees from the top, 240 from 3 o'clock.
        val arc = clockArcs(LocalTime.of(23, 0), listOf(StageBlock(Stage.Light, 0, 60)), 12).single()
        assertEquals(240f, arc.startDegrees, 0.01f)
        assertEquals(30f, arc.sweepDegrees, 0.01f)
    }

    @Test fun `stages after midnight carry on round the face`() {
        // 11 pm + 4 h = 3 am, which is 3 o'clock: 0 degrees.
        val arc = clockArcs(LocalTime.of(23, 0), listOf(StageBlock(Stage.Deep, 240, 270)), 12).single()
        assertEquals(0f, arc.startDegrees, 0.01f)
        assertEquals(15f, arc.sweepDegrees, 0.01f)
    }

    @Test fun `neighbouring stages meet and the whole night adds up`() {
        val arcs = clockArcs(LocalTime.of(22, 15), listOf(StageBlock(Stage.Light, 0, 14), StageBlock(Stage.Deep, 14, 52), StageBlock(Stage.Rem, 52, 449)), 12)
        assertEquals(arcs[0].startDegrees + arcs[0].sweepDegrees, arcs[1].startDegrees, 0.01f)
        assertEquals(449 * 0.5f, arcs.map { it.sweepDegrees }.sum(), 0.01f)
    }

    @Test fun `back-to-back blocks of one stage become one arc`() {
        val arcs = clockArcs(LocalTime.of(23, 0), listOf(StageBlock(Stage.Light, 0, 20), StageBlock(Stage.Light, 20, 60), StageBlock(Stage.Light, 70, 80)), 12)
        assertEquals(listOf(30f, 5f), arcs.map { it.sweepDegrees })
    }

    @Test fun `empty blocks are dropped`() {
        assertEquals(0, clockArcs(LocalTime.of(23, 0), listOf(StageBlock(Stage.Awake, 5, 5)), 12).size)
    }

    @Test fun `nights over 12 hours use a 24-hour face and never lap`() {
        assertEquals(12, clockFaceHours(720))
        assertEquals(24, clockFaceHours(721))
        val arc = clockArcs(LocalTime.of(20, 0), listOf(StageBlock(Stage.Light, 0, 800)), clockFaceHours(800)).single()
        assertEquals(200f, arc.sweepDegrees, 0.01f)
    }
}
