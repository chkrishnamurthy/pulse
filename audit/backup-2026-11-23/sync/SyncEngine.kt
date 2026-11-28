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
import com.fitbittrack.pulse.data.remote.DailyListSpec
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
 * Safety rules:
 * - Every write is an upsert keyed by date or Google's data point name: no duplicates. Sleep and
 *   workouts are replaced for exactly the window fetched, so sessions deleted at Google go away too.
 * - Each data type is fetched on its own. A type the user didn't allow (403) or that Google rejects
 *   (400/404) is skipped; the sync only fails if every type fails, or sign-in is needed.
 * - [exclusive] (used by "delete local data") stops a running sync before clearing anything.
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
    enum class Outcome { OK, NEEDS_SIGN_IN, RETRY_LATER, FAILED, ABORTED }

    /** Thrown inside a sync when [exclusive] asked it to stop. Never leaves this class. */
    private class Aborted : Exception("Sync stopped because local data is being deleted.")

    private val syncLock = Mutex()
    private val detailLock = Mutex()
    @Volatile private var abortRequested = false

    /** Types skipped in the current run, and the first error seen (reported if nothing worked). */
    private var skipped = mutableListOf<String>()
    private var firstSkipError: HealthApiException? = null
    private var typesOk = 0

    /** True while a sync is running (pull-to-refresh uses this to return at once). */
    val isRunning: Boolean get() = syncLock.isLocked

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    private fun checkNotAborted() {
        if (abortRequested) throw Aborted()
    }

    suspend fun sync(includeBackfill: Boolean): Outcome = syncLock.withLock {
        skipped = mutableListOf(); firstSkipError = null; typesOk = 0
        try {
            checkNotAborted()
            store.setRunning("Getting your latest data…")
            syncRecent()
            if (typesOk == 0 && firstSkipError != null) throw firstSkipError!!
            if (includeBackfill && !store.isBackfillDone()) backfill()
            checkNotAborted()
            store.setSucceeded(clock.instant())
            try {
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A widget failing to redraw is not a sync failure.
            }
            Outcome.OK
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.setIdle() }
            throw e
        } catch (_: Aborted) {
            withContext(NonCancellable) { store.setIdle() }
            Outcome.ABORTED
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
        } catch (e: Exception) {
            // Anything unexpected (bad JSON, database error): never leave the status stuck on "running".
            runCatching { android.util.Log.e("PulseSync", "Sync failed", e) } // no-op in plain unit tests
            runCatching { store.setFailed("Sync failed: ${e.javaClass.simpleName}. It will try again later.", needsSignIn = false) }
            Outcome.FAILED
        }
    }

    /**
     * Stops any running sync, waits for it to finish, then runs [block] with nothing else writing.
     * Used by "delete local data" and sign-out.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        abortRequested = true
        try {
            return syncLock.withLock { detailLock.withLock { block() } }
        } finally {
            abortRequested = false
        }
    }

    /**
     * Runs one data type's fetch. Skips the type on 403 (permission not granted), 404, or 400 that
     * isn't ACCOUNT_NOT_LINKED. Sign-in problems, 401, network trouble and 429/5xx still stop the sync.
     */
    private suspend fun optional(what: String, block: suspend () -> Unit) {
        checkNotAborted()
        try {
            block()
            typesOk++
        } catch (e: HealthApiException) {
            val skippable = e.httpCode == 403 || e.httpCode == 404 || (e.httpCode == 400 && !e.isAccountNotLinked)
            if (!skippable) throw e
            skipped += what
            if (firstSkipError == null) firstSkipError = e
        }
    }

    // ---------------- recent ----------------

    private suspend fun syncRecent() {
        val today = today()
        for (spec in DAILY_ROLLUPS) {
            optional(spec.dataType) { saveDaily(fetchRollup(spec, today.minusDays(6), today.plusDays(1))) }
        }
        store.setRunning("Getting heart, sleep and recovery data…")
        val since = today.minusDays(13)
        for (spec in DAILY_LISTS) {
            optional(spec.dataType) { saveDaily(Parsers.dailyList(spec, listDaily(spec, since, pageSize = 100))) }
        }
        optional("sleep") { saveSleep(since = today.minusDays(6)) }
        optional("exercise") { saveWorkouts(since = today.minusDays(13)) }
        optional("heart-rate") { fetchHeartRate(today); fetchHeartRate(today.minusDays(1)) }
        optional("steps-hourly") { fetchHourlySteps(today); fetchHourlySteps(today.minusDays(1)) }
        updateTrackerSync()
    }

    /** Daily list with a date filter; if Google rejects the filter (400), fetch the whole list instead. */
    private suspend fun listDaily(spec: DailyListSpec, since: LocalDate, pageSize: Int, onPage: suspend () -> Unit = {}) =
        try {
            client.list(spec.dataType, filter = "${spec.filterName}.date >= \"$since\"", pageSize = pageSize, onPage = onPage)
        } catch (e: HealthApiException) {
            if (e.httpCode != 400 || e.isAccountNotLinked) throw e
            client.list(spec.dataType, pageSize = 1000, onPage = onPage)
        }

    private suspend fun updateTrackerSync() {
        try {
            Parsers.lastTrackerSync(client.pairedDevices())?.let { checkNotAborted(); store.setLastTrackerSync(it) }
        } catch (e: HealthApiException) {
            // Optional: the paired-devices call may need a permission the user did not grant.
            if (e.isTransient || e.isUnauthorized) throw e
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
        val total = (chunks.size + DAILY_LISTS.size + 2).coerceAtLeast(1)
        var done = 0
        for ((spec, range) in chunks) {
            optional(spec.dataType) { saveDaily(fetchRollup(spec, range.first, range.second)) }
            done++
            store.setRunning("Downloading history (${monthLabel(range.first)})…", done.toFloat() / total)
            pace()
        }

        // 3) Daily health metrics, sleep and workouts since the start of history.
        for (spec in DAILY_LISTS) {
            optional(spec.dataType) {
                saveDaily(Parsers.dailyList(spec, listDaily(spec, earliest, pageSize = 1000) { pace() }))
            }
            done++
            store.setRunning("Downloading heart and recovery history…", done.toFloat() / total)
        }
        store.setRunning("Downloading sleep history…", done.toFloat() / total)
        optional("sleep") { saveSleep(since = earliest, slow = true) }
        done++
        store.setRunning("Downloading workout history…", done.toFloat() / total)
        optional("exercise") { saveWorkouts(since = earliest, slow = true) }

        checkNotAborted()
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
        checkNotAborted()
        if (values.isNotEmpty()) dao.upsertDaily(values.map { DailyMetricEntity(it.date.toString(), it.metric, it.value) })
    }

    private suspend fun saveSleep(since: LocalDate, slow: Boolean = false) {
        val points = client.list("sleep", filter = "sleep.interval.civil_end_time >= \"$since\"", pageSize = 25) {
            if (slow) pace()
        }
        val z = zone()
        val rows = points.mapNotNull { Parsers.sleep(it, z) }
            .filter { it.wakeDate >= since }
            .map { it.session.toEntity(it.wakeDate) }
        checkNotAborted()
        dao.replaceSleepFrom(since.toString(), rows)
    }

    private suspend fun saveWorkouts(since: LocalDate, slow: Boolean = false) {
        val points = client.list("exercise", filter = "exercise.interval.civil_start_time >= \"${since}T00:00:00\"", pageSize = 25) {
            if (slow) pace()
        }
        val z = zone()
        val rows = points.mapNotNull { Parsers.exercise(it, z) }
            .filter { it.startDate >= since }
            .map { it.workout.toEntity(it.startDate) }
        checkNotAborted()
        dao.replaceWorkoutsFrom(since.toString(), rows)
    }

    /** Local day [date] as a physical time range. Handles 23- and 25-hour days (DST). */
    internal fun dayRange(date: LocalDate): Pair<Instant, Instant> {
        val z = zone()
        return date.atStartOfDay(z).toInstant() to date.plusDays(1).atStartOfDay(z).toInstant()
    }

    /** Heart rate for one local day as 5-minute averages (rollUp, field beatsPerMinuteAvg). */
    suspend fun fetchHeartRate(date: LocalDate) {
        val (start, end) = dayRange(date)
        val rows = client.rollUp("heart-rate", start, end, 300, "heartRate").mapNotNull { p ->
            val avg = p.value.num("beatsPerMinuteAvg") ?: return@mapNotNull null
            val t = p.start ?: return@mapNotNull null
            HeartRateEntity(t.epochSecond, date.toString(), avg.roundToInt())
        }
        checkNotAborted()
        dao.replaceHeartRate(date.toString(), rows)
        dao.upsertFetched(FetchedDayEntity(date.toString(), KIND_HR, clock.millis()))
    }

    /** Steps per local hour (rollUp with 3600s windows, field countSum). On the 25-hour DST day the repeated hour is added up. */
    suspend fun fetchHourlySteps(date: LocalDate) {
        val (start, end) = dayRange(date)
        val z = zone()
        val byHour = HashMap<Int, Long>()
        client.rollUp("steps", start, end, 3600, "steps").forEach { p ->
            val t = p.start ?: return@forEach
            val steps = p.value.num("countSum")?.toLong() ?: return@forEach
            val hour = t.atZone(z).hour
            byHour[hour] = (byHour[hour] ?: 0L) + steps
        }
        checkNotAborted()
        dao.replaceHourly(date.toString(), byHour.map { (h, s) -> HourlyStepsEntity(date.toString(), h, s) })
        dao.upsertFetched(FetchedDayEntity(date.toString(), KIND_HOURLY, clock.millis()))
    }

    /**
     * Fetches heart rate or hourly steps for a day the screens asked about, unless we already have
     * a complete copy. Errors are ignored here (for example offline); the next full sync reports them.
     */
    suspend fun ensureDetail(date: LocalDate, kind: String) {
        if (abortRequested || date > today()) return
        detailLock.withLock {
            val fetched = dao.fetched(date.toString(), kind)
            if (!needsFetch(date, fetched?.fetchedAtMs)) return@withLock
            try {
                if (kind == KIND_HR) fetchHeartRate(date) else fetchHourlySteps(date)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Today: refresh every 15 minutes. Past days: once fetched more than a day after they ended. */
    internal fun needsFetch(date: LocalDate, fetchedAtMs: Long?): Boolean {
        if (fetchedAtMs == null) return true
        val fetchedAt = Instant.ofEpochMilli(fetchedAtMs)
        return if (date == today()) fetchedAt.plusSeconds(15 * 60) < clock.instant()
        else fetchedAt < date.plusDays(2).atStartOfDay(zone()).toInstant()
    }

    /** Data types skipped in the last run (for diagnostics). */
    val lastSkipped: List<String> get() = skipped.toList()

    /** Reads the locale each time, so a language change applies without restarting. */
    private fun monthLabel(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))

    companion object {
        const val KIND_HR = "hr"
        const val KIND_HOURLY = "hourly"
    }
}
