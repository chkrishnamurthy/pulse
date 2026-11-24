package com.fitbittrack.pulse.air

import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The six steps of the US air quality index. */
enum class AirLevel(val label: String, val top: Int, val tip: String) {
    Good("Good", 50, "A good time to be outside."),
    Moderate("Moderate", 100, "Fine for most people. Take it easier outside if you are very sensitive to dirty air."),
    Sensitive("Unhealthy for sensitive groups", 150, "Children, older people, and people with asthma or heart trouble should do less hard exercise outside."),
    Unhealthy("Unhealthy", 200, "Exercise indoors if you can, and keep outdoor time short."),
    VeryUnhealthy("Very unhealthy", 300, "Avoid exercise outside. Keep windows closed."),
    Hazardous("Hazardous", 500, "Stay indoors and keep windows closed.");

    companion object {
        fun of(aqi: Int): AirLevel = entries.firstOrNull { aqi <= it.top } ?: Hazardous
    }
}

/** Where a reading comes from. The screen always says which one it is. */
sealed interface AirSource {
    data class Station(val name: String, val distanceMeters: Double, val credits: List<String>) : AirSource
    data object Estimate : AirSource
}

/** One air reading. Kept in memory only (WAQI does not allow stored copies). */
data class AirReading(
    val aqi: Int,
    /** When it was measured, or the hour the estimate is for. */
    val at: Instant,
    val source: AirSource,
    /** The pollutant that decides [aqi], in plain words. */
    val mainPollutant: String? = null,
    /** Amounts in µg/m³, estimate only: stations send index numbers, not amounts. */
    val pm25: Double? = null,
    val pm10: Double? = null,
    /** Set when a station could not be used for a reason the user can fix. */
    val note: String? = null,
) {
    val level: AirLevel get() = AirLevel.of(aqi)
}

data class Place(val lat: Double, val lon: Double)

object AirRules {
    const val STATION_MAX_METERS = 10_000.0
    val STATION_MAX_AGE: Duration = Duration.ofHours(3)
    /** A station clock may run a little fast. */
    val CLOCK_SLACK: Duration = Duration.ofMinutes(30)
    /** A reading this young is not asked for again. */
    val FRESH: Duration = Duration.ofMinutes(30)
    /** When a refresh fails, the last reading stays on screen this long. */
    val KEEP: Duration = Duration.ofHours(3)

    fun isRecent(at: Instant, now: Instant): Boolean =
        Duration.between(at, now) <= STATION_MAX_AGE && Duration.between(now, at) <= CLOCK_SLACK

    fun meters(a: Place, b: Place): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun pollutantName(code: String?): String? = when (code?.lowercase()) {
        "pm25", "pm2_5" -> "Fine dust (PM2.5)"
        "pm10" -> "Dust (PM10)"
        "o3", "ozone" -> "Ozone"
        "no2", "nitrogen_dioxide" -> "Nitrogen dioxide"
        "so2", "sulphur_dioxide" -> "Sulphur dioxide"
        "co", "carbon_monoxide" -> "Carbon monoxide"
        else -> null
    }
}
