package com.fitbittrack.pulse.chat

import com.fitbittrack.pulse.domain.DatedGoals
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.Workout
import com.fitbittrack.pulse.ui.model.Units
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

private val today: LocalDate = LocalDate.of(2026, 11, 23) // a Monday

private fun night(wake: LocalDate, minutes: Long): SleepSession {
    val end = wake.atTime(7, 0).toInstant(ZoneOffset.UTC)
    return SleepSession("n$wake", end.minusSeconds(minutes * 60 + 1800), end, emptyList(), minutes, true, wake)
}

private fun data(
    days: List<DaySummary> = emptyList(),
    nights: List<SleepSession> = emptyList(),
    workouts: List<Workout> = emptyList(),
    goals: List<DatedGoals> = listOf(DatedGoals(LocalDate.ofEpochDay(0), Goals())),
) = ChatData(today, days, nights, workouts, goals, Units.Metric, Locale.US, ZoneOffset.UTC)

private fun ask(q: String, d: ChatData): Answer = AnswerBuilder.build(QuestionRouter.route(q, today) as Route.Data, d)

class QuestionRouterTest {

    private fun topic(q: String) = (QuestionRouter.route(q, today) as? Route.Data)?.topic

    @Test fun `possible emergencies come before everything else`() {
        for (q in listOf("I have chest pain, how is my heart rate?", "I can't breathe well", "I fainted after my run", "am I having a heart attack", "seene mein dard hai", "shortness of breath when I walk"))
            assertEquals(q, Route.Emergency(crisis = false), QuestionRouter.route(q, today))
        for (q in listOf("I want to die", "thinking about suicide, I sleep badly", "I want to hurt myself"))
            assertEquals(q, Route.Emergency(crisis = true), QuestionRouter.route(q, today))
    }

    @Test fun `illness and medicine get no health answer`() {
        for (q in listOf(
            "Should I take a sleeping pill?", "Do I have sleep apnea?", "What is my blood pressure?", "Is this diabetes?", "How much melatonin for sleep",
            "my knee hurts when I walk", "do I have a fever, skin temperature is up", "why is my heartbeat irregular", "should I see a doctor about my heart rate",
            "what dose of insulin", "am I sick", "am I depressed because of sleep", "mujhe sar dard hai",
        )) assertEquals(q, Route.Medical, QuestionRouter.route(q, today))
    }

    @Test fun `words inside other words do not trigger the medical rule`() {
        // "ill" in "still", "bp" in "bpm", "pill" in "spill", "pain" in "Spain"
        assertEquals(Topic.Distance, topic("How far must I still walk today?"))
        assertEquals(Topic.RestingHeart, topic("what is my bpm"))
        assertEquals(Topic.Steps, topic("How many steps in Spain last week?"))
    }

    @Test fun `topics`() {
        mapOf(
            "How did I sleep this week?" to Topic.Sleep, "Did I get enough deep sleep?" to Topic.DeepSleep, "How close am I to my calorie goal?" to Topic.Calories,
            "Compare my steps with last week" to Topic.Steps, "How far must I still walk today?" to Topic.Distance, "How many floors did I climb" to Topic.Floors,
            "Did I reach my zone minutes goal this week?" to Topic.Zone, "What workouts did I do?" to Topic.Workouts, "Is my resting heart rate normal for me?" to Topic.RestingHeart,
            "How is my HRV?" to Topic.Hrv, "How is my heart rate variability this week?" to Topic.Hrv, "blood oxygen last night" to Topic.Spo2, "Is my breathing rate ok?" to Topic.Breathing,
            "Why is my skin temperature up?" to Topic.SkinTemp, "How fit am I?" to Topic.Fitness, "I feel tired, what does my data say?" to Topic.Overview,
            "Mera sleep kaisa tha is week?" to Topic.Sleep, "kal neend kaisi thi" to Topic.Sleep, "How is my cardio fitness this week?" to Topic.Fitness,
        ).forEach { (q, t) -> assertEquals(q, t, topic(q)) }
        // Every question Pulse itself suggests must be understood.
        (Suggestions.start + Topic.entries.flatMap { Suggestions.after(it) }).forEach { assertNotNull(it, topic(it)) }
    }

    @Test fun `anything else is off topic`() {
        for (q in listOf("Who won the cricket match yesterday?", "Write me a poem", "what is the capital of France", "hi", "???"))
            assertEquals(q, Route.OffTopic, QuestionRouter.route(q, today))
    }

