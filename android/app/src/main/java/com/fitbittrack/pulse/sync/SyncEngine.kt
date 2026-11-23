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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Downloads data from Google into the local database.
 *
 * - Recent sync (every run): the last 7 days of daily totals, the last 14 days of daily
 *   health metrics, recent sleep and workouts, and today's/yesterday's heart rate and hourly steps.
 *   Today's steps and heart rate start first; a few requests run at the same time.
 * - Today refresh ([refreshToday]): only today's totals, heart rate and hourly steps, while the app is open.
 * - History backfill (first run only): walks back 90 days at a time until two empty windows
 *   in a row (or 10 years), then fills every metric for that whole range. It has its own lock and
 *   its own progress, so it never blocks a refresh or delays the first numbers on screen.
 *
 * Safety rules:
 * - Every write is an upsert keyed by date or Google's data point name: no duplicates. Sleep and
 *   workouts are replaced for exactly the window fetched, so sessions deleted at Google go away too.
 * - Each data type is fetched on its own. A type the user didn't allow (403) or that Google rejects
 *   (400/404) is skipped. In a recent sync any other trouble with one type doesn't stop the rest:
 *   the result is [Outcome.PARTIAL] and the problem is shown. Sign-in problems stop everything.
 * - [exclusive] (used by "delete local data") stops every running download before clearing anything.
 */
