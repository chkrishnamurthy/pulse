package com.fitbittrack.pulse.training

import com.fitbittrack.pulse.chat.TrainingAnswers
import com.fitbittrack.pulse.chat.TrainingChatData
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.prefs.decodeTodayCards
import com.fitbittrack.pulse.ui.training.findExercises
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** The shapes here are those that several public Boostcamp tools describe. */
private val FILE = """
{"code":0,"data":{
 "2026-03-14":[{"id":"w1","title":"Day 1","name":"GZCLP","week":0,"day":1,"finished_at":"2026-03-14 18:28:05","records":[
   {"id":"r1","name":"Bench Press","type":"Barbell","sets":[
     {"archived_weight":45,"archived_reps":10,"is_warmup":true,"weight_unit":"kg","value":"20","amount":"10"},
     {"archived_weight":"132.277","archived_reps":"8","archived_rpe":0,"weight_unit":"kg","value":"57.5","amount":"8","target_type":"reps"},
     {"archived_weight":137.789,"archived_reps":6,"archived_rpe":8.5,"value":"","amount":"3+","target_type":"AMRAP"},
     {"archived_weight":0,"archived_reps":0},
     {"value":"60","amount":"8"},
     {"archived_weight":99,"archived_reps":5,"skipped":true}]},
   {"id":"r2","type":"Superset","uniq":"u9","supersets":[
     {"name":"Pull Up","type":"Bodyweight","sets":[{"archived_weight":0,"archived_reps":8}]},
     {"name":"Plank","type":"Other","sets":[{"archived_reps":60,"target_type":"time"}]}]},
   {"id":"r3","sets":[{"archived_weight":1,"archived_reps":1}]}]}],
 "not a day":[{}],
 "2026-03-16":[{"records":[{"name":"Squat","type":"Barbell","sets":[{"archived_weight":220.462,"archived_reps":5}]}]},{"id":"empty","records":[]}, 7]
}}
""".trimIndent()

class BoostcampImportTest {
    private val draft = BoostcampImport.read(FILE)
    private val w1 = draft.workouts.first { it.externalId == "w1" }

    @Test fun `reads what was lifted, never what was planned`() {
        val sets = w1.exercises.first { it.name == "Bench Press" }.sets
        assertEquals(listOf(45.0, 132.277, 137.789), sets.map { it.weight })
        assertEquals(listOf(10, 8, 6), sets.map { it.reps })
        assertEquals(listOf(SetKind.Warmup, SetKind.Working, SetKind.Max), sets.map { it.kind })
        assertEquals(listOf(null, null, 85), sets.map { it.rpe10 })
    }

    @Test fun `supersets are unpacked and timed sets are seconds`() {
        assertEquals(listOf("Bench Press", "Pull Up", "Plank"), w1.exercises.map { it.name })
        assertEquals(listOf(null, "u9", "u9"), w1.exercises.map { it.superset })
        assertEquals(60, w1.exercises.last().sets.single().seconds)
        assertNull(w1.exercises.last().sets.single().reps)
        assertNull("body weight is not a weight of zero", w1.exercises[1].sets.single().weight)
    }

    @Test fun `everything left out is counted`() = assertEquals(
        mapOf("empty sets" to 1, "sets with only planned numbers" to 1, "skipped sets" to 1, "exercises without a name" to 1, "days that can't be read" to 1, "workouts without sets" to 1, "workouts that can't be read" to 1),
        draft.leftOut,
    )

    @Test fun `the unit question shows both answers`() {
        val (_, e, s) = draft.sample(0)!!
        assertEquals("Squat", e.name)
        assertEquals(100_000.0, Weights.grams(s.weight!!, WeightUnit.Lb).toDouble(), 5.0)
        assertEquals("Bench Press", draft.sample(1)!!.second.name)
        assertEquals(draft.sample(0), draft.sample(2))
    }