    @Test fun `days and dates`() {
        fun p(q: String) = (QuestionRouter.route(q, today) as Route.Data).period
        assertEquals(today, p("How was my sleep last night?").from)
        assertEquals(today.minusDays(1), p("How many steps yesterday?").to)
        assertEquals(LocalDate.of(2026, 1, 2), p("How did I sleep on 2 January?").from)
        assertEquals(LocalDate.of(2026, 11, 5), p("steps on Nov 5th").from)
        // Not in the future: the last one.
        assertEquals(LocalDate.of(2025, 12, 25), p("steps on 25 December").from)
        assertEquals(LocalDate.of(2026, 11, 20), p("How many steps on Friday?").from)
        assertEquals(today, p("steps on monday").from)
        assertEquals(30L, p("What was my longest night this month?").days)
        assertEquals(7L, p("How did I sleep this week?").days)
        // A year in the question counts. (At first "2 January 2020" got the answer for 2026.)
        assertEquals(LocalDate.of(2020, 1, 2), p("how did I sleep on 2 January 2020").from)
        assertEquals(LocalDate.of(2025, 11, 5), p("steps on Nov 5, 2025").from)
        // A date that doesn't exist doesn't crash.
        assertEquals(Topic.Steps, topic("steps on 31 February"))
    }
}

class AnswerBuilderTest {

    private val week = listOf(465L, 485, 422, 440, 460, 505, 425).mapIndexed { i, m -> night(today.minusDays(6L - i), m) }

    @Test fun `sleep this week`() {
        val a = ask("How did I sleep this week?", data(nights = week))
        assertEquals("You averaged 7h 37m a night over the last 7 days. That is 23m under your goal of 8h 00m. You reached your sleep goal on 2 of 7 nights.", a.direct)
        assertEquals("7h 37m", a.big)
        assertEquals(2, a.bars.count { it.reached })
        assertTrue(a.hasData)
    }

    @Test fun `one night, best night and worst night`() {
        assertEquals("Last night: 7h 05m. That is 55m under your goal of 8h 00m.", ask("How was my sleep last night?", data(nights = week)).direct)
        assertEquals("Your longest night of the last 30 was Sunday 22 November, with 8h 25m.", ask("What was my longest night this month?", data(nights = week)).direct)
        assertTrue(ask("Which was my shortest night?", data(nights = week)).direct.endsWith("with 7h 02m."))
    }

    @Test fun `calories left today`() {
        val d = data(days = listOf(DaySummary(today, caloriesKcal = 2184.0)))
        assertEquals("Today so far: 2,184 kcal. 616 kcal to go. You are at 78% of your goal of 2,800 kcal.", ask("How close am I to my calorie goal?", d).direct)
        val done = data(days = listOf(DaySummary(today, caloriesKcal = 2900.0)))
        assertTrue(ask("calories left today", done).direct.contains("You reached your goal"))
    }

    @Test fun `distance uses the chosen units`() {
        val d = data(days = listOf(DaySummary(today, distanceMeters = 6100.0)))
        assertEquals("Today so far: 6.1 km. 0.9 km to go. You are at 87% of your goal of 7.0 km.", ask("How far must I still walk today?", d).direct)
        assertTrue(ask("How far must I still walk today?", d.copy(units = Units.Imperial)).direct.contains("3.8 mi"))
    }

    @Test fun `an unfinished today is not part of averages`() {
        val d = data(days = (0L..7L).map { DaySummary(today.minusDays(it), steps = if (it == 0L) 100 else 12_000) })
        assertTrue(ask("average steps this week", d).direct.startsWith("You averaged 12,000 steps a day"))
        // ... but today does count for "how many days reached the goal".
        assertTrue(ask("did I reach my step goal this week", d).direct.contains("6 of 7 days"))
    }

    @Test fun `each day is judged by the goal it had`() {
        val changed = today.minusDays(2)
        val goals = DatedGoals.record(emptyList(), Goals(dailySteps = 10_000), Goals(dailySteps = 5_000), changed)
        val d = data(days = (0L..6L).map { DaySummary(today.minusDays(it), steps = 6_000) }, goals = goals)
        assertTrue(ask("How often did I reach my step goal this week?", d).direct.contains("3 of 7 days"))
    }

    @Test fun `resting heart rate against your own normal`() {
        val days = (1L..30L).map { DaySummary(today.minusDays(it), restingHeartRate = 60) }
        assertEquals("Today: 58 bpm. Your own 30-day normal is 60 bpm, so this is below it by 2 bpm.", ask("Is my resting heart rate normal for me?", data(days + DaySummary(today, restingHeartRate = 58))).direct)
        assertTrue(ask("Is my resting heart rate normal?", data(days + DaySummary(today, restingHeartRate = 60))).direct.contains("the same as"))
        assertTrue(ask("resting heart rate", data(listOf(DaySummary(today, restingHeartRate = 58)))).direct.contains("not enough days"))
    }

