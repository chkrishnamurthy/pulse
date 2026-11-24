package com.fitbittrack.pulse.data.remote

import com.fitbittrack.pulse.domain.BandBattery
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.SleepStage
import com.fitbittrack.pulse.domain.SleepStageType
import com.fitbittrack.pulse.domain.Workout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// ---------- small JSON helpers (int64 values arrive as strings, so numbers are read leniently) ----------

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
internal fun JsonObject.instant(key: String): Instant? = str(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** {"year":2026,"month":11,"day":22} → LocalDate. Also accepts a CivilDateTime ({"date":{…},"time":{…}}). */
internal fun JsonObject.civilDate(): LocalDate? {
    val d = obj("date") ?: this
    val y = d.num("year")?.toInt() ?: return null
    val m = d.num("month")?.toInt() ?: return null
    val day = d.num("day")?.toInt() ?: return null
    return runCatching { LocalDate.of(y, m, day) }.getOrNull()
}

/** "1234.5s" → seconds. */
internal fun parseDurationSeconds(s: String?): Double? = s?.removeSuffix("s")?.toDoubleOrNull()

/** Local date of an interval end/start: prefers Google's civil time, else time + UTC offset, else the phone's zone. */
internal fun JsonObject.localDateOf(timeKey: String, offsetKey: String, civilKey: String, zone: ZoneId): LocalDate? {
    obj(civilKey)?.civilDate()?.let { return it }
    val t = instant(timeKey) ?: return null
    val offsetSec = parseDurationSeconds(str(offsetKey))?.toInt()
    return if (offsetSec != null) t.atOffset(ZoneOffset.ofTotalSeconds(offsetSec)).toLocalDate()
    else t.atZone(zone).toLocalDate()
}

private fun JsonElement.asObj(): JsonObject? = this as? JsonObject

// ---------- metric keys stored in the daily table ----------

object Metric {
    const val STEPS = "steps"
    const val DISTANCE_M = "distance_m"
    const val FLOORS = "floors"
    const val CALORIES = "calories"
    const val AZM = "azm"
    const val ACTIVE_MIN = "active_min"
    const val RESTING_HR = "resting_hr"
    const val HRV = "hrv"
    const val SPO2 = "spo2"
    const val RESP_RATE = "resp_rate"
    const val SKIN_TEMP_DELTA = "skin_temp_delta"
    const val VO2MAX = "vo2max"
}

/**
 * How to fetch one daily number. [valueKey] is the JSON key inside a rollup point or data point.
 * Field names are from the Google Health REST reference (StepsRollupValue, DistanceRollupValue, …).
 */
data class RollupSpec(
    val dataType: String,
    val valueKey: String,
    val metric: String,
    /** Google's max days per dailyRollUp request for this type. */
    val maxDays: Int,
    val read: (JsonObject) -> Double?,
)

val DAILY_ROLLUPS: List<RollupSpec> = listOf(
    RollupSpec("steps", "steps", Metric.STEPS, 90) { it.num("countSum") },
    RollupSpec("distance", "distance", Metric.DISTANCE_M, 90) { v -> v.num("millimetersSum")?.div(1000.0) },
    RollupSpec("floors", "floors", Metric.FLOORS, 90) { it.num("countSum") },
    RollupSpec("total-calories", "totalCalories", Metric.CALORIES, 14) { it.num("kcalSum") },
    RollupSpec("active-zone-minutes", "activeZoneMinutes", Metric.AZM, 90) { v ->
        val parts = listOf("sumInFatBurnHeartZone", "sumInCardioHeartZone", "sumInPeakHeartZone").mapNotNull { v.num(it) }
        if (parts.isEmpty()) null else parts.sum()
    },
    RollupSpec("active-minutes", "activeMinutes", Metric.ACTIVE_MIN, 14) { v ->
        val levels = v.arr("activeMinutesRollupByActivityLevel")?.mapNotNull { it.asObj()?.num("activeMinutesSum") }
        if (levels.isNullOrEmpty()) null else levels.sum()
    },
)

/** Daily data types read with the list endpoint (one data point per day, field "date"). */
data class DailyListSpec(
    val dataType: String,
    val filterName: String,
    val valueKey: String,
    val metric: String,
    val read: (JsonObject) -> Double?,
)

val DAILY_LISTS: List<DailyListSpec> = listOf(
    DailyListSpec("daily-resting-heart-rate", "daily_resting_heart_rate", "dailyRestingHeartRate", Metric.RESTING_HR) { it.num("beatsPerMinute") },
    DailyListSpec("daily-heart-rate-variability", "daily_heart_rate_variability", "dailyHeartRateVariability", Metric.HRV) { it.num("averageHeartRateVariabilityMilliseconds") },
    DailyListSpec("daily-oxygen-saturation", "daily_oxygen_saturation", "dailyOxygenSaturation", Metric.SPO2) { it.num("averagePercentage") },
    DailyListSpec("daily-respiratory-rate", "daily_respiratory_rate", "dailyRespiratoryRate", Metric.RESP_RATE) { it.num("breathsPerMinute") },
    DailyListSpec("daily-sleep-temperature-derivations", "daily_sleep_temperature_derivations", "dailySleepTemperatureDerivations", Metric.SKIN_TEMP_DELTA) { v ->
        val nightly = v.num("nightlyTemperatureCelsius")
        val baseline = v.num("baselineTemperatureCelsius")
        if (nightly != null && baseline != null) nightly - baseline else null
    },
    DailyListSpec("daily-vo2-max", "daily_vo2_max", "dailyVo2Max", Metric.VO2MAX) { it.num("vo2Max") },
)

data class DailyValue(val date: LocalDate, val metric: String, val value: Double)

object Parsers {

    fun dailyRollup(spec: RollupSpec, points: List<RollupPoint>): List<DailyValue> =
        points.mapNotNull { p ->
            val date = p.civilDate ?: return@mapNotNull null
            val v = spec.read(p.value) ?: return@mapNotNull null
            DailyValue(date, spec.metric, v)
        }

    /** List results are newest first; keep the first value seen for each day. */
    fun dailyList(spec: DailyListSpec, dataPoints: List<JsonObject>): List<DailyValue> {
        val seen = HashSet<LocalDate>()
        return dataPoints.mapNotNull { dp ->
            val inner = dp.obj(spec.valueKey) ?: return@mapNotNull null
            val date = inner.obj("date")?.civilDate() ?: return@mapNotNull null
            val v = spec.read(inner) ?: return@mapNotNull null
            if (!seen.add(date)) null else DailyValue(date, spec.metric, v)
        }
    }

    data class ParsedSleep(val session: SleepSession, val wakeDate: LocalDate)

    fun sleep(dp: JsonObject, zone: ZoneId): ParsedSleep? {
        val s = dp.obj("sleep") ?: return null
        val interval = s.obj("interval") ?: return null
        val start = interval.instant("startTime") ?: return null
        val end = interval.instant("endTime") ?: return null
        val id = dp.str("name") ?: "sleep-${start.epochSecond}"
        val stages = s.arr("stages").orEmpty().mapNotNull { el ->
            val st = el.asObj() ?: return@mapNotNull null
            val a = st.instant("startTime") ?: return@mapNotNull null
            val b = st.instant("endTime") ?: return@mapNotNull null
            SleepStage(stageType(st.str("type")), a, b)
        }.sortedBy { it.start }
        val minutesAsleep = s.obj("summary")?.num("minutesAsleep")?.toLong()
        val main = s.obj("metadata")?.bool("mainSleep") ?: true
        val wake = interval.localDateOf("endTime", "endUtcOffset", "civilEndTime", zone) ?: return null
        return ParsedSleep(SleepSession(id, start, end, stages, minutesAsleep, main, wake), wake)
    }

    fun stageType(raw: String?): SleepStageType = when (raw) {
        "AWAKE" -> SleepStageType.AWAKE
        "LIGHT" -> SleepStageType.LIGHT
        "DEEP" -> SleepStageType.DEEP
        "REM" -> SleepStageType.REM
        // CLASSIC sleep (no stage data) uses ASLEEP / RESTLESS.
        else -> SleepStageType.UNKNOWN
    }

    data class ParsedWorkout(val workout: Workout, val startDate: LocalDate)

    fun exercise(dp: JsonObject, zone: ZoneId): ParsedWorkout? {
        val e = dp.obj("exercise") ?: return null
        val interval = e.obj("interval") ?: return null
        val start = interval.instant("startTime") ?: return null
        val end = interval.instant("endTime") ?: return null
        val type = e.str("exerciseType") ?: "OTHER"
        val m = e.obj("metricsSummary")
        val w = Workout(
            id = dp.str("name") ?: "exercise-${start.epochSecond}",
            type = type,
            name = e.str("displayName")?.takeIf { it.isNotBlank() } ?: prettyType(type),
            start = start,
            end = end,
            activeSeconds = parseDurationSeconds(e.str("activeDuration"))?.toLong()
                ?: Duration.between(start, end).seconds,
            caloriesKcal = m?.num("caloriesKcal"),
            distanceMeters = m?.num("distanceMillimeters")?.div(1000.0),
            avgHeartRate = m?.num("averageHeartRateBeatsPerMinute")?.toInt(),
        )
        val date = interval.localDateOf("startTime", "startUtcOffset", "civilStartTime", zone) ?: return null
        return ParsedWorkout(w, date)
    }

    /** RUNNING → Running, CARDIO_WORKOUT → Cardio workout. */
    fun prettyType(type: String): String =
        type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** The newest heart-rate reading in a list answer, whatever its order. */
    fun newestHeart(dataPoints: List<JsonObject>): HeartRateSample? = dataPoints.mapNotNull { dp ->
        val h = dp.obj("heartRate") ?: return@mapNotNull null
        val at = h.obj("sampleTime")?.instant("physicalTime") ?: return@mapNotNull null
        val bpm = h.num("beatsPerMinute")?.toInt()?.takeIf { it in 20..250 } ?: return@mapNotNull null
        HeartRateSample(at, bpm)
    }.maxByOrNull { it.time }

    /** Latest tracker sync time from pairedDevices (TRACKER devices preferred). */
    fun lastTrackerSync(devices: List<JsonObject>): Instant? {
        val trackers = devices.filter { it.str("deviceType") == "TRACKER" }.ifEmpty { devices }
        return trackers.mapNotNull { it.instant("lastSyncTime") }.maxOrNull()
    }

    /** Battery of the tracker from pairedDevices: percent when given, else the status word. Null when neither is there. */
    fun battery(devices: List<JsonObject>): BandBattery? {
        val tracker = devices.filter { it.str("deviceType") == "TRACKER" }.ifEmpty { devices }
            .maxByOrNull { it.instant("lastSyncTime")?.toEpochMilli() ?: 0L } ?: return null
        val level = tracker.num("batteryLevel")?.toInt()?.takeIf { it in 0..100 }
        val status = tracker.str("batteryStatus")?.takeIf { it.isNotBlank() }
        return if (level == null && status == null) null else BandBattery(level, status)
    }

    fun parseDataPoints(json: JsonObject): List<JsonObject> =
        json.arr("dataPoints").orEmpty().mapNotNull { it.asObj() }
}
