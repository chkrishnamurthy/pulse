package com.fitbittrack.pulse.training

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

private fun edb(id: String, name: String, targets: String = "pectorals", parts: String = "chest", equip: String = "barbell", second: String = "", steps: String = "Push.") =
    EdbEntity(id, name, parts, equip, targets, second, steps, 0)

private fun pulse(id: String, name: String, custom: Boolean = false) = ExerciseEntity(id, name, Equipment.Barbell, Track.WeightReps, Muscle.Chest, custom = custom)

class EdbRulesTest {
    @Test fun `steps lose their number`() {
        assertEquals("Settle onto a bench.", EdbRules.cleanStep("Step:1 Settle onto a bench."))
        assertEquals("Brace.", EdbRules.cleanStep("step 12: Brace."))
        assertEquals("Plain text stays.", EdbRules.cleanStep("Plain text stays."))
    }

    @Test fun `names read like titles`() {
        assertEquals("Barbell Bench Press", EdbRules.title("barbell bench press"))
        assertEquals("EZ Bar Curl", EdbRules.title("ez bar curl"))
        assertEquals("Push-up to Side Plank", EdbRules.title("push-up to side plank"))
    }

    @Test fun `muscles and equipment map to Pulse groups`() {
        assertEquals(Muscle.Chest, EdbRules.muscle("pectorals"))
        assertEquals(Muscle.Back, EdbRules.muscle("latissimus dorsi"))
        assertEquals(Muscle.BackLegs, EdbRules.muscle("glutes"))
        assertEquals(Muscle.Other, EdbRules.muscle("cardiovascular system"))
        assertEquals(Equipment.Barbell, EdbRules.equipment(listOf("EZ bar")))
        assertEquals(Equipment.Machine, EdbRules.equipment(listOf("leverage machine")))
        assertEquals(Equipment.Body, EdbRules.equipment(listOf("bodyweight")))
        assertEquals(Equipment.Other, EdbRules.equipment(listOf("tire")))
    }

    @Test fun `what a set counts`() {
        assertEquals(Track.WeightReps, EdbRules.track(edb("1", "barbell bench press")))
        assertEquals(Track.Reps, EdbRules.track(edb("2", "push up", equip = "bodyweight")))
        assertEquals(Track.Time, EdbRules.track(edb("3", "front plank with twist", equip = "bodyweight")))
        assertEquals(Track.Time, EdbRules.track(edb("4", "jumping jack", parts = "cardio", equip = "bodyweight")))
    }

    @Test fun `main muscle falls back to the body part`() {
        assertEquals(Muscle.Chest, EdbRules.mainMuscle(edb("1", "x")))
        assertEquals(Muscle.Calves, EdbRules.mainMuscle(edb("2", "x", targets = "cardiovascular system", parts = "lower legs")))
        val e = EdbRules.pulseExercise(edb("abc", "barbell bench press", second = "triceps\ndeltoids"))
        assertEquals("edb-abc", e.id)
        assertEquals("Barbell Bench Press", e.name)
        assertEquals(listOf(Muscle.Arms, Muscle.Shoulders), e.alsoTrains)
        assertEquals(150, e.restSec)
    }

    @Test fun `ids go both ways`() {
        assertEquals("EIeI8Vf", EdbRules.edbIdOf(EdbRules.exerciseId("EIeI8Vf")))
        assertNull(EdbRules.edbIdOf("Barbell_Squat"))
    }

    @Test fun `likeness of names`() {
        assertEquals(1.0, EdbRules.likeness("bench press", "bench press"), 0.0)
        assertTrue(EdbRules.likeness("dumbbell flye", "dumbbell fly") > EdbRules.CLOSE)
        assertTrue(EdbRules.likeness("bench press", "leg curl") < 0.5)
    }

    @Test fun `same names are tied, the rest added, nothing of Pulse removed`() {
        val p = listOf(pulse("Barbell_Bench_Press", "Barbell Bench Press"), pulse("Plank", "Plank"), pulse("mine-1", "Barbell Bench Press", custom = true))
        val e = listOf(edb("A", "barbell bench press"), edb("B", "dumbbell fly"))
        val m = EdbRules.merge(p, e, emptyList())
        assertEquals(listOf(EdbLinkEntity("Barbell_Bench_Press", "A", LinkHow.Same)), m.same)
        assertEquals(listOf("edb-B"), m.add.map { it.id })
    }

    @Test fun `merge runs again without doubles`() {
        val p = listOf(pulse("Barbell_Bench_Press", "Barbell Bench Press"))
        val e = listOf(edb("A", "barbell bench press"), edb("B", "dumbbell fly"))
        val first = EdbRules.merge(p, e, emptyList())
        val second = EdbRules.merge(p + first.add, e, first.same)
        assertTrue(second.same.isEmpty())
        assertTrue(second.add.isEmpty())
    }

    @Test fun `close names are asked once, a no is remembered`() {
        val p = listOf(pulse("Dumbbell_Flyes", "Dumbbell Flyes"))
        val e = listOf(edb("B", "dumbbell fly"), edb("C", "cable row"))
        // "Flyes" loses its plural and "fly" stays: close, not the same.
        val m = EdbRules.merge(p, e, emptyList())
        assertEquals(listOf("Dumbbell_Flyes" to "B"), m.close)
        val after = EdbRules.merge(p, e, listOf(EdbLinkEntity("Dumbbell_Flyes", "B", LinkHow.Declined)))
        assertTrue(after.close.isEmpty())
        val chosen = EdbRules.merge(p, e, listOf(EdbLinkEntity("Dumbbell_Flyes", "B", LinkHow.Chosen)))
        assertTrue(chosen.close.isEmpty())
        assertFalse("a chosen one is not added again", chosen.add.any { it.id == "edb-B" })
    }