    @Test fun `compare two weeks`() {
        val d = data(days = (1L..14L).map { DaySummary(today.minusDays(it), steps = if (it <= 7) 11_000 else 9_000) })
        assertEquals("You averaged 11,000 steps a day in the last seven days, and 9,000 steps in the seven before. That is 2,000 steps more.", ask("Compare my steps with last week", d).direct)
        assertTrue(ask("Compare my steps with last week", data(days = listOf(DaySummary(today, steps = 5)))).direct.contains("not enough data"))
    }

    @Test fun `a comparison shows the same number in the heading, the bars and the sentence`() {
        // An odd average, and an unfinished today that must not change it.
        val steps = listOf(400L, 11_201, 11_950, 11_802, 10_907, 11_333, 11_700, 10_720, 8_100, 8_250, 8_301, 8_180, 8_333, 8_299, 8_270)
        val a = ask("Compare my steps with last week", data(days = steps.mapIndexed { i, n -> DaySummary(today.minusDays(i.toLong()), steps = n) }))
        assertEquals("You averaged 11,373 steps a day in the last seven days, and 8,248 steps in the seven before. That is 3,125 steps more.", a.direct)
        assertEquals("11,373", a.big)
        assertEquals("Steps, last 7 full days", a.title)
        assertEquals(10_720.0, a.bars.first().value!!, 0.0)
        assertEquals(11_201.0, a.bars.last().value!!, 0.0)
        assertEquals("11,373", ask("compare my steps today with last week", data(days = steps.mapIndexed { i, n -> DaySummary(today.minusDays(i.toLong()), steps = n) })).big)
    }

    @Test fun `the idea fits the result`() {
        val month = (1L..30L).map { DaySummary(today.minusDays(it), restingHeartRate = 60, steps = 12_000) }
        fun rhr(v: Int) = ask("Is my resting heart rate normal?", data(month + DaySummary(today, restingHeartRate = v))).ideas
        assertEquals(Ideas.keep(Topic.RestingHeart), rhr(58))
        assertEquals(Ideas.forTopic(Topic.RestingHeart), rhr(66))
        assertEquals(Ideas.keep(Topic.Steps), ask("average steps this week", data(month)).ideas)
        assertEquals(Ideas.forTopic(Topic.Steps), ask("average steps this week", data((0L..6L).map { DaySummary(today.minusDays(it), steps = 4_000) })).ideas)
        assertEquals(Ideas.forTopic(Topic.Sleep), ask("How did I sleep this week?", data(nights = week)).ideas)
    }

    @Test fun `no data is said plainly and never guessed`() {
        val a = ask("How did I sleep on 2 January?", data(nights = week))
        assertFalse(a.hasData)
        assertEquals("Pulse has no sleep data saved for Fri 2 Jan, so I won't guess.", a.direct)
        assertTrue(a.ideas.isEmpty())
        assertFalse(ask("what workouts did I do this week", data()).hasData)
        assertFalse(ask("How is my week?", data()).hasData)
    }

