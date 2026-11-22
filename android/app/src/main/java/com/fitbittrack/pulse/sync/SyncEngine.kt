package com.fitbittrack.pulse.sync

import com.fitbittrack.pulse.data.HealthApiException
import com.fitbittrack.pulse.data.local.DailyMetricEntity
import com.fitbittrack.pulse.data.local.FetchedDayEntity
import com.fitbittrack.pulse.data.local.HealthDao
import com.fitbittrack.pulse.data.local.HeartRateEntity
import com.fitbittrack.pulse.data.local.HourlyStepsEntity
import com.fitbittrack.pulse.data.local.SettingsStore
import com.fitbittrack.pulse.data.local.toEntity
import com.fitbittrack.pulse.data.remote.DAILY_LISTS
import com.fitbittrack.pulse.data.remote.DAILY_ROLLUPS
import com.fitbittrack.pulse.data.remote.DailyValue
import com.fitbittrack.pulse.data.remote.HealthClient
import com.fitbittrack.pulse.data.remote.Metric
import com.fitbittrack.pulse.data.remote.NeedsSignInException
import com.fitbittrack.pulse.data.remote.Parsers
import com.fitbittrack.pulse.data.remote.RollupSpec
import com.fitbittrack.pulse.data.remote.num
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Downloads data from Google into the local database.
 *
 * - Recent sync (every run): the last 7 days of daily totals, the last 14 days of daily
 *   health metrics, recent sleep and workouts, and today's/yesterday's heart rate and hourly steps.
 * - History backfill (first run only): walks back 90 days at a time until two empty windows
 *   in a row (or 10 years), then fills every metric for that whole range.
 *
 * Every write is an upsert keyed by date or Google's data point name: no duplicates.
 */
