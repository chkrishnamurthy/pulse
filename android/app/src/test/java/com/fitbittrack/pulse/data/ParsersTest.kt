package com.fitbittrack.pulse.data

import com.fitbittrack.pulse.data.local.DailyMetricEntity
import com.fitbittrack.pulse.data.local.HourlyStepsEntity
import com.fitbittrack.pulse.data.local.toDaySummaries
import com.fitbittrack.pulse.data.local.toDomain
import com.fitbittrack.pulse.data.local.toEntity
import com.fitbittrack.pulse.data.local.toHourly
import com.fitbittrack.pulse.data.remote.DAILY_LISTS
import com.fitbittrack.pulse.data.remote.DAILY_ROLLUPS
import com.fitbittrack.pulse.data.remote.Metric
import com.fitbittrack.pulse.data.remote.Parsers
import com.fitbittrack.pulse.data.remote.RollupPoint
import com.fitbittrack.pulse.domain.SleepStageType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Sample JSON follows the shapes in developers.google.com/health (endpoints + REST reference). */
class ParsersTest {

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun dailyRollupSteps() {
        val spec = DAILY_ROLLUPS.first { it.metric == Metric.STEPS }
        val p = RollupPoint(null, null, LocalDate.of(2026, 11, 21), obj("""{"countSum":"8430"}"""))
        val v = Parsers.dailyRollup(spec, listOf(p)).single()
        assertEquals(8430.0, v.value, 0.0)
        assertEquals(LocalDate.of(2026, 11, 21), v.date)
    }

    @Test fun distanceIsMillimetersToMeters() {
        val spec = DAILY_ROLLUPS.first { it.metric == Metric.DISTANCE_M }
        assertEquals(5432.1, spec.read(obj("""{"millimetersSum":"5432100"}"""))!!, 0.001)
    }

    @Test fun activeZoneMinutesSumsZones() {
        val spec = DAILY_ROLLUPS.first { it.metric == Metric.AZM }
        val v = spec.read(obj("""{"sumInFatBurnHeartZone":"10","sumInCardioHeartZone":"8","sumInPeakHeartZone":"4"}"""))
        assertEquals(22.0, v!!, 0.0)
    }

    @Test fun activeMinutesSumsLevels() {
        val spec = DAILY_ROLLUPS.first { it.metric == Metric.ACTIVE_MIN }
        val v = spec.read(obj("""{"activeMinutesRollupByActivityLevel":[{"activityLevel":"LIGHT","activeMinutesSum":"30"},{"activityLevel":"VIGOROUS","activeMinutesSum":"12"}]}"""))
        assertEquals(42.0, v!!, 0.0)
        assertNull(spec.read(obj("{}")))
    }

    @Test fun dailyListKeepsNewestPerDay() {
        val spec = DAILY_LISTS.first { it.metric == Metric.RESTING_HR }
        val points = listOf(
            obj("""{"name":"a","dailyRestingHeartRate":{"date":{"year":2026,"month":11,"day":21},"beatsPerMinute":"58"}}"""),
            obj("""{"name":"b","dailyRestingHeartRate":{"date":{"year":2026,"month":11,"day":21},"beatsPerMinute":"61"}}"""),
            obj("""{"name":"c","dailyRestingHeartRate":{"date":{"year":2026,"month":11,"day":20},"beatsPerMinute":"60"}}"""),
        )
        val out = Parsers.dailyList(spec, points)
        assertEquals(2, out.size)
        assertEquals(58.0, out.first { it.date.dayOfMonth == 21 }.value, 0.0)
    }

    @Test fun skinTempIsDeltaFromBaseline() {
        val spec = DAILY_LISTS.first { it.metric == Metric.SKIN_TEMP_DELTA }
        val v = spec.read(obj("""{"nightlyTemperatureCelsius":34.1,"baselineTemperatureCelsius":33.8}"""))
        assertEquals(0.3, v!!, 1e-9)
    }

