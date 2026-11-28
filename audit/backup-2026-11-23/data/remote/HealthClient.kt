package com.fitbittrack.pulse.data.remote

import com.fitbittrack.pulse.data.HealthApi
import com.fitbittrack.pulse.data.HealthApiException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate

/** Thrown when Google needs the user to sign in again (consent screen). */
class NeedsSignInException(message: String = "Please sign in again.") : Exception(message)

/** Gives the data layer an access token. The token is never stored on disk. */
interface TokenProvider {
    /** A valid access token, or throws [NeedsSignInException]. */
    suspend fun accessToken(): String
    /** Forget the cached token (called after a 401). */
    fun invalidate()
}

/** One value from rollUp / dailyRollUp: the window and the type's value object. */
data class RollupPoint(val start: Instant?, val end: Instant?, val civilDate: LocalDate?, val value: JsonObject)

/**
 * Typed calls on top of [HealthApi]: list (with paging), rollUp, dailyRollUp, pairedDevices.
 * Handles one automatic token refresh on 401.
 */
class HealthClient(private val api: HealthApi, private val tokens: TokenProvider) {

    private suspend fun <T> withToken(block: suspend (String) -> T): T = try {
        block(tokens.accessToken())
    } catch (e: HealthApiException) {
        if (!e.isUnauthorized) throw e
        tokens.invalidate()
        block(tokens.accessToken())
    }

    /**
     * GET users/me/dataTypes/{type}/dataPoints, following nextPageToken.
     * Results come newest first. [maxPages] guards against runaway loops.
     */
    suspend fun list(
        dataType: String,
        filter: String? = null,
        pageSize: Int = 1000,
        maxPages: Int = 400,
        onPage: suspend () -> Unit = {},
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var token: String? = null
        var pages = 0
        do {
            val query = buildMap {
                put("pageSize", pageSize.toString())
                if (filter != null) put("filter", filter)
                token?.let { put("pageToken", it) }
            }
            val page = withToken { api.get(it, "v4/users/me/dataTypes/$dataType/dataPoints", query) }
            page["dataPoints"]?.jsonArray?.forEach { out += it.jsonObject }
            token = page.str("nextPageToken")?.takeIf { it.isNotBlank() }
            pages++
            onPage()
        } while (token != null && pages < maxPages)
        return out
    }

    /** POST …/dataPoints:rollUp over a physical time range with fixed windows (for example 3600s). */
    suspend fun rollUp(
        dataType: String,
        start: Instant,
        end: Instant,
        windowSeconds: Long,
        valueKey: String,
    ): List<RollupPoint> {
        val out = mutableListOf<RollupPoint>()
        var token: String? = null
        do {
            val body = buildJsonObject {
                putJsonObject("range") {
                    put("startTime", start.toString())
                    put("endTime", end.toString())
                }
                put("windowSize", "${windowSeconds}s")
                put("pageSize", 10000)
                if (token != null) put("pageToken", token)
            }
            val page = withToken { api.post(it, "v4/users/me/dataTypes/$dataType/dataPoints:rollUp", body) }
            page.arr("rollupDataPoints")?.forEach { el ->
                val o = el.jsonObject
                val value = o.obj(valueKey) ?: return@forEach
                out += RollupPoint(o.instant("startTime"), o.instant("endTime"), null, value)
            }
            token = page.str("nextPageToken")?.takeIf { it.isNotBlank() }
        } while (token != null)
        return out
    }

    /**
     * POST …/dataPoints:dailyRollUp: one value per local day from [from] up to (not including) [toExclusive].
     * The caller keeps each request within Google's range limit (90 days, or 14 for some types).
     */
    suspend fun dailyRollUp(
        dataType: String,
        from: LocalDate,
        toExclusive: LocalDate,
        valueKey: String,
    ): List<RollupPoint> {
        val days = (toExclusive.toEpochDay() - from.toEpochDay()).toInt().coerceAtLeast(1)
        val body = buildJsonObject {
            putJsonObject("range") {
                put("start", civil(from))
                put("end", civil(toExclusive))
            }
            put("windowSizeDays", 1)
            put("pageSize", days.coerceAtMost(90))
        }
        val page = withToken { api.post(it, "v4/users/me/dataTypes/$dataType/dataPoints:dailyRollUp", body) }
        return page.arr("rollupDataPoints").orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val value = o.obj(valueKey) ?: return@mapNotNull null
            RollupPoint(null, null, o.obj("civilStartTime")?.civilDate(), value)
        }
    }

    /** GET users/me/pairedDevices. Needs the settings.readonly scope. */
    suspend fun pairedDevices(): List<JsonObject> =
        withToken { api.get(it, "v4/users/me/pairedDevices", mapOf("pageSize" to "100")) }
            .arr("pairedDevices").orEmpty().map { it.jsonObject }

    private fun civil(date: LocalDate) = buildJsonObject {
        putJsonObject("date") {
            put("year", date.year); put("month", date.monthValue); put("day", date.dayOfMonth)
        }
        putJsonObject("time") {
            put("hours", 0); put("minutes", 0); put("seconds", 0); put("nanos", 0)
        }
    }

}