class SyncEngine(
    private val client: HealthClient,
    private val dao: HealthDao,
    private val store: SettingsStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: Clock = Clock.systemUTC(),
    /** Pause between calls during backfill, to stay well under 300 requests/minute. */
    private val pace: suspend () -> Unit = { delay(250) },
    /** Called after every successful sync (for example to refresh home-screen widgets). */
    private val onSuccess: suspend () -> Unit = {},
) {
    enum class Outcome { OK, NEEDS_SIGN_IN, RETRY_LATER, FAILED }

    private val syncLock = Mutex()
    private val detailLock = Mutex()

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    suspend fun sync(includeBackfill: Boolean): Outcome = syncLock.withLock {
        try {
            store.setRunning("Getting your latest data…")
            syncRecent()
            if (includeBackfill && !store.isBackfillDone()) backfill()
            store.setSucceeded(clock.instant())
            runCatching { onSuccess() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            Outcome.OK
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.setIdle() }
            throw e
        } catch (e: NeedsSignInException) {
            store.setFailed(e.message ?: "Please sign in again.", needsSignIn = true)
            Outcome.NEEDS_SIGN_IN
        } catch (e: HealthApiException) {
            val needsSignIn = e.isUnauthorized
            store.setFailed(e.message ?: "Sync failed.", needsSignIn)
            when {
                needsSignIn -> Outcome.NEEDS_SIGN_IN
                e.isTransient -> Outcome.RETRY_LATER
                else -> Outcome.FAILED
            }
        }
    }

    // ---------------- recent ----------------

    private suspend fun syncRecent() {
        val today = today()
        for (spec in DAILY_ROLLUPS) {
            saveDaily(fetchRollup(spec, today.minusDays(6), today.plusDays(1)))
        }
        store.setRunning("Getting heart, sleep and recovery data…")
        val since = today.minusDays(13)
        for (spec in DAILY_LISTS) {
            val points = client.list(spec.dataType, filter = "${spec.filterName}.date >= \"$since\"", pageSize = 100)
            saveDaily(Parsers.dailyList(spec, points))
        }
        saveSleep(since = today.minusDays(6))
        saveWorkouts(since = today.minusDays(13))
        fetchHeartRate(today); fetchHeartRate(today.minusDays(1))
        fetchHourlySteps(today); fetchHourlySteps(today.minusDays(1))
        updateTrackerSync()
    }

    private suspend fun updateTrackerSync() {
        try {
            Parsers.lastTrackerSync(client.pairedDevices())?.let { store.setLastTrackerSync(it) }
        } catch (e: HealthApiException) {
            // Optional: the paired-devices call may need a permission the user did not grant.
            if (e.isTransient) throw e
        }
    }

    // ---------------- history ----------------

    private suspend fun backfill() {
        val recentStart = today().minusDays(6)
        store.setRunning("Finding how far back your history goes…")

        // 1) Discover the start of history using steps, 90 days at a time.
        val stepsSpec = DAILY_ROLLUPS.first { it.metric == Metric.STEPS }
        var windowEnd = recentStart
        var earliest = recentStart
        var emptyInARow = 0
        val oldestAllowed = recentStart.minusYears(10)
        while (emptyInARow < 2 && windowEnd > oldestAllowed) {
            val windowStart = windowEnd.minusDays(stepsSpec.maxDays.toLong())
            val values = fetchRollup(stepsSpec, windowStart, windowEnd).filter { it.value > 0 }
            saveDaily(values)
            if (values.isEmpty()) emptyInARow++ else {
                emptyInARow = 0
                earliest = values.minOf { it.date }
            }
            store.setRunning("Found history back to ${monthLabel(earliest)}…")
            windowEnd = windowStart
            pace()
        }

        // 2) Every other daily total over [earliest, recentStart).
        val others = DAILY_ROLLUPS.filter { it.metric != Metric.STEPS }
        val chunks = others.flatMap { spec -> chunk(earliest, recentStart, spec.maxDays).map { spec to it } }
        val listSteps = DAILY_LISTS.size + 2 // + sleep + workouts
        val total = (chunks.size + listSteps).coerceAtLeast(1)
        var done = 0
        for ((spec, range) in chunks) {
            saveDaily(fetchRollup(spec, range.first, range.second))
            done++
            store.setRunning("Downloading history (${monthLabel(range.first)})…", done.toFloat() / total)
            pace()
        }

        // 3) Daily health metrics, sleep and workouts since the start of history.
        for (spec in DAILY_LISTS) {
            val points = client.list(spec.dataType, filter = "${spec.filterName}.date >= \"$earliest\"", pageSize = 1000) { pace() }
            saveDaily(Parsers.dailyList(spec, points))
            done++
            store.setRunning("Downloading heart and recovery history…", done.toFloat() / total)
        }
        store.setRunning("Downloading sleep history…", done.toFloat() / total)
        saveSleep(since = earliest, slow = true); done++
        store.setRunning("Downloading workout history…", done.toFloat() / total)
        saveWorkouts(since = earliest, slow = true)

        store.setBackfillDone(earliest)
    }

    /** Splits [from, toExclusive) into ranges of at most [maxDays]. */
    internal fun chunk(from: LocalDate, toExclusive: LocalDate, maxDays: Int): List<Pair<LocalDate, LocalDate>> {
        val out = mutableListOf<Pair<LocalDate, LocalDate>>()
        var start = from
        while (start < toExclusive) {
            val end = minOf(start.plusDays(maxDays.toLong()), toExclusive)
            out += start to end
            start = end
        }
        return out
    }

    // ---------------- shared helpers ----------------

    private suspend fun fetchRollup(spec: RollupSpec, from: LocalDate, toExclusive: LocalDate): List<DailyValue> =
        chunk(from, toExclusive, spec.maxDays).flatMap { (a, b) ->
            Parsers.dailyRollup(spec, client.dailyRollUp(spec.dataType, a, b, spec.valueKey))
        }

    private suspend fun saveDaily(values: List<DailyValue>) {
        if (values.isNotEmpty()) dao.upsertDaily(values.map { DailyMetricEntity(it.date.toString(), it.metric, it.value) })
    }

    private suspend fun saveSleep(since: LocalDate, slow: Boolean = false) {
        val points = client.list("sleep", filter = "sleep.interval.civil_end_time >= \"$since\"", pageSize = 25) {
            if (slow) pace()
        }
        val z = zone()
        dao.upsertSleep(points.mapNotNull { Parsers.sleep(it, z) }.map { it.session.toEntity(it.wakeDate) })
    }

    private suspend fun saveWorkouts(since: LocalDate, slow: Boolean = false) {
        val points = client.list("exercise", filter = "exercise.interval.civil_start_time >= \"${since}T00:00:00\"", pageSize = 25) {
            if (slow) pace()
        }
        val z = zone()
        dao.upsertWorkouts(points.mapNotNull { Parsers.exercise(it, z) }.map { it.workout.toEntity(it.startDate) })
    }

    /** Heart rate for one local day as 5-minute averages (rollUp, field beatsPerMinuteAvg). */
    suspend fun fetchHeartRate(date: LocalDate) {
        val z = zone()
        val start = date.atStartOfDay(z).toInstant()
        val end = date.plusDays(1).atStartOfDay(z).toInstant()
        val rows = client.rollUp("heart-rate", start, end, 300, "heartRate").mapNotNull { p ->
            val avg = p.value.num("beatsPerMinuteAvg") ?: return@mapNotNull null
            val t = p.start ?: return@mapNotNull null
            HeartRateEntity(t.epochSecond, date.toString(), avg.roundToInt())
        }
        dao.deleteHeartRate(date.toString())
        dao.upsertHeartRate(rows)
        dao.upsertFetched(FetchedDayEntity(date.toString(), KIND_HR, clock.millis()))
    }

    /** Steps per local hour (rollUp with 3600s windows, field countSum). */
    suspend fun fetchHourlySteps(date: LocalDate) {
        val z = zone()
        val start = date.atStartOfDay(z).toInstant()
        val end = date.plusDays(1).atStartOfDay(z).toInstant()
        val byHour = HashMap<Int, Long>()
        client.rollUp("steps", start, end, 3600, "steps").forEach { p ->
            val t = p.start ?: return@forEach
            val steps = p.value.num("countSum")?.toLong() ?: return@forEach
            val hour = t.atZone(z).hour
            byHour[hour] = (byHour[hour] ?: 0L) + steps
        }
        dao.upsertHourly(byHour.map { (h, s) -> HourlyStepsEntity(date.toString(), h, s) })
        dao.upsertFetched(FetchedDayEntity(date.toString(), KIND_HOURLY, clock.millis()))
    }

    /**
     * Fetches heart rate or hourly steps for a day the screens asked about, unless we already have
     * a complete copy. Errors are ignored here; the next full sync reports them.
     */
    suspend fun ensureDetail(date: LocalDate, kind: String) = detailLock.withLock {
        if (date > today()) return@withLock
        val fetched = dao.fetched(date.toString(), kind)
        if (!needsFetch(date, fetched?.fetchedAtMs)) return@withLock
        try {
            if (kind == KIND_HR) fetchHeartRate(date) else fetchHourlySteps(date)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Today: refresh every 15 minutes. Past days: once fetched more than a day after they ended. */
    internal fun needsFetch(date: LocalDate, fetchedAtMs: Long?): Boolean {
        if (fetchedAtMs == null) return true
        val fetchedAt = Instant.ofEpochMilli(fetchedAtMs)
        return if (date == today()) fetchedAt.plusSeconds(15 * 60) < clock.instant()
        else fetchedAt < date.plusDays(2).atStartOfDay(zone()).toInstant()
    }

    private fun monthLabel(d: LocalDate): String = d.format(MONTH)

    companion object {
        const val KIND_HR = "hr"
        const val KIND_HOURLY = "hourly"
        private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    }
}