    @Test fun sleepWithStages() {
        val dp = obj(
            """{"name":"users/me/dataTypes/sleep/dataPoints/123","sleep":{
              "interval":{"startTime":"2026-11-21T17:00:00Z","startUtcOffset":"19800s","endTime":"2026-11-22T01:00:00Z","endUtcOffset":"19800s",
                "civilEndTime":{"date":{"year":2026,"month":11,"day":22},"time":{"hours":6,"minutes":30}}},
              "type":"STAGES",
              "stages":[
                {"startTime":"2026-11-21T17:00:00Z","endTime":"2026-11-21T17:30:00Z","type":"LIGHT"},
                {"startTime":"2026-11-21T17:30:00Z","endTime":"2026-11-21T18:30:00Z","type":"DEEP"},
                {"startTime":"2026-11-21T18:30:00Z","endTime":"2026-11-21T18:40:00Z","type":"AWAKE"},
                {"startTime":"2026-11-21T18:40:00Z","endTime":"2026-11-21T19:40:00Z","type":"REM"}],
              "metadata":{"mainSleep":true},
              "summary":{"minutesAsleep":"407","minutesAwake":"57"}}}"""
        )
        val parsed = Parsers.sleep(dp, zone)!!
        assertEquals(LocalDate.of(2026, 11, 22), parsed.wakeDate)
        assertEquals(407, parsed.session.minutesAsleep)
        assertEquals(60, parsed.session.minutesIn(SleepStageType.DEEP))
        assertEquals(10, parsed.session.minutesIn(SleepStageType.AWAKE))

        // Round trip through the database row keeps everything.
        val back = parsed.session.toEntity(parsed.wakeDate).toDomain()
        assertEquals(parsed.session.stages, back.stages)
        assertEquals(407, back.minutesAsleep)
    }

    @Test fun sleepWakeDateFromOffsetWhenNoCivilTime() {
        val dp = obj("""{"sleep":{"interval":{"startTime":"2026-11-21T20:00:00Z","endTime":"2026-11-21T23:00:00Z","endUtcOffset":"19800s"}}}""")
        // 23:00Z + 5:30 = 04:30 on the 22nd.
        assertEquals(LocalDate.of(2026, 11, 22), Parsers.sleep(dp, ZoneId.of("UTC"))!!.wakeDate)
    }

    @Test fun exerciseSummary() {
        val dp = obj(
            """{"name":"users/me/dataTypes/exercise/dataPoints/9","exercise":{
              "interval":{"startTime":"2026-11-21T01:00:00Z","endTime":"2026-11-21T01:45:00Z","civilStartTime":{"date":{"year":2026,"month":11,"day":21}}},
              "exerciseType":"RUNNING","displayName":"",
              "activeDuration":"2400.5s",
              "metricsSummary":{"caloriesKcal":350.5,"distanceMillimeters":6000000,"averageHeartRateBeatsPerMinute":"142"}}}"""
        )
        val w = Parsers.exercise(dp, zone)!!
        assertEquals("Running", w.workout.name)
        assertEquals(2400L, w.workout.activeSeconds)
        assertEquals(6000.0, w.workout.distanceMeters!!, 0.0)
        assertEquals(142, w.workout.avgHeartRate)
        assertEquals(LocalDate.of(2026, 11, 21), w.startDate)
    }

    @Test fun trackerSyncPrefersTrackers() {
        val devices = listOf(
            obj("""{"deviceType":"SCALE","lastSyncTime":"2026-11-22T09:00:00Z"}"""),
            obj("""{"deviceType":"TRACKER","lastSyncTime":"2026-11-22T07:05:00Z"}"""),
        )
        assertEquals(Instant.parse("2026-11-22T07:05:00Z"), Parsers.lastTrackerSync(devices))
    }

    @Test fun daySummaryGrouping() {
        val rows = listOf(
            DailyMetricEntity("2026-11-21", Metric.STEPS, 9000.0),
            DailyMetricEntity("2026-11-21", Metric.RESTING_HR, 58.4),
            DailyMetricEntity("2026-11-20", Metric.STEPS, 5000.0),
        )
        val s = rows.toDaySummaries()
        assertEquals(listOf(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21)), s.map { it.date })
        assertEquals(58, s[1].restingHeartRate)
        assertNull(s[0].restingHeartRate)
    }

    @Test fun hourlyHas24Slots() {
        val h = listOf(HourlyStepsEntity("2026-11-22", 7, 1200), HourlyStepsEntity("2026-11-22", 8, 300)).toHourly()
        assertEquals(24, h.size)
        assertEquals(1200L, h[7])
        assertNull(h[0])
        assertTrue(h.count { it != null } == 2)
    }
}
