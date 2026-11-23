package com.fitbittrack.pulse.direct

import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.ui.sample.SampleHealthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class LanesTest {

    private val today = LocalDate.of(2026, 11, 23)
    private val yesterday = today.minusDays(1)
    private fun at(s: String) = Instant.parse("2026-11-23T${s}Z")

    @Test fun `the bigger total wins, whichever lane has it`() {
        val fresherLocal = Lanes.mergeDay(DaySummary(today, steps = 4000, floors = 3), LocalSnapshot(today, steps = 4200, floors = 2), today)!!
        assertEquals(4200L, fresherLocal.steps)
        assertEquals(3L, fresherLocal.floors)
        val fresherCloud = Lanes.mergeDay(DaySummary(today, steps = 5000), LocalSnapshot(today, steps = 4200), today)!!
        assertEquals(5000L, fresherCloud.steps)
    }

    @Test fun `only steps, distance and floors are touched`() {
        val cloud = DaySummary(today, steps = 10, restingHeartRate = 55, hrvMs = 40.0, caloriesKcal = 900.0)
        val merged = Lanes.mergeDay(cloud, LocalSnapshot(today, steps = 20, distanceMeters = 12.0), today)
        assertEquals(cloud.copy(steps = 20, distanceMeters = 12.0), merged)
    }

    @Test fun `other days are never changed, also after midnight`() {
        val cloud = DaySummary(yesterday, steps = 100)
        assertEquals(cloud, Lanes.mergeDay(cloud, LocalSnapshot(today, steps = 9000), yesterday))
        // A snapshot left over from yesterday is ignored for today.
        assertNull(Lanes.mergeDay(null, LocalSnapshot(yesterday, steps = 9000), today))
    }

    @Test fun `no Health Connect data means exactly the cloud data`() {
        val cloud = DaySummary(today, steps = 100)
        assertEquals(cloud, Lanes.mergeDay(cloud, null, today))
        assertEquals(cloud, Lanes.mergeDay(cloud, LocalSnapshot(today), today))
        assertNull(Lanes.mergeDay(null, LocalSnapshot(today), today))
    }

    @Test fun `today appears from Health Connect before the cloud has it`() {
        assertEquals(300L, Lanes.mergeDay(null, LocalSnapshot(today, steps = 300), today)?.steps)
        val days = Lanes.mergeDays(listOf(DaySummary(yesterday, steps = 1)), LocalSnapshot(today, steps = 300), yesterday, today)
        assertEquals(listOf(yesterday, today), days.map { it.date })
        // A report that ends yesterday doesn't get today added.
        val past = listOf(DaySummary(yesterday, steps = 1))
        assertEquals(past, Lanes.mergeDays(past, LocalSnapshot(today, steps = 300), yesterday.minusDays(6), yesterday))
    }

    @Test fun `merged days hold each date once`() {
        val days = Lanes.mergeDays(listOf(DaySummary(today, steps = 1), DaySummary(yesterday, steps = 2)), LocalSnapshot(today, steps = 9), yesterday, today)
        assertEquals(listOf(2L, 9L), days.map { it.steps })
    }

    @Test fun `only newer heart rate is added`() {
        val cloud = listOf(HeartRateSample(at("09:00:00"), 60), HeartRateSample(at("09:05:00"), 61))
        val local = LocalSnapshot(today, heart = listOf(HeartRateSample(at("09:05:00"), 99), HeartRateSample(at("09:10:00"), 72)))
        assertEquals(listOf(60, 61, 72), Lanes.mergeHeart(cloud, local, today).map { it.bpm })
        assertEquals(listOf(99, 72), Lanes.mergeHeart(emptyList(), local, today).map { it.bpm })
        assertEquals(cloud, Lanes.mergeHeart(cloud, local, yesterday))
    }

    @Test fun `a record across midnight counts only its part of today`() {
        val midnight = at("00:00:00")
        assertEquals(50.0, Lanes.partAfter(midnight, midnight.minusSeconds(60), midnight.plusSeconds(60), 100.0), 0.001)
        assertEquals(100.0, Lanes.partAfter(midnight, midnight, midnight.plusSeconds(60), 100.0), 0.001)
        assertEquals(0.0, Lanes.partAfter(midnight, midnight.minusSeconds(60), midnight, 100.0), 0.001)
    }

    @Test fun `only Fitbit sources count`() {
        assertTrue(Lanes.isBandSource("com.fitbit.FitbitMobile"))
        assertTrue(Lanes.isBandSource("com.example.app", manufacturer = "Fitbit"))
        assertFalse(Lanes.isBandSource("android"))
        assertFalse(Lanes.isBandSource("com.fitbittrack.pulse", "Acme", "Band"))
        assertFalse(Lanes.isBandSource("com.google.android.apps.fitness", "Google", "Pixel 9"))
        assertFalse(Lanes.isBandSource("com.sec.android.app.shealth"))
    }

    @Test fun `heart rate messages are read correctly`() {
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        assertEquals(72, HeartRateProfile.bpm(b(0x00, 72)))
        assertEquals(200, HeartRateProfile.bpm(b(0x00, 200))) // above 127: bytes are unsigned
        assertEquals(180, HeartRateProfile.bpm(b(0x01, 180, 0)))
        assertNull(HeartRateProfile.bpm(b(0x01, 180))) // cut short
        assertNull(HeartRateProfile.bpm(b()))
        assertNull(HeartRateProfile.bpm(b(0x00, 0))) // no reading
        assertNull(HeartRateProfile.bpm(b(0x01, 0x10, 0x27))) // 10000
        assertTrue(HeartRateProfile.onSkin(b(0x00, 72))) // contact not reported
        assertFalse(HeartRateProfile.onSkin(b(0x04, 72)))
        assertTrue(HeartRateProfile.onSkin(b(0x06, 72)))
    }

    private class Cloud(val day: DaySummary?) : HealthRepository by SampleHealthRepository() {
        var deleted = false
        override fun daySummary(date: LocalDate): Flow<DaySummary?> = flowOf(day)
        override suspend fun deleteAllLocalData() { deleted = true }
    }

    @Test fun `the merged repository follows Health Connect and clears it on delete`() = runBlocking {
        val local = MutableStateFlow<LocalSnapshot?>(null)
        val cloud = Cloud(DaySummary(today, steps = 100))
        val repo = MergedHealthRepository(cloud, local) { local.value = null }
        assertEquals(100L, repo.daySummary(today).first()?.steps)
        local.value = LocalSnapshot(today, steps = 150)
        assertEquals(150L, repo.daySummary(today).first()?.steps)
        repo.deleteAllLocalData()
        assertTrue(cloud.deleted)
        assertEquals(100L, repo.daySummary(today).first()?.steps)
    }
}
