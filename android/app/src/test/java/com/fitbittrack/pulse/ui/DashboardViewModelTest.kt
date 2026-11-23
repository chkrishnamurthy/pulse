package com.fitbittrack.pulse.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** U-004: screens roll over to the new day at midnight without restarting the app. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `today rolls over at midnight`() = runTest(dispatcher) {
        var now = LocalDateTime.of(2026, 11, 22, 23, 59, 30)
        val vm = DashboardViewModel(now = { now })
        val seen = mutableListOf<java.time.LocalDate>()
        val job = launch { vm.todayUi.filterNotNull().collect { seen += it.date } }

        runCurrent()
        assertEquals(java.time.LocalDate.of(2026, 11, 22), seen.last())

        now = LocalDateTime.of(2026, 11, 23, 0, 0, 1)
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(java.time.LocalDate.of(2026, 11, 23), seen.last())
        job.cancel()
    }

    /** Counts refresh calls; everything else comes from the sample. */
    private class CountingRepo(
        private val base: com.fitbittrack.pulse.domain.HealthRepository = com.fitbittrack.pulse.ui.sample.SampleHealthRepository(),
    ) : com.fitbittrack.pulse.domain.HealthRepository by base {
        var onOpen = 0
        var live = 0
        override suspend fun refreshIfStale(maxAge: java.time.Duration) { onOpen++ }
        override suspend fun refreshToday() { live++; if (live == 1) error("offline") }
    }

    @Test fun `refreshes on open, then regularly, and stops off screen`() = runTest(dispatcher) {
        val vm = DashboardViewModel()
        val repo = CountingRepo()
        vm.startLiveRefresh()
        runCurrent()
        assertEquals(0, repo.onOpen) // sample data: nothing to refresh
        vm.useRepository(repo)
        runCurrent()
        assertEquals(1, repo.onOpen)
        assertEquals(0, repo.live)

        advanceTimeBy(DashboardViewModel.LIVE_INTERVAL_MS * 3 + 1)
        runCurrent()
        assertEquals(3, repo.live) // one failure doesn't end the loop
        assertEquals(1, repo.onOpen)

        vm.stopLiveRefresh()
        advanceTimeBy(DashboardViewModel.LIVE_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(3, repo.live)

        vm.startLiveRefresh()
        vm.startLiveRefresh() // calling twice starts one loop
        runCurrent()
        assertEquals(2, repo.onOpen)
        vm.stopLiveRefresh()
    }

    @Test fun `starts on sample data and switches to a real repository`() = runTest(dispatcher) {
        val vm = DashboardViewModel()
        val job = launch { vm.isSample.collect { } }
        runCurrent()
        assertTrue(vm.isSample.value)
        vm.useRepository(com.fitbittrack.pulse.ui.sample.SampleHealthRepository())
        runCurrent()
        assertEquals(false, vm.isSample.value)
        job.cancel()
    }
}
