package com.fitbittrack.pulse.direct

import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.domain.HeartRateSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate

/** What the screens read: the saved data from Google's servers, with today's fresher numbers from Health Connect on top. */
class MergedHealthRepository(
    private val cloud: HealthRepository,
    private val local: StateFlow<LocalSnapshot?>,
    private val clearLocal: () -> Unit = {},
) : HealthRepository by cloud {

    override fun daySummaries(from: LocalDate, to: LocalDate): Flow<List<DaySummary>> =
        combine(cloud.daySummaries(from, to), local) { c, l -> Lanes.mergeDays(c, l, from, to) }.distinctUntilChanged()

    override fun daySummary(date: LocalDate): Flow<DaySummary?> =
        combine(cloud.daySummary(date), local) { c, l -> Lanes.mergeDay(c, l, date) }.distinctUntilChanged()

    override fun heartRate(date: LocalDate): Flow<List<HeartRateSample>> =
        combine(cloud.heartRate(date), local) { c, l -> Lanes.mergeHeart(c, l, date) }.distinctUntilChanged()

    override suspend fun deleteAllLocalData() {
        clearLocal()
        cloud.deleteAllLocalData()
    }
}
