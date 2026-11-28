package com.fitbittrack.pulse.training

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** ExerciseDB on a real Android database: the upgrade from the first layout, and the merge with Pulse's own list. */
@RunWith(AndroidJUnit4::class)
class ExerciseDbDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TrainingDatabase::class.java)

    @Test fun upgradeKeepsEverything() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO exercise (id, name, equipment, track, muscle, secondary, custom, favourite, archived, restSec, stepGrams) VALUES ('mine-1', 'My curl', 'Dumbbell', 'WeightReps', 'Arms', '', 1, 1, 0, 90, 2000)")
            db.execSQL("INSERT INTO session (id, plannedId, title, planTitle, week, startMs, endMs, zone, date, state, source, feeling, note, restEndsMs, restTotalSec, changedMs, timeUnsure) VALUES ('s1', NULL, 'Day 1', NULL, NULL, 1, 2, 'Asia/Kolkata', '2026-11-20', 'Finished', 'Pulse', NULL, '', NULL, 0, 2, 0)")
        }
        helper.runMigrationsAndValidate(name, 2, true).use { db ->
            db.query("SELECT name, favourite FROM exercise WHERE id = 'mine-1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("My curl", c.getString(0)); assertEquals(1, c.getInt(1)) }
            db.query("SELECT COUNT(*) FROM session").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM edb_exercise").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
        context.deleteDatabase(name)
    }

    @Test fun mergeTiesAddsAndSurvivesBackup() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrainingDatabase::class.java).build()
        try {
            val repo = TrainingRepository(context, db.dao(), TrainingPrefs(context), { Instant.parse("2026-11-25T06:00:00Z") }, { ZoneId.of("Asia/Kolkata") })
            repo.seed()
            val before = db.dao().allExercises().size
            db.dao().putEdb(listOf(
                EdbEntity("EIeI8Vf", "barbell bench press - medium grip", "chest", "barbell", "pectorals", "triceps", "Settle onto a bench.", 0),
                EdbEntity("new1", "landmine twist", "waist", "barbell", "obliques", "", "Twist.", 0),
            ))
            val m = repo.mergeExerciseDb()
            assertEquals(1, m.same.size)
            assertEquals("Barbell_Bench_Press_-_Medium_Grip", m.same.single().exerciseId)
            assertEquals(before + 1, db.dao().allExercises().size)
            assertNotNull(db.dao().exercise("edb-new1"))
            assertEquals("EIeI8Vf", repo.edbFor("Barbell_Bench_Press_-_Medium_Grip")?.id)
            assertEquals("new1", repo.edbFor("edb-new1")?.id)
            // Again: nothing doubles.
            repo.mergeExerciseDb()
            assertEquals(before + 1, db.dao().allExercises().size)
            // A backup brings the ties back, and a wipe ties again by itself.
            val file = repo.readBackup(repo.backup())
            assertEquals(1, file.links.size)
            repo.deleteAll()
            assertEquals("EIeI8Vf", repo.edbFor("Barbell_Bench_Press_-_Medium_Grip")?.id)
            repo.restore(file)
            assertEquals("EIeI8Vf", repo.edbFor("Barbell_Bench_Press_-_Medium_Grip")?.id)
        } finally {
            db.close()
        }
    }
}
