package com.fitbittrack.pulse.training

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/**
 * Health Connect for training. Off until the user switches it on. Writes only finished workouts that Pulse itself recorded
 * (name, start and end; no sets), and reads only body weight. Imported workouts are never written: their times are not known.
 */
class TrainingHealth(private val context: Context) {

    fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private suspend fun granted(): Set<String> = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    suspend fun canWrite(): Boolean = quiet(false) { available() && WRITE in granted() }

    /** Writes a finished workout. The same one again replaces the first, because the id is the same. */
    suspend fun write(s: SessionEntity): Boolean = quiet(false) {
        if (s.state != SessionState.Finished || s.source != "pulse" || s.endMs == null || s.timeUnsure) return@quiet false
        if (!TrainingGraph.prefs(context).settings.first().healthConnect || !canWrite()) return@quiet false
        val zone = runCatching { ZoneId.of(s.zone) }.getOrDefault(ZoneId.systemDefault())
        val start = Instant.ofEpochMilli(s.startMs)
        val end = Instant.ofEpochMilli(s.endMs)
        HealthConnectClient.getOrCreate(context).insertRecords(
            listOf(
                ExerciseSessionRecord(
                    startTime = start, startZoneOffset = zone.rules.getOffset(start), endTime = end, endZoneOffset = zone.rules.getOffset(end),
                    metadata = Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE), clientRecordId = "pulse-${s.id}", clientRecordVersion = s.changedMs),
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, title = s.title,
                ),
            ),
        )
        true
    }

    /** Takes a deleted workout out again. Only ever touches Pulse's own record. */
    suspend fun remove(sessionId: String) {
        quiet(Unit) {
            if (canWrite()) HealthConnectClient.getOrCreate(context).deleteRecords(ExerciseSessionRecord::class, emptyList(), listOf("pulse-$sessionId"))
        }
    }

    /** The newest body weight of the last year, in grams. */
    suspend fun bodyWeight(): Pair<Instant, Int>? = quiet(null) {
        if (!available() || READ_WEIGHT !in granted()) return@quiet null
        val r = HealthConnectClient.getOrCreate(context).readRecords(
            ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.after(Instant.now().minusSeconds(365L * 86_400)), ascendingOrder = false, pageSize = 1),
        ).records.firstOrNull() ?: return@quiet null
        r.time to (r.weight.inKilograms * 1000).toInt()
    }

    private suspend fun <T> quiet(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    companion object {
        val WRITE = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)
        val PERMISSIONS = setOf(WRITE, READ_WEIGHT)
    }
}
