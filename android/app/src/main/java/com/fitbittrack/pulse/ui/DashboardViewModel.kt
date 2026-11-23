package com.fitbittrack.pulse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.ui.goals.DayGoals
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.goals.MonthUi
import com.fitbittrack.pulse.ui.model.ActivityUi
import com.fitbittrack.pulse.ui.model.DayStat
import com.fitbittrack.pulse.ui.model.DayUi
import com.fitbittrack.pulse.ui.model.HeartUi
import com.fitbittrack.pulse.ui.model.HubUi
import com.fitbittrack.pulse.ui.model.Mappers
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.MetricMapper
import com.fitbittrack.pulse.ui.model.MetricUi
import com.fitbittrack.pulse.ui.model.Range
import com.fitbittrack.pulse.ui.model.ReportRange
import com.fitbittrack.pulse.ui.model.SleepUi
import com.fitbittrack.pulse.ui.model.SyncUi
import com.fitbittrack.pulse.ui.model.TodayUi
import com.fitbittrack.pulse.ui.reports.Reports
import com.fitbittrack.pulse.ui.sample.SampleHealthRepository
import com.fitbittrack.pulse.ui.screens.GoalsUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Feeds every dashboard screen. Reads only from [HealthRepository] (the local database),
 * so screens open instantly and work offline.
 *
 * Dates come from a clock flow that ticks every minute, so screens roll over at midnight
 * (and follow time-zone changes) without restarting the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) : ViewModel() {

    private data class Source(val repo: HealthRepository, val isSample: Boolean)

    private val sample = SampleHealthRepository()
    private val source = MutableStateFlow(Source(sample, true))

    /** What the screens read right now (sample or real). Chat reads the same. */
    val repository: HealthRepository get() = source.value.repo

    val isSample: StateFlow<Boolean> = source.map { it.isSample }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Called by the app shell: real repository when an account is connected, null for sample data. */
    fun useRepository(repo: HealthRepository?) {
        val next = if (repo == null) Source(sample, true) else Source(repo, false)
        if (next.repo !== source.value.repo) source.value = next
    }

    private val heartRange = MutableStateFlow(Range.Day)
    private val reportRange = MutableStateFlow(ReportRange.Week)

    /** Current local time, re-emitted at the start of every minute while a screen is watching. */
    private val clock: Flow<LocalDateTime> = flow {
        while (true) {
            val t = now()
            emit(t)
            delay(ChronoUnit.MILLIS.between(t, t.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)).coerceAtLeast(1_000))
        }
    }
    private val date: Flow<LocalDate> = clock.map { it.toLocalDate() }.distinctUntilChanged()

    private data class Ctx(val repo: HealthRepository, val day: LocalDate)
    private val ctx: Flow<Ctx> = combine(source, date) { s, d -> Ctx(s.repo, d) }.distinctUntilChanged()

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val goals: StateFlow<Goals> = source.flatMapLatest { it.repo.goals }.state(Goals())

    val goalsUi: StateFlow<GoalsUi> = goals.map { GoalsUi(it.dailySteps, it.sleepMinutes, it.weeklyActiveZoneMinutes, it.dailyFloors, it.dailyCalories, it.dailyDistanceMeters) }
        .state(GoalsUi(10_000, 480, 150, 10, 2_800, 7_000))

    val sync: StateFlow<SyncUi> = source.flatMapLatest { s -> s.repo.syncStatus.map { Mappers.sync(it, s.isSample) } }
        .state(SyncUi(false, null, null, false, "Not synced yet", "unknown", firstSyncPending = false))

    val todayUi: StateFlow<TodayUi?> = ctx.flatMapLatest { (repo, d) ->
        combine(
            combine(repo.daySummary(d), repo.daySummaries(d.minusDays(30), d), repo.hourlySteps(d)) { a, b, c -> Triple(a, b, c) },
            repo.sleep(d, d),
            repo.goals,
            clock,
        ) { (day, last30, hourly), nights, goals, t ->
            Mappers.today(d, day, last30, hourly, nights.lastOrNull { it.isMainSleep }, goals, t.toLocalTime())
        }
    }.state(null)

    val heartUi: StateFlow<HeartUi?> = combine(ctx, heartRange) { c, r -> c to r }.flatMapLatest { (c, range) ->
        val from = c.day.minusDays(maxOf(range.days, 30).toLong() - 1)
        combine(c.repo.heartRate(c.day), c.repo.daySummaries(from, c.day), c.repo.goals) { samples, sums, goals ->
            Mappers.heart(range, samples, sums, c.day, goals)
        }
    }.state(null)

    val sleepUi: StateFlow<SleepUi?> = ctx.flatMapLatest { (repo, d) ->
        combine(repo.sleep(d.minusDays(6), d), repo.daySummaries(d.minusDays(6), d), repo.goals) { nights, sums, goals ->
            Mappers.sleep(nights, sums, goals)
        }
    }.state(null)

    val activityUi: StateFlow<ActivityUi?> = ctx.flatMapLatest { (repo, d) ->
        combine(repo.workouts(d.minusDays(6), d), repo.daySummaries(d.minusDays(6), d), repo.goals) { w, sums, goals ->
            Mappers.activity(w, sums, goals)
        }
    }.state(null)

    val reportRangeState: StateFlow<ReportRange> = reportRange
    val reportDays: StateFlow<List<DayStat>> = combine(ctx, reportRange) { c, r -> c to r }.flatMapLatest { (c, range) ->
        val from = c.day.minusDays(range.days - 1L)
        combine(c.repo.daySummaries(from, c.day), c.repo.sleep(from, c.day)) { sums, nights ->
            Reports.fillGaps(Mappers.dayStats(sums, nights), from, c.day)
        }
    }.state(emptyList())

    // ---------- goals and calendar ----------

    /** Every day from [from] to today with its goals. */
    private fun goalDays(repo: HealthRepository, from: LocalDate, to: LocalDate): Flow<List<DayGoals>> =
        combine(repo.daySummaries(from, to), repo.sleep(from, to), repo.goalHistory, coreGoals) { sums, nights, history, core ->
            GoalCalendar.days(from, to, sums, nights, history, core = core)
        }

    /** The goals a day needs to be complete. The user picks them in You. */
    private val coreGoals = MutableStateFlow(GoalCalendar.CORE)
    fun setCoreGoals(core: Set<GoalMetric>) {
        if (core.isEmpty()) return
        coreGoals.value = core
        if (calendarMetric.value?.let { it !in core } == true) calendarMetric.value = null
    }

    /** The last seven days, for Today. */
    val weekGoals: StateFlow<List<DayGoals>> = ctx.flatMapLatest { (repo, d) -> goalDays(repo, d.minusDays(6), d) }.state(emptyList())

    private val calendarMonth = MutableStateFlow<YearMonth?>(null) // null = the current month
    private val calendarMetric = MutableStateFlow<GoalMetric?>(null)

    private data class CalendarDays(val month: YearMonth, val today: LocalDate, val days: List<DayGoals>)

    /** A year of days for the streaks, or more when an older month is open. */
    private val calendarDays: Flow<CalendarDays> = combine(ctx, calendarMonth) { c, m -> c to (m ?: YearMonth.from(c.day)) }
        .flatMapLatest { (c, month) ->
            goalDays(c.repo, minOf(c.day.minusDays(365), month.atDay(1)), c.day).map { CalendarDays(month, c.day, it) }
        }

    val calendarUi: StateFlow<MonthUi?> = combine(calendarDays, calendarMetric) { d, metric ->
        GoalCalendar.month(d.month, metric, d.days, d.today, WeekFields.of(Locale.getDefault()).firstDayOfWeek)
    }.flowOn(Dispatchers.Default).state(null)

    fun setCalendarMetric(m: GoalMetric?) { calendarMetric.value = m }
    fun setCalendarMonth(m: YearMonth) { calendarMonth.value = m }

    /** One day in full. Cold: each open page collects its own. */
    fun day(date: LocalDate): Flow<DayUi> = ctx.flatMapLatest { (repo, today) ->
        combine(goalDays(repo, date, date), repo.daySummary(date), repo.sleep(date, date), repo.hourlySteps(date), repo.workouts(date, date)) { goals, sum, nights, hourly, workouts ->
            DayUi(goals.first(), sum, nights.filter { it.isMainSleep }.maxOfOrNull { it.minutesAsleep }?.toInt(), hourly, Mappers.workouts(workouts), today)
        }
    }

    /** One metric over time. Cold: each open page collects its own. */
    fun metric(kind: MetricKind, range: ReportRange): Flow<MetricUi> = ctx.flatMapLatest { (repo, today) ->
        val hourly = if (kind == MetricKind.Steps) repo.hourlySteps(today) else flowOf(emptyList())
        combine(repo.daySummaries(today.minusDays(range.days - 1L), today), hourly, repo.goalHistory) { sums, hours, history ->
            MetricMapper.metric(kind, range, today, sums, hours) { d ->
                kind.goal?.let { GoalCalendar.day(d, null, null, GoalCalendar.goalsOn(history, d))[it].target }
            }
        }
    }

    val hubUi: StateFlow<HubUi?> = ctx.flatMapLatest { (repo, d) ->
        combine(repo.daySummaries(d.minusDays(29), d), repo.sleep(d, d), repo.workouts(d.minusDays(6), d), repo.heartRate(d)) { sums, nights, workouts, hr ->
            val newest = sums.sortedByDescending { it.date }
            HubUi(
                today = d,
                latest = MetricKind.entries.mapNotNull { k -> newest.firstNotNullOfOrNull { s -> k.read(s)?.let { s.date to it } }?.let { k to it } }.toMap(),
                latestBpm = hr.lastOrNull()?.bpm,
                sleepMinutes = nights.filter { it.isMainSleep }.maxOfOrNull { it.minutesAsleep }?.toInt(),
                workouts7 = workouts.size,
            )
        }
    }.state(null)

    fun setHeartRange(r: Range) { heartRange.value = r }
    fun setReportRange(r: ReportRange) { reportRange.value = r }

    fun setGoals(g: GoalsUi) = launchSafely {
        source.value.repo.setGoals(Goals(g.dailySteps, g.sleepMinutes, g.weeklyZoneMinutes, g.dailyFloors, g.dailyCalories, g.dailyDistanceMeters))
    }

    /** Pull-to-refresh / Sync now. Failures show through the sync status, not as a crash. */
    fun refresh() = launchSafely { source.value.repo.refreshNow() }

    private var live: Job? = null

    /** Called when Pulse comes on screen. Syncs at once if the data is old, then updates today's numbers regularly. */
    fun startLiveRefresh() {
        if (live?.isActive == true) return
        live = viewModelScope.launch {
            source.collectLatest { s ->
                if (s.isSample) return@collectLatest
                quietly { s.repo.refreshIfStale(OPEN_MAX_AGE) }
                while (true) {
                    delay(LIVE_INTERVAL_MS)
                    quietly { s.repo.refreshToday() }
                }
            }
        }
    }

    /** Called when Pulse leaves the screen. Stops the loop and a sync it started; the background sync catches up. */
    fun stopLiveRefresh() {
        live?.cancel()
        live = null
    }

    fun deleteLocalData() = launchSafely { source.value.repo.deleteAllLocalData() }

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch { quietly(block) }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The repository records sync failures in syncStatus; nothing else to show here.
        }
    }

    companion object {
        /** Opening Pulse starts a full sync if the last one is older than this. */
        val OPEN_MAX_AGE: Duration = Duration.ofMinutes(1)

        /** How often today's numbers are updated while Pulse is on screen. */
        const val LIVE_INTERVAL_MS = 90_000L
    }
}
