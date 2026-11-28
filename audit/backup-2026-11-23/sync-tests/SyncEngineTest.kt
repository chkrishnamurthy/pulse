package com.fitbittrack.pulse.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.fitbittrack.pulse.data.HealthApi
import com.fitbittrack.pulse.data.local.DailyMetricEntity
import com.fitbittrack.pulse.data.local.FetchedDayEntity
import com.fitbittrack.pulse.data.local.HealthDao
import com.fitbittrack.pulse.data.local.HeartRateEntity
import com.fitbittrack.pulse.data.local.HourlyStepsEntity
import com.fitbittrack.pulse.data.local.SettingsStore
import com.fitbittrack.pulse.data.local.SleepEntity
import com.fitbittrack.pulse.data.local.WorkoutEntity
import com.fitbittrack.pulse.data.remote.HealthClient
import com.fitbittrack.pulse.data.remote.NeedsSignInException
import com.fitbittrack.pulse.data.remote.TokenProvider
import com.fitbittrack.pulse.domain.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory stand-in for the Room DAO. */
class FakeDao : HealthDao() {
    val daily = ConcurrentHashMap<Pair<String, String>, Double>()
    val hr = ConcurrentHashMap<Long, HeartRateEntity>()
    val hourly = ConcurrentHashMap<Pair<String, Int>, Long>()
    val sleepRows = ConcurrentHashMap<String, SleepEntity>()
    val workoutRows = ConcurrentHashMap<String, WorkoutEntity>()
    val fetchedRows = ConcurrentHashMap<Pair<String, String>, FetchedDayEntity>()
    /** Hook run inside each daily write (lets a test act while a sync is mid-way). */
    var onDailyWrite: suspend () -> Unit = {}

    fun clear() { daily.clear(); hr.clear(); hourly.clear(); sleepRows.clear(); workoutRows.clear(); fetchedRows.clear() }

    override suspend fun upsertDaily(rows: List<DailyMetricEntity>) { onDailyWrite(); rows.forEach { daily[it.date to it.metric] = it.value } }
    override suspend fun upsertHeartRate(rows: List<HeartRateEntity>) { rows.forEach { hr[it.epochSecond] = it } }
    override suspend fun upsertHourly(rows: List<HourlyStepsEntity>) { rows.forEach { hourly[it.date to it.hour] = it.steps } }
    override suspend fun upsertSleep(rows: List<SleepEntity>) { rows.forEach { sleepRows[it.id] = it } }
    override suspend fun upsertWorkouts(rows: List<WorkoutEntity>) { rows.forEach { workoutRows[it.id] = it } }
    override suspend fun upsertFetched(row: FetchedDayEntity) { fetchedRows[row.date to row.kind] = row }
    override fun daily(from: String, to: String): Flow<List<DailyMetricEntity>> = flowOf(emptyList())
    override fun heartRate(date: String): Flow<List<HeartRateEntity>> = flowOf(emptyList())
    override suspend fun deleteHeartRate(date: String) { hr.values.removeIf { it.date == date } }
    override fun hourly(date: String): Flow<List<HourlyStepsEntity>> = flowOf(emptyList())
    override suspend fun deleteHourly(date: String) { hourly.keys.removeIf { it.first == date } }
    override fun sleep(from: String, to: String): Flow<List<SleepEntity>> = flowOf(emptyList())
    override suspend fun deleteSleepFrom(from: String) { sleepRows.values.removeIf { it.wakeDate >= from } }
    override fun workouts(from: String, to: String): Flow<List<WorkoutEntity>> = flowOf(emptyList())
    override suspend fun deleteWorkoutsFrom(from: String) { workoutRows.values.removeIf { it.startDate >= from } }
    override suspend fun fetched(date: String, kind: String): FetchedDayEntity? = fetchedRows[date to kind]
}

class MemoryPrefs : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

class SyncEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeDao
    private lateinit var store: SettingsStore
    private lateinit var storeScope: CoroutineScope
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Test rules: map "data type" → response. Default answers are empty but valid. */
    private val overrides = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-22T06:30:00Z") // 12:00 in Kolkata

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /** Type name from a path like /v4/users/me/dataTypes/steps/dataPoints:dailyRollUp. */
    private fun typeOf(path: String): String =
        Regex("dataTypes/([a-z0-9-]+)/").find(path)?.groupValues?.get(1) ?: path

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path ?: ""
                val type = typeOf(path)
                overrides[type]?.let { return it(request) }
                overrides["*"]?.let { return it(request) }
                return when {
                    path.contains("pairedDevices") -> ok("""{"pairedDevices":[{"deviceType":"TRACKER","lastSyncTime":"2026-11-22T06:00:00Z"}]}""")
                    path.contains(":dailyRollUp") && type == "steps" -> ok(stepsDaily(request))
                    path.contains(":dailyRollUp") -> ok("""{"rollupDataPoints":[]}""")
                    path.contains(":rollUp") -> ok("""{"rollupDataPoints":[]}""")
                    else -> ok("""{"dataPoints":[]}""")
                }
            }
        }
        server.start()
        dao = FakeDao()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // In-memory DataStore: the file-based one cannot replace files on Windows test machines.
        store = SettingsStore(MemoryPrefs())
    }

    @After fun tearDown() {
        server.shutdown()
        storeScope.cancel()
    }

    /** Steps only exist from [historyStart] on: 5000 per day. */
    private var historyStart: LocalDate = LocalDate.of(2026, 11, 1)

    private fun stepsDaily(request: RecordedRequest): String {
        val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
        fun d(key: String): LocalDate {
            val date = body["range"]!!.jsonObject[key]!!.jsonObject["date"]!!.jsonObject
            return LocalDate.of(
                date["year"]!!.jsonPrimitive.content.toInt(),
                date["month"]!!.jsonPrimitive.content.toInt(),
                date["day"]!!.jsonPrimitive.content.toInt(),
            )
        }
        val points = generateSequence(d("start")) { it.plusDays(1) }.takeWhile { it < d("end") }
            .filter { it >= historyStart }
            .joinToString(",") { """{"civilStartTime":{"date":{"year":${it.year},"month":${it.monthValue},"day":${it.dayOfMonth}}},"steps":{"countSum":"5000"}}""" }
        return """{"rollupDataPoints":[$points]}"""
    }

    private fun engine(
        tokens: TokenProvider = object : TokenProvider {
            override suspend fun accessToken() = "t"
            override fun invalidate() {}
        },
        z: ZoneId = zone,
        at: Instant = now,
    ) = SyncEngine(
        client = HealthClient(HealthApi(baseUrl = server.url("/").toString(), sleeper = {}), tokens),
        dao = dao,
        store = store,
        zone = { z },
        clock = Clock.fixed(at, z),
        pace = {},
    )

    @Test fun recentSyncStoresDataAndSucceeds() = runBlocking {
        val e = engine()
        assertEquals(SyncEngine.Outcome.OK, e.sync(includeBackfill = false))
        assertEquals(5000.0, dao.daily["2026-11-22" to "steps"])
        val status = store.syncStatus.first()
        assertEquals(SyncState.Idle, status.state)
        assertEquals(now, status.lastSuccess)
        assertEquals(Instant.parse("2026-11-22T06:00:00Z"), status.lastTrackerSync)
    }

    @Test fun tokenRefusedNeedsSignIn() = runBlocking {
        val e = engine(tokens = object : TokenProvider {
            override suspend fun accessToken(): String = throw NeedsSignInException("Sign in again.")
            override fun invalidate() {}
        })
        assertEquals(SyncEngine.Outcome.NEEDS_SIGN_IN, e.sync(false))
        val s = store.syncStatus.first().state as SyncState.Failed
        assertTrue(s.needsSignIn)
        assertTrue(dao.daily.isEmpty())
    }

    @Test fun accountNotLinkedFailsWithPlainMessage() = runBlocking {
        overrides["*"] = { MockResponse().setResponseCode(400).setBody("""{"error":{"status":"FAILED_PRECONDITION","details":[{"reason":"ACCOUNT_NOT_LINKED"}]}}""") }
        val e = engine()
        assertEquals(SyncEngine.Outcome.FAILED, e.sync(false))
        val s = store.syncStatus.first().state as SyncState.Failed
        assertTrue("not linked" in s.message)
        assertFalse(s.needsSignIn)
    }

    @Test fun partialConsentSkipsOnlyThatType() = runBlocking {
        overrides["sleep"] = { MockResponse().setResponseCode(403).setBody("""{"error":{"code":403}}""") }
        val e = engine()
        assertEquals(SyncEngine.Outcome.OK, e.sync(false))
        assertEquals(5000.0, dao.daily["2026-11-22" to "steps"])
        assertEquals(listOf("sleep"), e.lastSkipped)
    }

    @Test fun everyTypeRefusedFails() = runBlocking {
        overrides["*"] = { MockResponse().setResponseCode(403).setBody("{}") }
        assertEquals(SyncEngine.Outcome.FAILED, engine().sync(false))
        assertTrue(store.syncStatus.first().state is SyncState.Failed)
    }

    @Test fun unexpectedErrorNeverLeavesRunning() = runBlocking {
        overrides["steps"] = { ok("this is not json") }
        assertEquals(SyncEngine.Outcome.FAILED, engine().sync(false))
        assertTrue(store.syncStatus.first().state is SyncState.Failed)
    }

    @Test fun rejectedDailyFilterFallsBackToFullList() = runBlocking {
        overrides["daily-resting-heart-rate"] = { r ->
            if (r.requestUrl!!.queryParameter("filter") != null) MockResponse().setResponseCode(400).setBody("{}")
            else ok("""{"dataPoints":[{"dailyRestingHeartRate":{"date":{"year":2026,"month":11,"day":21},"beatsPerMinute":"57"}}]}""")
        }
        assertEquals(SyncEngine.Outcome.OK, engine().sync(false))
        assertEquals(57.0, dao.daily["2026-11-21" to "resting_hr"])
    }

    @Test fun backfillFindsStartOfHistory() = runBlocking {
        historyStart = LocalDate.of(2026, 3, 10)
        assertEquals(SyncEngine.Outcome.OK, engine().sync(includeBackfill = true))
        assertTrue(store.isBackfillDone())
        assertEquals(5000.0, dao.daily["2026-03-10" to "steps"])
        assertEquals(null, dao.daily["2026-03-09" to "steps"])
        // A second sync does not repeat the history download.
        requests.clear()
        engine().sync(includeBackfill = true)
        assertTrue(requests.size < 40)
    }

    @Test fun syncTwiceMakesNoDuplicates() = runBlocking {
        overrides["sleep"] = { ok(sleepJson("s1", "2026-11-21T17:00:00Z", "2026-11-22T00:30:00Z")) }
        engine().sync(false); engine().sync(false)
        assertEquals(1, dao.sleepRows.size)
        // Session deleted at Google: the next sync removes it from the window it fetched.
        overrides["sleep"] = { ok("""{"dataPoints":[]}""") }
        engine().sync(false)
        assertEquals(0, dao.sleepRows.size)
    }

    private fun sleepJson(id: String, start: String, end: String) =
        """{"dataPoints":[{"name":"$id","sleep":{"interval":{"startTime":"$start","endTime":"$end","endUtcOffset":"19800s"},"summary":{"minutesAsleep":"400"}}}]}"""

    @Test fun deleteDuringSyncLeavesNothingBehind() = runBlocking {
        val e = engine()
        val midSync = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.onDailyWrite = {
            if (!midSync.isCompleted) { midSync.complete(Unit); release.await() }
        }
        val running = async(Dispatchers.IO) { e.sync(includeBackfill = true) }
        withTimeout(10_000) { midSync.await() }
        val deleting = async(Dispatchers.IO) { e.exclusive { dao.clear(); store.resetSync() } }
        release.complete(Unit)
        assertEquals(SyncEngine.Outcome.ABORTED, withTimeout(10_000) { running.await() })
        withTimeout(10_000) { deleting.await() }
        assertTrue("rows written after delete: ${dao.daily.keys}", dao.daily.isEmpty())
        assertFalse(store.isBackfillDone())
        assertEquals(SyncState.Idle, store.syncStatus.first().state)
    }

    @Test fun staleRunningIsCleared() = runBlocking {
        store.setRunning("Syncing…")
        store.clearStaleRunning()
        assertEquals(SyncState.Idle, store.syncStatus.first().state)
    }

    @Test fun dayRangeHandlesDstAndZones() {
        // New York, 1 Nov 2026: clocks go back, so the day is 25 hours long.
        val ny = ZoneId.of("America/New_York")
        val (a, b) = engine(z = ny).dayRange(LocalDate.of(2026, 11, 1))
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), a)
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), b)
        // Kolkata: midnight local is 18:30 UTC the day before.
        val (c, _) = engine().dayRange(LocalDate.of(2026, 11, 22))
        assertEquals(Instant.parse("2026-11-21T18:30:00Z"), c)
    }

    @Test fun hourlyStepsOnDstDayAddsRepeatedHour() = runBlocking {
        val ny = ZoneId.of("America/New_York")
        // 05:00Z and 06:00Z are both 01:xx local on 1 Nov 2026.
        overrides["steps"] = { r ->
            if (r.path!!.contains(":rollUp")) ok(
                """{"rollupDataPoints":[
                  {"startTime":"2026-11-01T05:00:00Z","endTime":"2026-11-01T06:00:00Z","steps":{"countSum":"100"}},
                  {"startTime":"2026-11-01T06:00:00Z","endTime":"2026-11-01T07:00:00Z","steps":{"countSum":"50"}},
                  {"startTime":"2026-11-01T13:00:00Z","endTime":"2026-11-01T14:00:00Z","steps":{"countSum":"900"}}]}"""
            ) else ok("""{"rollupDataPoints":[]}""")
        }
        engine(z = ny, at = Instant.parse("2026-11-02T15:00:00Z")).fetchHourlySteps(LocalDate.of(2026, 11, 1))
        assertEquals(150L, dao.hourly["2026-11-01" to 1])
        assertEquals(900L, dao.hourly["2026-11-01" to 8])
        val body = requests.last { it.path!!.contains(":rollUp") }.body.readUtf8()
        assertTrue(body.contains("2026-11-01T04:00:00Z") && body.contains("2026-11-02T05:00:00Z"))
    }

    @Test fun todayUsesLocalDateNotUtc() = runBlocking {
        // 20:00Z on the 21st is already the 22nd in Kolkata.
        engine(at = Instant.parse("2026-11-21T20:00:00Z")).sync(false)
        assertEquals(5000.0, dao.daily["2026-11-22" to "steps"])
    }

    @Test fun chunkSplitsWithoutGapsOrOverlap() {
        val e = engine()
        val parts = e.chunk(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 14)
        assertEquals(listOf(
            LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 1, 15) to LocalDate.of(2026, 1, 29),
            LocalDate.of(2026, 1, 29) to LocalDate.of(2026, 1, 31),
        ), parts)
        assertTrue(e.chunk(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), 14).isEmpty())
    }

    @Test fun detailFetchRules() {
        val e = engine()
        val today = LocalDate.of(2026, 11, 22)
        assertTrue(e.needsFetch(today, null))
        assertFalse(e.needsFetch(today, now.minusSeconds(60).toEpochMilli()))
        assertTrue(e.needsFetch(today, now.minusSeconds(16 * 60).toEpochMilli()))
        // A past day fetched long after it ended is complete.
        assertFalse(e.needsFetch(LocalDate.of(2026, 11, 10), now.toEpochMilli()))
        // A past day fetched on the same day may be incomplete.
        assertTrue(e.needsFetch(LocalDate.of(2026, 11, 21), Instant.parse("2026-11-21T10:00:00Z").toEpochMilli()))
    }
}
