package com.fitbittrack.pulse.ui

import com.fitbittrack.pulse.reminders.ReminderPrefs
import com.fitbittrack.pulse.training.HistoryRow
import com.fitbittrack.pulse.training.SessionEntity
import com.fitbittrack.pulse.training.SessionState
import com.fitbittrack.pulse.training.SetKind
import com.fitbittrack.pulse.ui.navigation.ExercisePick
import com.fitbittrack.pulse.ui.navigation.ExercisesRoute
import com.fitbittrack.pulse.ui.training.HistoryItem
import com.fitbittrack.pulse.ui.training.ProgressUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class TrainingUiTest {

    @Test fun everyExercisePickSurvivesTheRoute() {
        val all = listOf(
            ExercisePick.AddToSession("s1"), ExercisePick.SwapInSession("se1"), ExercisePick.AddSlot,
            ExercisePick.SwapSlot("slot-1"), ExercisePick.ForImport("Bench Press (Barbell)"),
        )
        all.forEach { assertEquals(it, ExercisesRoute.pick(it).choice) }
        assertNull(ExercisesRoute.browse().choice)
        assertNull(ExercisePick.decode("nonsense:1"))
    }

    @Test fun importKeysMayContainColons() {
        val pick = ExercisePick.ForImport("Row: Seated")
        assertEquals(pick, ExercisesRoute.pick(pick).choice)
    }

    private fun session(id: String, title: String, start: Long, end: Long?, unsure: Boolean = false) =
        SessionEntity(id, null, title, null, null, start, end, "UTC", "2026-11-20", SessionState.Finished, timeUnsure = unsure)

    private fun row(session: String, exercise: String) =
        HistoryRow(session, "2026-11-20", 0, "t", exercise, exercise, SetKind.Working, 100_000, 5, null, null, 0)

    private fun progress(sessions: List<SessionEntity>, rows: List<HistoryRow> = emptyList()) = ProgressUi(
        emptyList(), rows, emptyMap(), emptyMap(), sessions.map { HistoryItem(it, null, false) }, 0, 0, LocalDate.of(2026, 11, 25), DayOfWeek.MONDAY,
    )

    @Test fun bandWorkoutMatchesTheSameGymVisit() {
        val hour = 3_600_000L
        val ui = progress(listOf(
            session("unsure", "Imported", 0, hour, unsure = true),
            session("open", "Open", 0, null),
            session("gym", "Push A", 0, hour),
        ))
        assertEquals("gym", ui.sameVisit(10 * 60_000, hour)?.id)
        assertNull(ui.sameVisit(2 * hour, 3 * hour))
    }

    @Test fun workoutsPerExerciseCountsEachWorkoutOnce() {
        val ui = progress(emptyList(), listOf(row("a", "bench"), row("a", "bench"), row("b", "bench"), row("b", "squat")))
        assertEquals(mapOf("bench" to 2, "squat" to 1), ui.workoutsPerExercise)
    }

    @Test fun lastNamedIsTheNewest() {
        val ui = progress(listOf(session("new", "Push A", 0, 1), session("old", "Push A", 0, 1)))
        assertEquals("new", ui.lastNamed("Push A")?.id)
        assertNull(ui.lastNamed("Legs"))
    }

    @Test fun reminderSummary() {
        assertEquals("All off", ReminderPrefs().timedSummary)
        assertEquals("Evening, Morning", ReminderPrefs(eveningOn = true, morningOn = true).timedSummary)
    }
}
