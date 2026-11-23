package com.fitbittrack.pulse.direct

import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.HeartRateSample
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/*
 * Pulse has three lanes for data:
 *  1. Live heart rate straight from the band (Bluetooth, see LiveHeart). Shown live, never stored.
 *  2. Health Connect on this phone (see LocalLane): today's numbers without an internet trip. In memory only.
 *  3. Google's servers (SyncEngine): everything, and the only lane that is saved. It stays the main record.
 *
 * This file holds the rules for joining lane 2 and lane 3. Plain functions, no Android.
 */

/** Today's numbers as Health Connect has them right now. */
data class LocalSnapshot(
    val day: LocalDate,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val floors: Long? = null,
    /** 5-minute averages, oldest first. */
    val heart: List<HeartRateSample> = emptyList(),
    /** How fresh the data is: end of the newest record, not the time we looked. */
    val newestSteps: Instant? = null,
    val newestHeart: Instant? = null,
)

object Lanes {

    /**
     * Today's totals only ever grow during a day, so the bigger number is the fresher one.
     * Other days, and values Health Connect doesn't have, stay exactly as Google's servers sent them.
     */
    fun mergeDay(cloud: DaySummary?, local: LocalSnapshot?, date: LocalDate): DaySummary? {
        if (local == null || local.day != date) return cloud
        if (cloud == null && local.steps == null && local.distanceMeters == null && local.floors == null) return null
        val base = cloud ?: DaySummary(date)
        return base.copy(
            steps = larger(base.steps, local.steps),
            distanceMeters = larger(base.distanceMeters, local.distanceMeters),
            floors = larger(base.floors, local.floors),
        )
    }

    fun mergeDays(cloud: List<DaySummary>, local: LocalSnapshot?, from: LocalDate, to: LocalDate): List<DaySummary> {
        val day = local?.day ?: return cloud
        if (day < from || day > to) return cloud
        val merged = mergeDay(cloud.firstOrNull { it.date == day }, local, day) ?: return cloud
        return (cloud.filter { it.date != day } + merged).sortedBy { it.date }
    }

    /** Google's samples, plus Health Connect's newer ones. */
    fun mergeHeart(cloud: List<HeartRateSample>, local: LocalSnapshot?, date: LocalDate): List<HeartRateSample> {
        if (local == null || local.day != date) return cloud
        val last = cloud.maxOfOrNull { it.time }
        return cloud + local.heart.filter { last == null || it.time > last }
    }

    /** The share of a record that lies after [from] (a record can start just before midnight). */
    fun partAfter(from: Instant, start: Instant, end: Instant, value: Double): Double {
        if (start >= from || end <= start) return value
        if (end <= from) return 0.0
        return value * Duration.between(from, end).toMillis() / Duration.between(start, end).toMillis()
    }

    private fun <T : Comparable<T>> larger(a: T?, b: T?): T? = if (a == null) b else if (b == null) a else maxOf(a, b)

    /**
     * Is this Health Connect source the Fitbit band? Other sources (the phone's own step counter, other apps)
     * count different steps, so mixing them in would make the total wrong.
     */
    fun isBandSource(packageName: String, manufacturer: String? = null, model: String? = null): Boolean =
        // Google Health is the former Fitbit app and keeps its app ID. Exact prefix: Pulse's own ID also contains "fitbit".
        packageName.startsWith("com.fitbit.") || listOf(manufacturer, model).any { it != null && it.contains("fitbit", ignoreCase = true) }
}

/** The standard Bluetooth "Heart Rate Measurement" message (characteristic 0x2A37). */
object HeartRateProfile {
    /** Beats per minute, or null if the message is broken or the band has no reading. */
    fun bpm(value: ByteArray): Int? {
        if (value.size < 2) return null
        val wide = value[0].toInt() and 0x01 != 0
        if (wide && value.size < 3) return null
        val bpm = if (wide) (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8) else value[1].toInt() and 0xFF
        return bpm.takeIf { it in 20..250 }
    }

    /** False only when the band says it is not touching skin. */
    fun onSkin(value: ByteArray): Boolean {
        val flags = value.firstOrNull()?.toInt() ?: return true
        val supported = flags and 0x04 != 0
        return !supported || flags and 0x02 != 0
    }
}
