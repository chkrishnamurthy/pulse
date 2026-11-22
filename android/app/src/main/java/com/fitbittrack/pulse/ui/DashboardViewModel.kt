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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Feeds every dashboard screen. Reads only from [HealthRepository] (the local database),
 * so screens open instantly and work offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel : ViewModel() {

    private data class Source(val repo: HealthRepository, val isSample: Boolean)

    private val sample = SampleHealthRepository()
    private val source = MutableStateFlow(Source(sample, true))

    val isSample: StateFlow<Boolean> = source.map { it.isSample }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Called by the app shell: real repository after sign-in, null to go back to sample data. */
    fun useRepository(repo: HealthRepository?) {
        source.value = if (repo == null) Source(sample, true) else Source(repo, false)
    }

    private val heartRange = MutableStateFlow(Range.Day)
    private val reportRange = MutableStateFlow(ReportRange.Week)

    private val today: LocalDate get() = LocalDate.now()

    private fun <T> fromRepo(block: (HealthRepository) -> Flow<T>): Flow<T> = source.flatMapLatest { block(it.repo) }
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val goals: StateFlow<Goals> = fromRepo { it.goals }.state(Goals())

    val goalsUi: StateFlow<GoalsUi> = goals.map { GoalsUi(it.dailySteps, it.sleepMinutes, it.weeklyActiveZoneMinutes, it.dailyFloors) }
        .state(GoalsUi(10_000, 480, 150, 10))

    val sync: StateFlow<SyncUi> = fromRepo { it.syncStatus }.map(Mappers::sync)
        .state(SyncUi(false, null, null, false, "Not synced yet", "unknown"))

    val todayUi: StateFlow<TodayUi?> = fromRepo { repo ->
        val d = today
        combine(
            repo.daySummary(d),
            repo.daySummaries(d.minusDays(30), d),
            repo.hourlySteps(d),
            repo.sleep(d, d),
            repo.goals,
        ) { day, last30, hourly, nights, goals ->
            Mappers.today(d, day, last30, hourly, nights.lastOrNull { it.isMainSleep }, goals, LocalTime.now())
        }
    }.state(null)

    val heartUi: StateFlow<HeartUi?> = combine(source, heartRange) { s, r -> s to r }.flatMapLatest { (s, range) ->
        val d = today
        val from = d.minusDays(maxOf(range.days, 30).toLong() - 1)
        combine(s.repo.heartRate(d), s.repo.daySummaries(from, d), s.repo.goals) { samples, sums, goals ->
            Mappers.heart(range, samples, sums, d, goals)
        }
    }.state(null)

    val sleepUi: StateFlow<SleepUi?> = fromRepo { repo ->
        val d = today
        combine(repo.sleep(d.minusDays(6), d), repo.daySummaries(d.minusDays(6), d), repo.goals) { nights, sums, goals ->
            Mappers.sleep(nights, sums, goals)
        }
    }.state(null)

    val activityUi: StateFlow<ActivityUi?> = fromRepo { repo ->
        val d = today
        combine(repo.workouts(d.minusDays(6), d), repo.daySummaries(d.minusDays(6), d), repo.goals) { w, sums, goals ->
            Mappers.activity(w, sums, goals)
        }
    }.state(null)

    val reportRangeState: StateFlow<ReportRange> = reportRange
    val reportDays: StateFlow<List<DayStat>> = combine(source, reportRange) { s, r -> s to r }.flatMapLatest { (s, range) ->
        val d = today
        val from = d.minusDays(range.days - 1L)
        combine(s.repo.daySummaries(from, d), s.repo.sleep(from, d)) { sums, nights ->
            Reports.fillGaps(Mappers.dayStats(sums, nights), from, d)
        }
    }.state(emptyList())

    fun setHeartRange(r: Range) { heartRange.value = r }
    fun setReportRange(r: ReportRange) { reportRange.value = r }

    fun setGoals(g: GoalsUi) = viewModelScope.launch {
        source.value.repo.setGoals(Goals(g.dailySteps, g.sleepMinutes, g.weeklyZoneMinutes, g.dailyFloors))
    }

    fun refresh() = viewModelScope.launch { runCatching { source.value.repo.refreshNow() } }

    fun deleteLocalData() = viewModelScope.launch { runCatching { source.value.repo.deleteAllLocalData() } }
}