    private fun rows(known: Set<String> = emptySet(), unit: FileUnit = FileUnit.Pounds, chosen: Map<String, String> = emptyMap()) =
        BoostcampImport.rows(draft, unit, chosen, known, "f.json", 1L, ZoneId.of("Asia/Kolkata"))

    @Test fun `weight in the file equals weight saved`() {
        for (u in FileUnit.entries) assertEquals(draft.volume(u), rows(unit = u).volume)
        assertEquals(6, rows().sets.size)
        assertEquals(2, rows().sessions.size)
    }

    @Test fun `the same file twice adds nothing`() {
        val first = rows()
        val again = rows(known = first.refs.map { it.externalId }.toSet())
        assertEquals(0, again.sessions.size)
        assertEquals(0, again.sets.size)
        assertEquals(2, again.already)
        assertEquals("a workout without an id gets the same one each time", BoostcampImport.read(FILE).workouts.map { it.externalId }, draft.workouts.map { it.externalId })
    }

    @Test fun `days stay days in every time zone`() {
        for (z in listOf("Asia/Kolkata", "America/Los_Angeles", "Pacific/Kiritimati")) {
            val r = BoostcampImport.rows(draft, FileUnit.Kilos, emptyMap(), emptySet(), "f", 1, ZoneId.of(z))
            assertEquals(listOf("2026-03-14", "2026-03-16"), r.sessions.map { it.date })
            assertTrue(r.sessions.all { it.timeUnsure && it.startMs < it.endMs!! })
        }
    }

    @Test fun `confirmed names become aliases, unknown ones new exercises`() {
        val key = w1.exercises[0].key
        val r = rows(chosen = mapOf(key to "bench"))
        assertTrue(r.exercises.any { it.exerciseId == "bench" && it.name == "Bench Press" })
        assertEquals(setOf("Pull Up", "Plank", "Squat"), r.newExercises.map { it.name }.toSet())
        assertEquals(Track.Time, r.newExercises.first { it.name == "Plank" }.track)
        assertEquals(Track.Reps, r.newExercises.first { it.name == "Pull Up" }.track)
        assertEquals(listOf(AliasEntity(key, "bench")), r.aliases)
    }

    @Test fun `other files are refused in plain words`() {
        for (bad in listOf("", "hello", "[]", "{}", """{"data":[]}""", """{"data":{}}""", """{"data":{"2026-01-01":[]}}""", "null", "{"))
            assertThrows(bad, IllegalArgumentException::class.java) { BoostcampImport.read(bad) }
    }

    @Test fun `strange numbers do no harm`() {
        val x = BoostcampImport.read("""{"2026-01-01":[{"id":"a","records":[{"name":"X","sets":[{"archived_weight":"NaN","archived_reps":"5"},{"archived_weight":1e12,"archived_reps":5},{"archived_weight":-3,"archived_reps":5},{"archived_weight":50,"archived_reps":1e9},{"archived_weight":50,"archived_reps":"abc"}]}]}]}""")
        assertEquals(listOf(null, null, null), x.workouts.single().exercises.single().sets.map { it.weight })
    }
}

class ImportNamesTest {
    private fun e(id: String, name: String, eq: Equipment, custom: Boolean = false) = ExerciseEntity(id, name, eq, Track.WeightReps, Muscle.Chest, custom = custom)
    private val all = listOf(
        e("bb", "Barbell Bench Press - Medium Grip", Equipment.Barbell), e("db", "Dumbbell Bench Press", Equipment.Dumbbell), e("row", "Bent Over Barbell Row", Equipment.Barbell), e("pd", "Triceps Pushdown", Equipment.Cable),
    )
    private fun x(name: String, eq: String?) = ImportExercise(name, eq, null, emptyList())

