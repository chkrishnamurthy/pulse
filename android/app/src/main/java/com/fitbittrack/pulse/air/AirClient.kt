package com.fitbittrack.pulse.air

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class AirException(message: String) : IOException(message)

/**
 * Outdoor air: a real measuring station when one is close and recent (WAQI), else a computer
 * estimate for the area (Open-Meteo). Only a rounded position is sent: no account, no health data.
 */
class AirClient(
    private val waqiKey: String,
    private val http: OkHttpClient = defaultClient(),
    private val waqiBase: String = "https://api.waqi.info/",
    private val meteoBase: String = "https://air-quality-api.open-meteo.com/",
    private val now: () -> Instant = Instant::now,
) {
    private class KeyRefused : IOException()

    suspend fun read(place: Place): AirReading {
        var note: String? = null
        if (waqiKey.isNotBlank()) {
            try {
                station(place)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: KeyRefused) {
                note = "The WAQI key was not accepted, so stations can't be used."
            } catch (_: Exception) {
                // The station service is down or sent something unreadable: use the estimate.
            }
        }
        return estimate(place).copy(note = note)
    }

    /** The nearest station, or null when there is none that is close and recent. */
    private suspend fun station(place: Place): AirReading? {
        val spot = "geo:${round(place.lat, 2)};${round(place.lon, 2)}"
        val url = waqiBase.toHttpUrl().newBuilder().addPathSegment("feed").addPathSegment(spot).addPathSegment("")
            .addQueryParameter("token", waqiKey).build()
        val root = get(url)
        if (root.text("status") != "ok") {
            if ("key" in root.text("data").orEmpty().lowercase()) throw KeyRefused()
            return null
        }
        val data = root["data"] as? JsonObject ?: return null
        // "-" when the station has no value right now.
        val aqi = data.number("aqi")?.roundToInt()?.takeIf { it in 0..2000 } ?: return null
        val city = data["city"] as? JsonObject ?: return null
        val geo = (city["geo"] as? JsonArray)?.mapNotNull { it.asNumber() }?.takeIf { it.size == 2 } ?: return null
        val distance = AirRules.meters(place, Place(geo[0], geo[1]))
        if (distance > AirRules.STATION_MAX_METERS) return null
        val at = (data["time"] as? JsonObject)?.text("iso")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return null
        if (!AirRules.isRecent(at, now())) return null
        val credits = (data["attributions"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("name") }.orEmpty()
        return AirReading(
            aqi = aqi, at = at,
            source = AirSource.Station(city.text("name")?.takeIf { it.isNotBlank() } ?: "Nearby station", distance, credits),
            mainPollutant = AirRules.pollutantName(data.text("dominentpol")), // spelled like this by WAQI
        )
    }

    private suspend fun estimate(place: Place): AirReading {
        val url = meteoBase.toHttpUrl().newBuilder().addPathSegments("v1/air-quality")
            // The map squares are 0.1° or larger, so a closer position would change nothing.
            .addQueryParameter("latitude", round(place.lat, 1))
            .addQueryParameter("longitude", round(place.lon, 1))
            .addQueryParameter("current", (listOf("us_aqi", "pm2_5", "pm10") + PARTS.map { "us_aqi_$it" }).joinToString(","))
            .addQueryParameter("timeformat", "unixtime")
            .build()
        val c = get(url)["current"] as? JsonObject ?: throw AirException("The air quality service sent no reading.")
        val aqi = c.number("us_aqi")?.roundToInt()?.takeIf { it >= 0 } ?: throw AirException("There is no air reading for this area.")
        val at = c.number("time")?.let { Instant.ofEpochSecond(it.toLong()) } ?: throw AirException("The air quality service sent no time.")
        val main = PARTS.mapNotNull { p -> c.number("us_aqi_$p")?.let { p to it } }.maxByOrNull { it.second }?.first
        return AirReading(aqi, at, AirSource.Estimate, AirRules.pollutantName(main), c.number("pm2_5"), c.number("pm10"))
    }

    private suspend fun get(url: HttpUrl): JsonObject {
        val body = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).await()
        return try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: IllegalArgumentException) {
            throw AirException("The air quality service sent something unreadable.")
        }
    }

    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            // The web address holds the key and the position, so it never goes into a message.
            override fun onFailure(call: Call, e: IOException) =
                cont.resumeWithException(AirException("Could not reach the air quality service. Check your internet."))

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { if (it.isSuccessful) it.body.string() else throw AirException("The air quality service had a problem (${it.code}).") }
                }
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it as? AirException ?: AirException("The air quality reply was cut off.")) })
            }
        })
    }

    companion object {
        private val PARTS = listOf("pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

        /** Always a dot, in every language. Never "-0.0". */
        internal fun round(v: Double, decimals: Int): String =
            String.format(Locale.ROOT, "%.${decimals}f", v).let { if (it.toDouble() == 0.0) it.removePrefix("-") else it }

        private fun JsonElement.asNumber(): Double? = (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        private fun JsonObject.number(key: String): Double? = this[key]?.asNumber()
        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
