package com.fitbittrack.pulse.direct

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Live steps: the band's own count, plus the steps this phone felt since that count was made.
 * An estimate, shown while Pulse is open and never saved. Goals, calendar and history use the band's count only.
 */

/** The band's steps for [day], counted up to [upTo]. */
data class BandTotal(val day: LocalDate, val steps: Long, val upTo: Instant)

/** Steps the phone counted in one stretch of time. */
data class StepPart(val start: Instant, val end: Instant, val count: Long)

/** [steps] = band's count + [added] phone steps. */
data class LiveSteps(val day: LocalDate, val steps: Long, val added: Long)

/** The phone's step counter over the last hours: what it said, and when. */
class StepTrail {
    private val points = ArrayDeque<Pair<Instant, Long>>()

    val start: Instant? get() = points.firstOrNull()?.first
    val last: Long? get() = points.lastOrNull()?.second

    fun add(at: Instant, total: Long) {
        val before = points.lastOrNull()
        // The quick sensor can be a step ahead of the exact one. Steps never go back.
        val t = maxOf(total, before?.second ?: total)
        val time = if (before != null && at < before.first) before.first else at
        // A point every few seconds is enough.
        if (points.size >= 2 && Duration.between(points[points.size - 2].first, time) < SPACING) points.removeLast()
        points.addLast(time to t)
        while (points.size > 1 && Duration.between(points.first().first, time) > KEEP) points.removeFirst()
    }

    /** Steps after [t]. Between two points (Pulse was closed) they are spread evenly. */
    fun since(t: Instant): Long {
        val end = points.lastOrNull() ?: return 0
        if (t >= end.first) return 0
        val i = points.indexOfLast { it.first <= t }
        if (i < 0) return end.second - points.first().second
        val (at, total) = points[i]
        val (nextAt, next) = points[i + 1]
        val span = Duration.between(at, nextAt).toMillis()
        val share = if (span <= 0) 1.0 else Duration.between(at, t).toMillis().toDouble() / span
        return end.second - (total + ((next - total) * share).toLong())
    }

    private companion object {
        val SPACING: Duration = Duration.ofSeconds(5)
        val KEEP: Duration = Duration.ofHours(3)
    }
}

/**
 * Which band count to add to. When Google says the band synced but the count is still the old one, the new
 * time is not believed at once: the steps may just not have arrived yet, and the number would jump back.
 */
class BandAnchor(private val grace: Duration = Duration.ofSeconds(60)) {
    private var held: BandTotal? = null
    private var newer: Pair<Instant, Instant>? = null // the later time, and when it was first seen

    fun take(new: BandTotal?, now: Instant): BandTotal? {
        val h = held
        if (new == null || h == null || new.day != h.day || new.steps != h.steps) {
            held = new
            newer = null
        } else if (new.upTo > h.upTo) {
            if (newer?.first != new.upTo) newer = new.upTo to now
            if (Duration.between(newer!!.second, now) >= grace) {
                held = new
                newer = null
            }
        }
        return held
    }
}

object LiveStepMath {

    /** A band that has not synced for this long is probably off the wrist. Then Pulse does not guess. */
    val MAX_AGE: Duration = Duration.ofHours(2)

    /** The newest band count, from Google's servers or from Health Connect on this phone. */
    fun bandTotal(day: LocalDate, cloudSteps: Long?, cloudUpTo: Instant?, local: LocalSnapshot?): BandTotal? {
        val l = local?.takeIf { it.day == day }
        val steps = l?.steps
        val upTo = l?.newestSteps
        return when {
            steps != null && upTo != null && steps > (cloudSteps ?: -1) -> BandTotal(day, steps, upTo)
            cloudUpTo == null -> if (steps != null && upTo != null && steps == cloudSteps) BandTotal(day, steps, upTo) else null
            steps == cloudSteps && upTo != null -> BandTotal(day, cloudSteps ?: 0, maxOf(cloudUpTo, upTo))
            // A day without steps yet counts from zero.
            else -> BandTotal(day, cloudSteps ?: 0, cloudUpTo)
        }
    }

    /** Phone steps the band's count can't have yet. [parts]: Health Connect's own phone steps, for before Pulse was open. */
    fun added(band: BandTotal, trail: StepTrail, parts: List<StepPart>, now: Instant, zone: ZoneId): Long {
        if (Duration.between(band.upTo, now) > MAX_AGE) return 0
        val start = trail.start ?: return 0
        val from = maxOf(band.upTo, band.day.atStartOfDay(zone).toInstant())
        val before = if (start > from) parts.sumOf { overlap(it, from, start) }.toLong() else 0
        return before + trail.since(maxOf(from, start))
    }

    fun estimate(band: BandTotal?, trail: StepTrail, parts: List<StepPart>, now: Instant, zone: ZoneId): LiveSteps? {
        if (band == null || trail.start == null) return null
        val n = added(band, trail, parts, now, zone)
        return LiveSteps(band.day, band.steps + n, n)
    }

    private fun overlap(p: StepPart, from: Instant, to: Instant): Double {
        val a = maxOf(p.start, from)
        val b = minOf(p.end, to)
        if (b <= a) return 0.0
        return p.count.toDouble() * Duration.between(a, b).toMillis() / Duration.between(p.start, p.end).toMillis().coerceAtLeast(1)
    }
}

/** The phone's own step sensor. Listens only between [start] and [stop]. Main thread. */
class PhoneSteps(private val context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val counter: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val detector: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val supported: Boolean get() = counter != null

    val allowed: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    val trail = StepTrail()

    /** Goes up with every step, so watchers look at [trail] again. */
    private val _steps = MutableStateFlow(0L)
    val steps: StateFlow<Long> = _steps.asStateFlow()

    private var base: Long? = null
    private var baseAt = 0L
    private var extra = 0L

    fun start(): Boolean {
        val s = sensors ?: return false
        if (counter == null || !allowed) return false
        stop()
        if (!s.registerListener(this, counter, SensorManager.SENSOR_DELAY_UI, 0)) return false
        detector?.let { s.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, 0) }
        return true
    }

    fun stop() {
        sensors?.unregisterListener(this)
        base = null
        extra = 0
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            // Exact, but can be 10 seconds late.
            base = e.values[0].toLong()
            baseAt = e.timestamp
            extra = 0
        } else {
            // One event per step, quicker. Counts until the exact number comes.
            if (base == null || e.timestamp <= baseAt) return
            extra++
        }
        val late = (SystemClock.elapsedRealtimeNanos() - e.timestamp).coerceIn(0L, 60_000_000_000L)
        trail.add(Instant.now().minusNanos(late), (base ?: return) + extra)
        _steps.value = trail.last ?: 0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