    @Test fun `every topic answers without crashing, with and without data`() {
        val full = data(
            days = (0L..40L).map { DaySummary(today.minusDays(it), 9_000, 6_000.0, 8, 2_500.0, 20, 30, 59, 40.0, 96.0, 14.0, 0.2, 44.0) },
            nights = (0L..40L).map { night(today.minusDays(it), 450) },
            workouts = listOf(Workout("w", "RUNNING", "Run", today.atTime(8, 0).toInstant(ZoneOffset.UTC), today.atTime(8, 31).toInstant(ZoneOffset.UTC))),
        )
        for (t in Topic.entries) for (w in Want.entries) for (n in listOf(0L, 6L, 29L)) {
            val r = Route.Data(t, Period(if (n == 0L) Period.Kind.Day else Period.Kind.Week, today.minusDays(n), today), w)
            for (d in listOf(full, data())) {
                val a = AnswerBuilder.build(r, d)
                assertTrue("$t $w", a.direct.isNotBlank() && "null" !in a.direct && "NaN" !in a.direct)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()

    @Before fun up() = Dispatchers.setMain(dispatcher)
    @After fun down() = Dispatchers.resetMain()

    private val sleepDays = (0L..6L).map { night(today.minusDays(it), 450) }
    private var prefs = ChatPrefs()
    private var gate: CompletableDeferred<Unit>? = null
    private var broken = false
    private val file get() = File(tmp.root, "chats.json")

    private fun vm() = ChatViewModel(
        { gate?.await(); if (broken) error("database"); data(nights = sleepDays, days = listOf(DaySummary(today, steps = 4000))) },
        { prefs }, ChatStore(file), { today },
    )

    private fun ChatViewModel.reply() = state.value.items.filterIsInstance<ChatItem.Reply>().last()

    @Test fun `answers with the numbers`() = runTest(dispatcher) {
        val v = vm()
        v.open(); v.ask("How did I sleep this week?"); advanceTimeBy(1_000); runCurrent()
        assertTrue(v.reply().answer.direct.startsWith("You averaged 7h 30m"))
        assertFalse(v.state.value.busy)
    }

    @Test fun `emergencies, illness and other things get fixed messages`() = runTest(dispatcher) {
        val v = vm()
        for (q in listOf("I have chest pain", "Do I have sleep apnea?", "Who won the match?")) { v.ask(q); advanceTimeBy(1_000); runCurrent() }
        val kinds = v.state.value.items.filterIsInstance<ChatItem.Notice>().map { it.kind }
        assertEquals(listOf(ChatItem.Notice.Kind.Emergency, ChatItem.Notice.Kind.Plain, ChatItem.Notice.Kind.Plain), kinds)
        assertTrue(v.state.value.items.none { it is ChatItem.Reply })
    }

    @Test fun `a database problem is said and the chat goes on`() = runTest(dispatcher) {
        val v = vm()
        broken = true; v.ask("steps today"); advanceTimeBy(1_000); runCurrent()
        assertEquals("That didn't work", (v.state.value.items.last() as ChatItem.Notice).title)
        broken = false; v.ask("steps today"); advanceTimeBy(1_000); runCurrent()
        assertEquals(Topic.Steps, v.reply().answer.topic)
    }

    @Test fun `areas that are switched off are not read`() = runTest(dispatcher) {
        prefs = ChatPrefs(areas = setOf(Area.Activity))
        val v = vm()
        v.ask("How did I sleep this week?"); advanceTimeBy(1_000); runCurrent()
        assertTrue((v.state.value.items.last() as ChatItem.Notice).text.contains("Sleep"))
        // The weekly overview leaves sleep out too.
        v.ask("How is my week?"); advanceTimeBy(1_000); runCurrent()
        assertFalse(v.state.value.items.filterIsInstance<ChatItem.Reply>().any { "Sleep" in it.answer.direct })
    }

    @Test fun `old chats load, also ones saved with an extra field`() = runBlocking {
        val store = ChatStore(file)
        assertTrue(store.load().isEmpty())
        store.save(List(60) { SavedTurn("q$it", "a") })
        assertEquals(40, store.load().size); assertEquals("q59", store.load().last().question)
        file.writeText("""[{"question":"q","answer":"a","ai":"x"}]"""); assertEquals(SavedTurn("q", "a"), store.load().single())
        file.writeText("not json"); assertTrue(store.load().isEmpty())
        store.clear(); assertFalse(file.exists())
    }

    @Test fun `chats are kept only when switched on, and emergencies never`() = runBlocking {
        // Saving runs on a real background thread, so wait for the file.
        prefs = ChatPrefs(keep = true)
        Dispatchers.setMain(Dispatchers.Unconfined)
        val v = vm(); v.ask("steps today"); v.ask("I have chest pain")
        repeat(100) { if (!file.exists()) delay(20) }
        delay(100)
        assertEquals(listOf("steps today"), ChatStore(file).load().map { it.question })
    }

    @Test fun `nothing is written when keeping is off`() = runTest(dispatcher) {
        val v = vm(); v.ask("How did I sleep this week?"); advanceTimeBy(1_000); runCurrent()
        assertFalse(file.exists())
    }

    @Test fun `one question at a time, and new chat drops an unfinished one`() = runTest(dispatcher) {
        gate = CompletableDeferred()
        val v = vm()
        v.ask("steps today"); runCurrent(); v.ask("How did I sleep this week?"); runCurrent()
        assertEquals(1, v.state.value.items.count { it is ChatItem.Question })
        v.newChat(); gate!!.complete(Unit); advanceTimeBy(1_000); runCurrent()
        assertTrue(v.state.value.items.isEmpty()); assertFalse(v.state.value.busy)
    }
}
