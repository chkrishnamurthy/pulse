package com.fitbittrack.pulse.direct

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What Settings shows about the Health Connect lane. */
data class LocalLaneStatus(
    val state: State = State.Off,
    /** Other apps that wrote data Pulse deliberately does not use. */
    val unusedSources: List<String> = emptyList(),
    val error: String? = null,
    /** Steps and heart rate are allowed, calories are not (they came in a later version). */
    val caloriesMissing: Boolean = false,
) {
    enum class State { Unavailable, NeedsUpdate, Off, NeedsPermission, NoBandData, On }
}

/**
 * Lane 2: reads today's numbers from Health Connect on this phone. Read-only, only while Pulse is on
 * screen, kept in memory only. If anything is missing it stays empty and Pulse uses Google's servers.
 */
class LocalLane private constructor(private val context: Context) {

    private val prefs = UiPrefsStore(context)
    private val lock = Mutex()

    private val _snapshot = MutableStateFlow<LocalSnapshot?>(null)
    val snapshot: StateFlow<LocalSnapshot?> = _snapshot.asStateFlow()

    private val _status = MutableStateFlow(LocalLaneStatus())
    val status: StateFlow<LocalLaneStatus> = _status.asStateFlow()

    suspend fun setEnabled(on: Boolean) {
        prefs.setHealthConnect(on)
        poll()
    }