    @Test fun `api item becomes a row`() {
        val row = EdbItem("X", " barbell row ", targetMuscles = listOf("upper back"), instructions = listOf("Step:1 Hinge.", "Step:2 Pull.")).entity(5)
        assertEquals("barbell row", row.name)
        assertEquals(listOf("Hinge.", "Pull."), row.stepList())
        assertEquals(listOf("upper back"), row.targetList())
    }
}

class EdbPaceTest {
    @Test fun `requests keep their distance`() {
        assertEquals(1_200, EdbPace.gap(1_000, 1_000))
        assertEquals(200, EdbPace.gap(1_000, 2_000))
        assertEquals(0, EdbPace.gap(1_000, 5_000))
    }

    @Test fun `longer waits, then stop`() {
        assertEquals(10_000L, EdbPace.wait(1, null))
        assertEquals(30_000L, EdbPace.wait(2, null))
        assertEquals(120_000L, EdbPace.wait(3, null))
        assertNull(EdbPace.wait(4, null))
        assertEquals("the server's longer wait wins", 60_000L, EdbPace.wait(1, 60))
    }

    @Test fun `which answers are worth another try`() {
        assertTrue(EdbPace.retry(429))
        assertTrue(EdbPace.retry(503))
        assertFalse(EdbPace.retry(404))
        assertFalse(EdbPace.retry(400))
    }

    @Test fun `best picture first`() {
        assertEquals(Picture.Saved, EdbPictures.pick("a", saved = true, online = false))
        assertEquals(Picture.Download, EdbPictures.pick("a", saved = false, online = true))
        assertEquals(Picture.Drawing, EdbPictures.pick("a", saved = false, online = false))
        assertEquals(Picture.Drawing, EdbPictures.pick(null, saved = false, online = true))
    }
}

class EdbClientTest {
    private lateinit var server: MockWebServer
    private val slept = mutableListOf<Long>()
    private var clock = 0L
    private lateinit var client: EdbClient

    @Before fun start() {
        server = MockWebServer()
        server.start()
        client = EdbClient(base = server.url("/").toString().trimEnd('/'), now = { clock }, sleep = { slept += it; clock += it })
    }

    @After fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).addHeader("Content-Type", "application/json").setBody(body)

    private val page1 = """{"success":true,"meta":{"total":2,"hasNextPage":true,"nextCursor":"A"},"data":[{"exerciseId":"A","name":"a","instructions":["Step:1 Go."]}]}"""
    private val page2 = """{"success":true,"meta":{"total":2,"hasNextPage":false},"data":[{"exerciseId":"B","name":"b","extra":1}]}"""

    @Test fun `pages follow the cursor and keep their distance`() = runTest {
        server.enqueue(json(page1))
        server.enqueue(json(page2))
        val a = client.page(null)
        assertEquals(listOf("A"), a.items.map { it.exerciseId })
        assertEquals("A", a.next)
        val b = client.page(a.next)
        assertNull(b.next)
        assertEquals("/api/v1/exercises?limit=25", server.takeRequest().path)
        assertEquals("/api/v1/exercises?limit=25&after=A", server.takeRequest().path)
        assertTrue("waited between the two", slept.any { it >= EdbPace.GAP_MS })
    }

    @Test fun `too many requests, so it waits and tries again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(json(page2))
        assertEquals(listOf("B"), client.page(null).items.map { it.exerciseId })
        assertTrue(slept.contains(10_000L))
    }

    @Test fun `gives up after three tries`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        try {
            client.page(null)
            fail("should give up")
        } catch (_: EdbUnavailable) {
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun `a missing page is not tried again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try { client.page(null); fail() } catch (e: java.io.IOException) { assertFalse(e is EdbUnavailable) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `a missing animation is skipped, not retried, and does not rest the app`() = runTest {
        val dir = Files.createTempDirectory("edb").toFile()
        server.enqueue(MockResponse().setResponseCode(404))
        try { client.animation("X", File(dir, "X.gif")); fail() } catch (e: java.io.IOException) { assertTrue(e is EdbMissing) }
        assertEquals(1, server.requestCount)
        assertFalse(File(dir, "X.gif").exists())
        dir.deleteRecursively()
    }

    @Test fun `animation is saved whole, and only pictures are kept`() = runTest {
        val dir = Files.createTempDirectory("edb").toFile()
        val gif = ByteArray(500) { (it % 7).toByte() }
        server.enqueue(MockResponse().addHeader("Content-Type", "image/gif").setBody(okio.Buffer().write(gif)))
        val to = File(dir, "A.gif")
        client.animation("A", to)
        assertEquals(500L, to.length())
        assertEquals("/media/A.gif", server.takeRequest().path)
        server.enqueue(json("{}"))
        val bad = File(dir, "B.gif")
        try { client.animation("B", bad); fail() } catch (_: java.io.IOException) {}
        assertFalse(bad.exists())
        assertFalse(File(dir, "B.gif.part").exists())
        dir.deleteRecursively()
    }
}