    @Test fun `only sure names match by themselves`() {
        assertEquals("bb", ImportNames.sure(x("Bench Press", "Barbell"), all, emptyMap())?.id)
        assertEquals("db", ImportNames.sure(x("Bench Press", "Dumbbell"), all, emptyMap())?.id)
        assertNull("barbell or dumbbell?", ImportNames.sure(x("Bench Press", null), all, emptyMap()))
        assertNull(ImportNames.sure(x("Row", "Barbell"), all, emptyMap()))
        assertEquals("pd", ImportNames.sure(x("Tricep Pushdowns", "Cable"), all, emptyMap())?.id)
    }

    @Test fun `what the user confirmed is remembered`() = assertEquals("row", ImportNames.sure(x("Row", "Barbell"), all, mapOf(ImportNames.key("Row", "Barbell") to "row"))?.id)

    @Test fun `a guess needs most words in common`() {
        assertEquals("row", ImportNames.guess(x("Bent Over Row", "Barbell"), all)?.id)
        assertNull(ImportNames.guess(x("Leg Press 45", "Machine"), all))
    }
}

class CatalogTest {
    private val items = ExerciseCatalog.parse(File("src/main/assets/exercises.json").readText())

    @Test fun `the catalogue is complete and readable`() {
        assertEquals(ExerciseCatalog.SIZE, items.size)
        assertEquals(items.size, items.map { it.id }.distinct().size)
        assertTrue(items.all { it.n.isNotBlank() && it.m !in it.s })
        assertTrue(items.count { it.h.isNotEmpty() } > 800)
    }

    @Test fun `search finds by words in any order, favourites first`() {
        val all = items.map { it.entity() }
        val rows = findExercises(all, "row barbell", null, null, false, emptyMap())
        assertTrue(rows.isNotEmpty() && rows.all { "row" in it.name.lowercase() || "barbell" in it.equipment.label.lowercase() })
        val fav = all.map { if (it.id == rows.last().id) it.copy(favourite = true) else it }
        assertEquals(rows.last().id, findExercises(fav, "row barbell", null, null, false, emptyMap()).first().id)
        assertEquals(0, findExercises(all, "zzzz", null, null, false, emptyMap()).size)
        assertTrue(findExercises(all, "", Muscle.Chest, Equipment.Cable, false, emptyMap()).all { it.muscle == Muscle.Chest && it.equipment == Equipment.Cable })
    }
}

class BackupTest {
    private fun file() = BackupFile(
        savedAtMs = 5, exercises = listOf(ExerciseEntity("e", "Bench", Equipment.Barbell, Track.WeightReps, Muscle.Chest)), programs = listOf(ProgramEntity("p", "Plan", 3, 1)),
        revisions = listOf(RevisionEntity("r", "p", 1, 1, PlanDoc(listOf(PlanWeek(listOf(PlanDay("d", "Day"))))).encode())),
        sessions = listOf(SessionEntity("s", null, "A", null, null, 1, 2, "UTC", "2026-11-24", SessionState.Finished)),
        sessionExercises = listOf(SessionExerciseEntity("x", "s", "e", "Bench", 0), SessionExerciseEntity("orphan", "gone", "e", "Bench", 0)),
        sets = listOf(SetEntity("1", "x", 0, SetKind.Working, 60_000, 8, done = true), SetEntity("2", "orphan", 0, SetKind.Working)), settings = mapOf("unit" to "Lb"),
    )
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    private fun text(f: BackupFile) = json.encodeToString(BackupFile.serializer(), f)

    @Test fun `a copy comes back the same, without loose ends`() {
        val b = Backups.read(text(file()))
        assertEquals(file().exercises, b.exercises)
        assertEquals(file().sessions, b.sessions)
        assertEquals(listOf("x"), b.sessionExercises.map { it.id })
        assertEquals(listOf("1"), b.sets.map { it.id })
        assertEquals(mapOf("unit" to "Lb"), b.settings)
    }

    @Test fun `other and damaged files are refused`() {
        for (bad in listOf("", "{}", "hello", text(BackupFile(savedAtMs = 1)), text(file().copy(format = 99)), text(file().copy(revisions = listOf(RevisionEntity("r", "p", 1, 1, "{broken"))))))
            assertThrows(bad.take(40), IllegalArgumentException::class.java) { Backups.read(bad) }
    }
}

