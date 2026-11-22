package com.fitbittrack.pulse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.domain.Goals
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.ui.model.ActivityUi
import com.fitbittrack.pulse.ui.model.DayStat
import com.fitbittrack.pulse.ui.model.HeartUi
import com.fitbittrack.pulse.ui.model.Mappers
import com.fitbittrack.pulse.ui.model.Range
import com.fitbittrack.pulse.ui.model.ReportRange
import com.fitbittrack.pulse.ui.model.SleepUi
import com.fitbittrack.pulse.ui.model.SyncUi
import com.fitbittrack.pulse.ui.model.TodayUi
import com.fitbittrack.pulse.ui.reports.Reports
import com.fitbittrack.pulse.ui.sample.SampleHealthRepository
import com.fitbittrack.pulse.ui.screens.GoalsUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

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

    val goalsUi: StateFlow<GoalsUi> = goals.map { GoalsUi(it.dailySteps, it.sleepMinutes, it.weeklyActiveZoneMinutes, it.dailyFloors) }
        .state(GoalsUi(10_000, 480, 150, 10))

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

    fun setHeartRange(r: Range) { heartRange.value = r }
    fun setReportRange(r: ReportRange) { reportRange.value = r }

    fun setGoals(g: GoalsUi) = launchSafely {
        source.value.repo.setGoals(Goals(g.dailySteps, g.sleepMinutes, g.weeklyZoneMinutes, g.dailyFloors))
    }

    /** Pull-to-refresh / Sync now. Failures show through the sync status, not as a crash. */
    fun refresh() = launchSafely { source.value.repo.refreshNow() }

    fun deleteLocalData() = launchSafely { source.value.repo.deleteAllLocalData() }

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The repository records sync failures in syncStatus; nothing else to show here.
        }
    }
}