    /** Switch off and give the permissions back (sign-out). */
    suspend fun turnOff() {
        prefs.setHealthConnect(false)
        poll()
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            try {
                HealthConnectClient.getOrCreate(context).permissionController.revokeAllPermissions()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The lane is off either way; the user can still remove access in Health Connect.
            }
        }
    }

    fun clear() { _snapshot.value = null; seen = null }

    /** What the last full read covered, and the ticket to ask whether anything changed since. */
    private data class Seen(val day: LocalDate, val granted: Set<String>, val token: String)
    @Volatile private var seen: Seen? = null

    /** Looks once. Never throws (except cancellation); problems end up in [status]. */
    suspend fun poll() = lock.withLock {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_UNAVAILABLE -> return@withLock off(LocalLaneStatus.State.Unavailable)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return@withLock off(LocalLaneStatus.State.NeedsUpdate)
        }
        if (!prefs.healthConnect.first()) return@withLock off(LocalLaneStatus.State.Off)
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            if (STEPS !in granted && HEART !in granted) return@withLock off(LocalLaneStatus.State.NeedsPermission)
            val day = LocalDate.now()
            val mine = granted intersect PERMISSIONS
            val last = seen?.takeIf { it.day == day && it.granted == mine }
            if (last != null) {
                val c = client.getChanges(last.token)
                if (!c.changesTokenExpired && c.changes.isEmpty() && !c.hasMore) {
                    if (_status.value.error != null) _status.value = _status.value.copy(error = null)
                    return@withLock
                }
            }
            // The ticket is taken before reading: a change in between is seen at the next look.
            val types = TYPES.filterValues { it in mine }.keys
            val token = client.getChangesToken(ChangesTokenRequest(types))
            read(client, granted)
            seen = Seen(day, mine, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            seen = null
            // Keep the last numbers (they can only be older, never wrong); try again at the next look.
            _status.value = _status.value.copy(error = "Health Connect did not answer (${e.javaClass.simpleName}).")
        }
    }

    private fun off(state: LocalLaneStatus.State) {
        _snapshot.value = null
        seen = null
        _status.value = LocalLaneStatus(state)
    }

    private suspend fun read(client: HealthConnectClient, granted: Set<String>) {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val now = Instant.now()
        val today = TimeRangeFilter.between(day.atStartOfDay(zone).toInstant(), now)
        val recent = TimeRangeFilter.between(now.minus(Duration.ofHours(48)), now)

        val unused = sortedSetOf<String>()
        /** The band's source among the newest records of one type, and the end of its newest record. */
        fun <T : Record> bandSource(records: List<T>, end: (T) -> Instant): Pair<DataOrigin, Instant>? {
            val (band, other) = records.partition { r ->
                Lanes.isBandSource(r.metadata.dataOrigin.packageName, r.metadata.device?.manufacturer, r.metadata.device?.model)
            }
            other.map { it.metadata.dataOrigin.packageName }.filterNotTo(unused) { Lanes.isPhoneSource(it) }
            return band.maxByOrNull(end)?.let { it.metadata.dataOrigin to end(it) }
        }

        var snap = LocalSnapshot(day)
        val stepRecords = if (STEPS in granted) newest(client, StepsRecord::class, recent) else emptyList()
        val heartRecords = if (HEART in granted) newest(client, HeartRateRecord::class, recent) else emptyList()
        val steps = bandSource(stepRecords) { it.endTime }
        val heart = bandSource(heartRecords) { it.endTime }
        val phoneFrom = now.minus(LiveStepMath.MAX_AGE)
        snap = snap.copy(phoneSteps = stepRecords.filter { Lanes.isPhoneSource(it.metadata.dataOrigin.packageName) && it.endTime > phoneFrom }
            .map { StepPart(it.startTime, it.endTime, it.count) })
        // Not from the aggregate: that fills empty hours with a guess of its own.
        if (CALORIES in granted) {
            val band = steps?.first ?: heart?.first
            if (band != null) {
                val from = day.atStartOfDay(zone).toInstant()
                val parts = all(client, TotalCaloriesBurnedRecord::class, today, band).map { Triple(it.startTime, it.endTime, it.energy.inKilocalories) }
                if (parts.isNotEmpty()) snap = snap.copy(caloriesKcal = Lanes.sumOnce(parts, from))
            }
        }

        if (steps != null) {
            val metrics = buildSet<AggregateMetric<*>> {
                add(StepsRecord.COUNT_TOTAL)
                if (DISTANCE in granted) add(DistanceRecord.DISTANCE_TOTAL)
                if (FLOORS in granted) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            }
            val r = client.aggregate(AggregateRequest(metrics, today, setOf(steps.first)))
            // Health Connect leaves out apps that are not on its priority list. Then add up the band's own records:
            // one source doesn't count the same minute twice.
            val from = day.atStartOfDay(zone).toInstant()
            val band = steps.first
            snap = snap.copy(
                steps = r[StepsRecord.COUNT_TOTAL]
                    ?: total(client, StepsRecord::class, today, band, from, { it.startTime }, { it.endTime }) { it.count.toDouble() }?.toLong(),
                distanceMeters = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters
                    ?: if (DISTANCE in granted) total(client, DistanceRecord::class, today, band, from, { it.startTime }, { it.endTime }) { it.distance.inMeters } else null,
                floors = (r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
                    ?: if (FLOORS in granted) total(client, FloorsClimbedRecord::class, today, band, from, { it.startTime }, { it.endTime }) { it.floors } else null)?.toLong(),
                newestSteps = steps.second,
            )
        }
        if (heart != null) {
            val rows = client.aggregateGroupByDuration(
                AggregateGroupByDurationRequest(setOf(HeartRateRecord.BPM_AVG), today, Duration.ofMinutes(5), setOf(heart.first))
            )
            snap = snap.copy(
                heart = rows.mapNotNull { row -> row.result[HeartRateRecord.BPM_AVG]?.let { HeartRateSample(row.startTime, it.toInt()) } },
                newestHeart = heart.second,
                lastHeart = heartRecords.filter { it.metadata.dataOrigin == heart.first }.flatMap { it.samples }.maxByOrNull { it.time }
                    ?.let { HeartRateSample(it.time, it.beatsPerMinute.toInt()) },
            )
        }
        _snapshot.value = snap
        val found = steps != null || heart != null
        _status.value = LocalLaneStatus(if (found) LocalLaneStatus.State.On else LocalLaneStatus.State.NoBandData, unused.toList(), caloriesMissing = CALORIES !in granted)
    }

    private suspend fun <T : Record> newest(client: HealthConnectClient, type: kotlin.reflect.KClass<T>, range: TimeRangeFilter): List<T> =
        client.readRecords(ReadRecordsRequest(type, range, ascendingOrder = false, pageSize = 300)).records

    /** Sum over every record of one source, or null if it has none. */
    private suspend fun <T : Record> total(
        client: HealthConnectClient, type: kotlin.reflect.KClass<T>, range: TimeRangeFilter, source: DataOrigin,
        from: Instant, start: (T) -> Instant, end: (T) -> Instant, value: (T) -> Double,
    ): Double? {
        val parts = all(client, type, range, source).map { Lanes.partAfter(from, start(it), end(it), value(it)) }
        return if (parts.isEmpty()) null else parts.sum()
    }

    private suspend fun <T : Record> all(client: HealthConnectClient, type: kotlin.reflect.KClass<T>, range: TimeRangeFilter, source: DataOrigin): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, range, setOf(source), pageSize = 1000, pageToken = token))
            out += page.records
            token = page.pageToken
        } while (token != null)
        return out
    }

    companion object {
        private val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        private val DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
        private val FLOORS = HealthPermission.getReadPermission(FloorsClimbedRecord::class)
        private val HEART = HealthPermission.getReadPermission(HeartRateRecord::class)
        private val CALORIES = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)

        /** Read-only. The same five are declared in the manifest. */
        val PERMISSIONS: Set<String> = setOf(STEPS, DISTANCE, FLOORS, HEART, CALORIES)

        private val TYPES: Map<kotlin.reflect.KClass<out Record>, String> = mapOf(
            StepsRecord::class to STEPS, DistanceRecord::class to DISTANCE, FloorsClimbedRecord::class to FLOORS,
            HeartRateRecord::class to HEART, TotalCaloriesBurnedRecord::class to CALORIES,
        )

        // Holds the application context only.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: LocalLane? = null

        fun get(context: Context): LocalLane =
            instance ?: synchronized(this) { instance ?: LocalLane(context.applicationContext).also { instance = it } }
    }
}