class TrainingAnswersTest {
    private val today = LocalDate.of(2026, 11, 24)
    private fun s(id: String, day: Long, ex: String, kg: Int, reps: Int) = DatedSet(id, today.minusDays(day), ex, DoneSet(SetKind.Working, kg * 1000, reps))
    private val d = TrainingChatData(
        today, listOf(s("a", 21, "bench", 60, 5), s("b", 7, "bench", 65, 5), s("b", 7, "row", 50, 8)), mapOf("bench" to "Barbell Bench Press - Medium Grip", "row" to "Bent Over Barbell Row"),
        emptyList(), "Upper A" to today, "Strength Base", WeightUnit.Kg, Locale.US,
    )

    @Test fun `bench question gives the estimated lift`() {
        val a = TrainingAnswers.answer("How is my bench press?", d)!!
        assertEquals("75.8", a.big)
        assertEquals("kg", a.unit)
        assertTrue(a.direct, "up 5.8 kg since 3 November" in a.direct)
        assertEquals(2, a.bars.size)
    }

    @Test fun `next workout and records`() {
        assertTrue(TrainingAnswers.answer("When is my next workout?", d)!!.direct.contains("Upper A, today"))
        assertTrue(TrainingAnswers.answer("next workout", d.copy(next = null))!!.direct.contains("No workout is planned"))
        assertNotNull(TrainingAnswers.answer("What are my records?", d))
    }

    @Test fun `other questions are left to the rest of the chat`() {
        for (q in listOf("How did I sleep?", "How many steps today?", "Is my resting heart rate normal for me?", "How many workouts did I do this week?", "How close am I to my calorie goal?", "Compare my steps with last week", "How many floors did I climb?")) assertNull(q, TrainingAnswers.answer(q, d))
    }

    @Test fun `an exercise never done is said plainly`() = assertFalse(TrainingAnswers.answer("How is my squat?", d)!!.hasData)
}

class TodayCardTest {
    @Test fun `the training card appears once, behind the circle`() {
        val cards = decodeTodayCards("Band:1,Ring:1,Air:0")
        assertEquals(listOf(TodayCard.Band, TodayCard.Ring, TodayCard.Train), cards.take(3).map { it.first })
        assertTrue(cards.first { it.first == TodayCard.Train }.second)
        assertEquals(1, cards.count { it.first == TodayCard.Train })
        assertFalse("hidden stays hidden", decodeTodayCards("Ring:1,Train:0").first { it.first == TodayCard.Train }.second)
    }
}

class ReminderTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")
    private fun ago(days: Long) = now.minusSeconds(days * 86_400).toEpochMilli()
    private val on = TrainingSettings(backupReminder = true)

    @Test fun `backup reminder comes only with something new, every two weeks`() {
        assertFalse(TrainingReminder.backupDue(on.copy(lastBackupMs = ago(13)), ago(1), now))
        assertTrue(TrainingReminder.backupDue(on.copy(lastBackupMs = ago(14)), ago(1), now))
        assertFalse(TrainingReminder.backupDue(on.copy(lastBackupMs = ago(15)), ago(1), now))
        assertTrue(TrainingReminder.backupDue(on.copy(lastBackupMs = ago(28)), ago(1), now))
        assertFalse("nothing new", TrainingReminder.backupDue(on.copy(lastBackupMs = ago(14)), ago(20), now))
        assertFalse(TrainingReminder.backupDue(on.copy(backupReminder = false, lastBackupMs = ago(14)), ago(1), now))
    }

    @Test fun `first copy is asked for at most once in two weeks`() {
        val asked = (0L until 28).count { TrainingReminder.backupDue(on, now.plusSeconds(it * 86_400).toEpochMilli(), now.plusSeconds(it * 86_400)) }
        assertEquals(2, asked)
    }

    @Suppress("unused") private val zone = ZoneOffset.UTC
}