class SyncEngine(
    private val client: HealthClient,
    private val dao: HealthDao,
    private val store: SettingsStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: Clock = Clock.systemUTC(),
    /** Pause between calls during backfill, to stay well under Google's per-minute limit. */
    private val pace: suspend () -> Unit = { delay(250) },
    /** Called whenever new data was saved (for example to refresh home-screen widgets). */
    private val onSuccess: suspend () -> Unit = {},
) {
    enum class Outcome { OK, PARTIAL, NEEDS_SIGN_IN, RETRY_LATER, FAILED, ABORTED }

    /** Thrown inside a sync when [exclusive] asked it to stop. Never leaves this class. */
    private class Aborted : Exception("Sync stopped because local data is being deleted.")

    /** What happened to each data type in one run. Written from several coroutines at once. */
    private class RunStats {
        val ok = AtomicInteger()
        val skipped = ConcurrentLinkedQueue<String>()
        val failed = ConcurrentLinkedQueue<String>()
        val firstSkip = AtomicReference<Exception?>()
        val firstFailure = AtomicReference<Exception?>()
    }

    /** Each download holds only one of these; [exclusive] takes all three, in this order. */
    private val syncLock = Mutex()
    private val backfillLock = Mutex()
    private val detailLock = Mutex()
    @Volatile private var abortRequested = false
    @Volatile private var fullSyncRunning = false
    @Volatile private var lastStats = RunStats()

    /** True while a full recent sync is running (pull-to-refresh uses this to return at once). */
    val isRunning: Boolean get() = fullSyncRunning

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    private fun checkNotAborted() {
        if (abortRequested) throw Aborted()
    }

    /**
     * Recent data first. Then, if asked and not done yet, the history download.
     * [historyGap]: skip the history download if it was last tried less than this long ago.
     */
    suspend fun sync(includeBackfill: Boolean, historyGap: Duration = Duration.ZERO): Outcome {
        val recent = syncLock.withLock {
            fullSyncRunning = true
            try {
                guarded(onStopped = { store.setIdle() }) { runRecent() }
            } finally {
                fullSyncRunning = false
            }
        }
        if (!includeBackfill || (recent != Outcome.OK && recent != Outcome.PARTIAL)) return recent
        val history = backfillIfNeeded(historyGap)
        return if (history == Outcome.OK) recent else history
    }

    private suspend fun runRecent(): Outcome {
        checkNotAborted()
        val stats = RunStats().also { lastStats = it }
        store.setRunning("Getting your latest data…")
        syncRecent(stats)
        checkNotAborted()
        if (stats.ok.get() == 0) (stats.firstFailure.get() ?: stats.firstSkip.get())?.let { throw it }
        val partial = stats.failed.isNotEmpty()
        if (partial) store.setPartial(clock.instant(), "Some data did not update (${stats.failed.joinToString()}). It will try again soon.")
        else store.setSucceeded(clock.instant())
        notifyChanged()
        return if (partial) Outcome.PARTIAL else Outcome.OK
    }

    private suspend fun backfillIfNeeded(gap: Duration): Outcome {
        if (!backfillLock.tryLock()) return Outcome.OK // already downloading
        try {
            if (store.isBackfillDone()) return Outcome.OK
            val tried = store.historyTriedAt()
            if (tried != null && clock.instant() < tried.plus(gap)) return Outcome.OK
            return guarded(onStopped = {}) {
                checkNotAborted()
                store.setHistoryTried(clock.instant())
                backfill()
                notifyChanged()
                Outcome.OK
            }
        } finally {
            withContext(NonCancellable) { store.clearHistory() }
            backfillLock.unlock()
        }
    }

    /**
     * Quick, silent update of today's totals, heart rate and hourly steps. Does nothing if another sync
     * is running. Problems are left for the next full sync to report, except "sign in again".
     */
    suspend fun refreshToday(): Boolean {
        if (abortRequested || !syncLock.tryLock()) return false
        try {
            checkNotAborted()
            val stats = RunStats()
            val today = today()
            parallel(
                buildList<suspend () -> Unit> {
                    add { optional(stats, "heart-rate") { fetchHeartRate(today) } }
                    add { optional(stats, "steps-hourly") { fetchHourlySteps(today) } }
                    DAILY_ROLLUPS.forEach { spec ->
                        add { optional(stats, spec.dataType) { saveDaily(fetchRollup(spec, today, today.plusDays(1))) } }
                    }
                }
            )
            if (stats.ok.get() > 0) notifyChanged()
            return stats.ok.get() > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsSignInException) {
            store.setFailed(e.message ?: "Please sign in again.", needsSignIn = true)
            return false
        } catch (_: Exception) {
            return false
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun notifyChanged() {
        try {
            onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A widget failing to redraw is not a sync failure.
        }
    }

    /** Turns every way a download can end into an [Outcome] and a saved status, so nothing stays "running". */
    private suspend fun guarded(onStopped: suspend () -> Unit, block: suspend () -> Outcome): Outcome = try {
        block()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { onStopped() }
        throw e
    } catch (_: Aborted) {
        withContext(NonCancellable) { onStopped() }
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

    /**
     * Stops any running download, waits for it to finish, then runs [block] with nothing else writing.
     * Used by "delete local data" and sign-out.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        abortRequested = true
        try {
            return syncLock.withLock { backfillLock.withLock { detailLock.withLock { block() } } }
        } finally {
            abortRequested = false
        }
    }

    /** Starts [tasks] in order, [PARALLEL] at a time. The first one that throws stops the others. */
    private suspend fun parallel(tasks: List<suspend () -> Unit>) = coroutineScope {
        val slots = Semaphore(PARALLEL)
        // UNDISPATCHED: tasks queue for a slot in list order, whatever thread runs them (the semaphore is first-come, first-served).
        tasks.forEach { task -> launch(start = CoroutineStart.UNDISPATCHED) { slots.withPermit { task() } } }
    }

    /**
     * Runs one data type's fetch. Skips the type on 403 (permission not granted), 404, or 400 that
     * isn't ACCOUNT_NOT_LINKED. Sign-in problems and 401 always stop the run. Other trouble (network,
     * 429/5xx, unreadable answer) is noted and the run goes on, unless [stopOnTrouble].
     */
    private suspend fun optional(stats: RunStats, what: String, stopOnTrouble: Boolean = false, block: suspend () -> Unit) {
        checkNotAborted()
        try {
            block()
            stats.ok.incrementAndGet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Aborted) {
            throw e
        } catch (e: NeedsSignInException) {
            throw e
        } catch (e: HealthApiException) {
            when {
                e.isUnauthorized || e.isAccountNotLinked -> throw e
                e.httpCode == 403 || e.httpCode == 404 || e.httpCode == 400 -> {
                    stats.skipped += what
                    stats.firstSkip.compareAndSet(null, e)
                }
                stopOnTrouble -> throw e
                else -> noteFailure(stats, what, e)
            }
        } catch (e: Exception) {
            if (stopOnTrouble) throw e
            noteFailure(stats, what, e)
        }
    }

    private fun noteFailure(stats: RunStats, what: String, e: Exception) {
        stats.failed += what
        stats.firstFailure.compareAndSet(null, e)
    }

    // ---------------- recent ----------------

    private suspend fun syncRecent(stats: RunStats) {
        val today = today()
        val yesterday = today.minusDays(1)
        val since = today.minusDays(13)
        // Order matters: what people look at first is fetched first.
        val (steps, otherTotals) = DAILY_ROLLUPS.partition { it.metric == Metric.STEPS }
        fun totals(spec: RollupSpec): suspend () -> Unit =
            { optional(stats, spec.dataType) { saveDaily(fetchRollup(spec, today.minusDays(6), today.plusDays(1))) } }
        parallel(
            buildList<suspend () -> Unit> {
                steps.forEach { add(totals(it)) }
                add { optional(stats, "heart-rate") { fetchHeartRate(today) } }
                add { optional(stats, "steps-hourly") { fetchHourlySteps(today) } }
                otherTotals.forEach { add(totals(it)) }
                add { optional(stats, "sleep") { saveSleep(since = today.minusDays(6)) } }
                DAILY_LISTS.forEach { spec ->
                    add { optional(stats, spec.dataType) { saveDaily(Parsers.dailyList(spec, listDaily(spec, since, pageSize = 100))) } }
                }
                add { optional(stats, "exercise") { saveWorkouts(since = since) } }
                add { optional(stats, "heart-rate (yesterday)") { fetchHeartRate(yesterday) } }
                add { optional(stats, "steps-hourly (yesterday)") { fetchHourlySteps(yesterday) } }
                add { updateTrackerSync() }
            }
        )
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
            val devices = client.pairedDevices()
            Parsers.lastTrackerSync(devices)?.let { checkNotAborted(); store.setLastTrackerSync(it) }
            store.setBattery(Parsers.battery(devices))
        } catch (e: HealthApiException) {
            // Optional: the paired-devices call may need a permission the user did not grant.
            if (e.isUnauthorized) throw e
        }
    }

    // ---------------- history ----------------

    private suspend fun backfill() {
        val stats = RunStats()
        val recentStart = today().minusDays(6)
        store.setHistory("Finding how far back your history goes…")

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
            store.setHistory("Found history back to ${monthLabel(earliest)}…")
            windowEnd = windowStart
            pace()
        }

        // 2) Every other daily total over [earliest, recentStart).
        val others = DAILY_ROLLUPS.filter { it.metric != Metric.STEPS }
        val chunks = others.flatMap { spec -> chunk(earliest, recentStart, spec.maxDays).map { spec to it } }
        val total = (chunks.size + DAILY_LISTS.size + 2).coerceAtLeast(1)
        var done = 0
        for ((spec, range) in chunks) {
            optional(stats, spec.dataType, stopOnTrouble = true) { saveDaily(fetchRollup(spec, range.first, range.second)) }
            done++
            store.setHistory("Downloading history (${monthLabel(range.first)})…", done.toFloat() / total)
            pace()
        }

        // 3) Daily health metrics, sleep and workouts since the start of history.
        for (spec in DAILY_LISTS) {
            optional(stats, spec.dataType, stopOnTrouble = true) {
                saveDaily(Parsers.dailyList(spec, listDaily(spec, earliest, pageSize = 1000) { pace() }))
            }
            done++
            store.setHistory("Downloading heart and recovery history…", done.toFloat() / total)
        }
        store.setHistory("Downloading sleep history…", done.toFloat() / total)
        optional(stats, "sleep", stopOnTrouble = true) { saveSleep(since = earliest, slow = true) }
        done++
        store.setHistory("Downloading workout history…", done.toFloat() / total)
        optional(stats, "exercise", stopOnTrouble = true) { saveWorkouts(since = earliest, slow = true) }

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
    val lastSkipped: List<String> get() = lastStats.skipped.toList()

    /** Reads the locale each time, so a language change applies without restarting. */
    private fun monthLabel(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))

    companion object {
        const val KIND_HR = "hr"
        /** Requests at the same time. Google allows 300 a minute per user; a full sync is about 20 in total. */
        private const val PARALLEL = 3
        const val KIND_HOURLY = "hourly"
    }
}
