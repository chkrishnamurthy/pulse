package com.fitbittrack.pulse.training

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The whole way on a real Android database, in memory: the user's own data is never touched. */
@RunWith(AndroidJUnit4::class)
class TrainingDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: TrainingDatabase
    private lateinit var repo: TrainingRepository
    private var now: Instant = Instant.parse("2026-11-24T06:00:00Z")
    private val zone = ZoneId.of("Asia/Kolkata")
    private val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    private val dao get() = db.dao()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, TrainingDatabase::class.java).build()
        repo = TrainingRepository(context, db.dao(), TrainingPrefs(context), { now }, { zone })
        runBlocking { repo.seed() }
    }

    @After fun close() = db.close()

    private fun bench() = "Barbell_Bench_Press_-_Medium_Grip"

    /** A plan of two weeks and two days a week, each with one exercise of three sets. */
    private suspend fun plan(): String {
        val id = repo.createPlan("Base", 2, setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), easyWeeks = false)
        val doc = decodePlan(dao.latestRevisionNow(id)!!.doc)
        repo.savePlan(id, PlanDoc(doc.weeks.map { w -> w.copy(days = w.days.map { it.copy(slots = listOf(Slot("s-${it.id}", bench(), working = 3, repsMin = 6, repsMax = 8, restSec = 90))) }) }))
        assertTrue(repo.startPlan(id, LocalDate.parse("2026-11-24"), setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)))
        return id
    }

    private suspend fun planned() = dao.plannedNow(dao.activeEnrollmentNow()!!.id)

    private suspend fun doWorkout(plannedId: String, kg: Double, reps: Int): String {
        val sid = repo.startPlanned(plannedId)!!
        dao.setsOfSessionNow(sid).forEach { s -> repo.changeSet(s.id) { it.copy(grams = Weights.grams(kg, WeightUnit.Kg), reps = reps) }; repo.tick(s.id, true) }
        now = now.plusSeconds(3600)
        repo.finish(sid)
        return sid
    }

    @Test fun catalogueIsThereOnceAndKeepsChanges() = runBlocking {
        assertEquals(ExerciseCatalog.SIZE, dao.seedCount())
        repo.toggleFavourite(bench())
        repo.seed()
        assertEquals(ExerciseCatalog.SIZE, dao.seedCount())
        assertTrue(dao.exercise(bench())!!.favourite)
    }

    @Test fun planGivesDatesInOrder() = runBlocking {
        plan()
        assertEquals(listOf("2026-11-24", "2026-11-27", "2026-12-01", "2026-12-04"), planned().map { it.date })
    }

    @Test fun onlyOneWorkoutAtATime() = runBlocking {
        plan()
        val a = repo.startPlanned(planned()[0].id)
        assertEquals(a, repo.startPlanned(planned()[1].id))
        assertEquals(a, repo.startEmpty())
    }

    @Test fun tickStartsRestAndLastSetDoesNot() = runBlocking {
        plan()
        val sid = repo.startPlanned(planned()[0].id)!!
        val sets = dao.setsOfSessionNow(sid)
        assertEquals(3, sets.size)
        repo.tick(sets[0].id, true)
        assertEquals(now.toEpochMilli() + 90_000, dao.session(sid)!!.restEndsMs)
        repo.addRest(sid, 15)
        assertEquals(now.toEpochMilli() + 105_000, dao.session(sid)!!.restEndsMs)
        repo.skipRest(sid)
        repo.tick(sets[1].id, true)
        repo.skipRest(sid)
        repo.tick(sets[2].id, true)
        assertNull("no rest after the last set", dao.session(sid)!!.restEndsMs)
    }

    @Test fun finishedWorkoutIsHistoryAndNextTimeSuggestsMore() = runBlocking {
        plan()
        val first = planned()[0]
        val sid = doWorkout(first.id, 60.0, 8)
        assertEquals(PlannedState.Done, dao.plannedOne(first.id)!!.state)
        assertNull(dao.activeSessionNow())
        val rows = dao.history().first()
        assertEquals(3, rows.size)
        assertEquals(3 * 8 * 60_000L, dao.totals().first().single { it.sessionId == sid }.volume)
        val p = repo.previewDay(planned()[1]).single()
        assertEquals(62_500, p.suggestion.grams)
        assertTrue(p.suggestion.reason, "8, 8, 8" in p.suggestion.reason)
    }

    @Test fun setsWithoutATickAreNotHistory() = runBlocking {
        plan()
        val sid = repo.startPlanned(planned()[0].id)!!
        repo.tick(dao.setsOfSessionNow(sid)[0].id, true)
        repo.finish(sid)
        assertEquals(1, dao.history().first().size)
    }

    @Test fun editingAPlanNeverChangesHistory() = runBlocking {
        val id = plan()
        doWorkout(planned()[0].id, 60.0, 8)
        val before = dao.history().first()
        val doc = decodePlan(dao.latestRevisionNow(id)!!.doc)
        val changed = doc.day(0, 0)!!.copy(name = "Renamed", slots = emptyList())
        repo.savePlan(id, PlanEdits.putDay(doc, 0, 0, changed, Scope.Whole))
        assertEquals(before, dao.history().first())
        assertEquals(1, planned().count { it.state == PlannedState.Done })
        assertEquals(4, planned().size)
        assertEquals("Renamed", planned().last { it.day == 0 }.title)
        assertEquals("dates stay", listOf("2026-11-27", "2026-12-01", "2026-12-04"), planned().filter { it.state == PlannedState.Planned }.map { it.date })
    }

    @Test fun addedDaysGetDatesAndKeepOrder() = runBlocking {
        val id = plan()
        doWorkout(planned()[0].id, 60.0, 8)
        val doc = decodePlan(dao.latestRevisionNow(id)!!.doc)
        repo.savePlan(id, PlanEdits.addDay(doc, 1, "Extra") { "x" + System.nanoTime() })
        val open = planned().filter { it.state == PlannedState.Planned }
        assertEquals(4, open.size)
        assertEquals(open.map { it.date }.sorted(), open.map { it.date })
        assertEquals(4, open.map { it.date }.distinct().size)
        assertTrue(open.none { it.date.isEmpty() })
        assertEquals(planned().size, planned().map { it.seq }.distinct().size)
    }

    @Test fun shiftAndUndo() = runBlocking {
        plan()
        val items = planned().map { it.item() }
        val after = ScheduleRules.shift(items, items[0].id, setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), LocalDate.parse("2026-11-24"))
        repo.saveSchedule("Plan shifted", after)
        assertEquals(listOf("2026-11-27", "2026-12-01", "2026-12-04", "2026-12-08"), planned().map { it.date })
        repo.undoSchedule(dao.lastUndo().first()!!)
        assertEquals(listOf("2026-11-24", "2026-11-27", "2026-12-01", "2026-12-04"), planned().map { it.date })
        assertNull(dao.lastUndo().first())
    }

    @Test fun undoNeverReopensADoneWorkout() = runBlocking {
        plan()
        val items = planned().map { it.item() }
        repo.saveSchedule("Plan shifted", ScheduleRules.shift(items, items[0].id, setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), LocalDate.parse("2026-11-24")))
        doWorkout(planned()[0].id, 60.0, 8)
        repo.undoSchedule(dao.lastUndo().first()!!)
        assertEquals(PlannedState.Done, planned()[0].state)
    }

    @Test fun lastWorkoutFinishesThePlan() = runBlocking {
        plan()
        val e = dao.activeEnrollmentNow()!!.id
        planned().map { it.id }.forEach { doWorkout(it, 60.0, 8) }
        assertNull(dao.activeEnrollmentNow())
        assertEquals(EnrollState.Finished, dao.enrollment(e)!!.state)
    }

    @Test fun discardOpensThePlannedDayAgainAndRestoreBringsItBack() = runBlocking {
        plan()
        val p = planned()[0].id
        val sid = doWorkout(p, 60.0, 8)
        repo.discard(sid)
        assertEquals(0, dao.history().first().size)
        assertEquals(PlannedState.Planned, dao.plannedOne(p)!!.state)
        repo.restore(sid)
        assertEquals(3, dao.history().first().size)
        repo.discard(sid)
        now = now.plusSeconds(31L * 86_400)
        repo.cleanUp()
        assertNull(dao.session(sid))
        assertEquals(0, dao.allSets().size)
    }

    @Test fun forgottenWorkoutEndsAfterItsLastSet() = runBlocking {
        val sid = repo.startEmpty()
        repo.addToSession(sid, bench())
        repo.tick(dao.setsOfSessionNow(sid).first().id, true)
        now = now.plusSeconds(9 * 3600)
        val s = repo.finish(sid)!!
        assertTrue(s.endMs!! - s.startMs < 10 * 60_000)
    }

    @Test fun swapKeepsWhatWasDone() = runBlocking {
        plan()
        val sid = repo.startPlanned(planned()[0].id)!!
        repo.tick(dao.setsOfSessionNow(sid)[0].id, true)
        repo.swap(dao.sessionExercisesNow(sid).single().id, "Dumbbell_Bench_Press", fromNow = true)
        val ex = dao.sessionExercisesNow(sid)
        assertEquals(listOf(bench(), "Dumbbell_Bench_Press"), ex.map { it.exerciseId })
        assertEquals(listOf(1, 2), ex.map { dao.setsOf(it.id).size })
        assertEquals("Dumbbell_Bench_Press", repo.previewDay(planned()[0]).single().slot.exerciseId)
        assertEquals("Dumbbell_Bench_Press", repo.previewDay(planned().first { it.week == 1 && it.day == 0 }).single().slot.exerciseId)
        assertEquals("the other day stays", bench(), repo.previewDay(planned().first { it.day == 1 }).single().slot.exerciseId)
    }

    @Test fun supersetRestsOnlyAfterTheRound() = runBlocking {
        val id = repo.createPlan("SS", 1, setOf(DayOfWeek.TUESDAY), false)
        val doc = decodePlan(dao.latestRevisionNow(id)!!.doc)
        val slots = listOf(Slot("a", bench(), working = 2, superset = "A"), Slot("b", "Triceps_Pushdown", working = 2, superset = "A"))
        repo.savePlan(id, PlanEdits.putDay(doc, 0, 0, doc.day(0, 0)!!.copy(slots = slots), Scope.Whole))
        repo.startPlan(id, LocalDate.parse("2026-11-24"), setOf(DayOfWeek.TUESDAY))
        val sid = repo.startPlanned(planned()[0].id)!!
        val s = dao.setsOfSessionNow(sid)
        repo.tick(s[0].id, true)
        assertNull(dao.session(sid)!!.restEndsMs)
        repo.tick(s[2].id, true)
        assertNotNull(dao.session(sid)!!.restEndsMs)
    }

    @Test fun ownExerciseIsHiddenOnceUsed() = runBlocking {
        val mine = repo.saveExercise(ExerciseEntity("", "Cable row", Equipment.Cable, Track.WeightReps, Muscle.Back))
        val other = repo.saveExercise(ExerciseEntity("", "Never used", Equipment.Cable, Track.WeightReps, Muscle.Back))
        val sid = repo.startEmpty()
        repo.addToSession(sid, mine)
        repo.removeExercise(mine)
        repo.removeExercise(other)
        assertTrue(dao.exercise(mine)!!.archived)
        assertNull(dao.exercise(other))
        repo.removeExercise(bench())
        assertNotNull("catalogue exercises can't be deleted", dao.exercise(bench()))
    }

    @Test fun importSavesOnceAndCanBeUndone() = runBlocking {
        val text = """{"data":{"2026-03-14":[{"id":"w1","title":"Day 1","records":[{"name":"Bench Press","type":"Barbell","sets":[{"archived_weight":220.462,"archived_reps":5}]}]}]}}"""
        suspend fun go(): Int {
            val draft = BoostcampImport.read(text)
            val r = BoostcampImport.rows(draft, FileUnit.Pounds, emptyMap(), dao.knownExternal(BoostcampImport.SOURCE).toSet(), "f", now.toEpochMilli(), zone)
            if (r.sessions.isNotEmpty()) dao.saveImport(r.batch, r.newExercises, r.aliases, r.sessions, r.exercises, r.sets, r.refs)
            return r.sessions.size
        }
        assertEquals(1, go())
        assertEquals(0, go())
        assertEquals(100_000, dao.history().first().single().grams!! / 10 * 10)
        dao.undoImport(dao.batches().first().single().id)
        assertEquals(0, dao.history().first().size)
        assertEquals(1, go())
    }

    @Test fun backupBringsEverythingBack() = runBlocking {
        plan()
        doWorkout(planned()[0].id, 60.0, 8)
        val text = repo.backup()
        val before = Triple(dao.history().first(), planned(), dao.allRevisions().size)
        dao.wipe()
        assertEquals(0, dao.history().first().size)
        repo.restore(repo.readBackup(text))
        assertEquals(before, Triple(dao.history().first(), planned(), dao.allRevisions().size))
        assertEquals(ExerciseCatalog.SIZE, dao.seedCount())
        assertFalse(TrainingPrefs(context).settings.first().healthConnect)
    }

    @Test fun stoppingAPlanKeepsHistory() = runBlocking {
        plan()
        doWorkout(planned()[0].id, 60.0, 8)
        repo.stopPlan()
        assertNull(dao.activeEnrollmentNow())
        assertEquals(3, dao.history().first().size)
        assertEquals(1, dao.allPlanned().size)
    }

    @Test fun restartGivesNewDatesAndKeepsDone() = runBlocking {
        plan()
        doWorkout(planned()[0].id, 60.0, 8)
        repo.restartFrom(0, LocalDate.parse("2026-11-25"))
        assertEquals(1, planned().count { it.state == PlannedState.Done })
        assertEquals(listOf("2026-11-27", "2026-12-01", "2026-12-04", "2026-12-08"), planned().filter { it.state == PlannedState.Planned }.map { it.date })
    }

    @Test fun allTrainingDays() = assertTrue(days.isNotEmpty())
}
