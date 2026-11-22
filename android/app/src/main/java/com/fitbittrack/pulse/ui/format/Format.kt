package com.fitbittrack.pulse.ui.format

import com.fitbittrack.pulse.ui.model.Units
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Plain-English number formatting used on every screen. Missing data shows as a dash. */
object Fmt {
    const val NONE = "–"
    // Built per call (not cached) so a language change while the app runs is picked up.
    private fun grouped() = NumberFormat.getIntegerInstance(Locale.getDefault())
    private fun time() = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    fun count(v: Long?): String = v?.let { grouped().format(it) } ?: NONE
    fun count(v: Int?): String = v?.let { grouped().format(it) } ?: NONE

    fun minutes(total: Int?): String {
        if (total == null) return NONE
        val h = total / 60
        val m = total % 60
        return if (h == 0) "${m}m" else "${h}h ${m.toString().padStart(2, '0')}m"
    }

    fun distance(meters: Double?, units: Units): Pair<String, String> {
        if (meters == null) return NONE to (if (units == Units.Metric) "km" else "mi")
        return if (units == Units.Metric) "%.1f".format(meters / 1000) to "km"
        else "%.1f".format(meters / 1609.344) to "mi"
    }

    fun oneDecimal(v: Double?): String = v?.let { "%.1f".format(it) } ?: NONE
    fun whole(v: Double?): String = v?.roundToInt()?.toString() ?: NONE

    fun signedTemp(deltaC: Double?): String =
        deltaC?.let { (if (it >= 0) "+" else "−") + "%.1f°".format(kotlin.math.abs(it)) } ?: NONE

    fun clock(t: LocalTime?): String = t?.format(time())?.lowercase() ?: NONE

    /** "just now", "12 min ago", "3 h ago", "2 days ago". */
    fun ago(instant: Instant?, now: Instant = Instant.now()): String {
        if (instant == null) return "never"
        val d = Duration.between(instant, now)
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} days ago"
        }
    }
}
