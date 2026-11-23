package com.fitbittrack.pulse.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An HTTP error from the Google Health API, with a plain-English [message]. */
class HealthApiException(val httpCode: Int?, val body: String, message: String) : IOException(message) {
    val isAccountNotLinked: Boolean get() = "ACCOUNT_NOT_LINKED" in body
    val isUnauthorized: Boolean get() = httpCode == 401
    /** Worth trying again later (network trouble, rate limit, Google server trouble). */
    val isTransient: Boolean get() = httpCode == null || httpCode == 429 || httpCode >= 500
}

/**
 * Low-level Google Health API client. Paths, field names and limits are from
 * developers.google.com/health (endpoints, filters, REST reference v4).
 *
 * It retries rate limits (429) and server errors (500/503/504) with growing waits,
 * as Google recommends. It never stores the access token.
 */
class HealthApi(
    private val http: OkHttpClient = defaultClient(),
    private val baseUrl: String = BASE,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 5,
) {

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Error(val httpCode: Int?, val message: String) : Result<Nothing>
    }

    data class Identity(val hasHealthUserId: Boolean, val hasLegacyUserId: Boolean)

    /** GET /v4/users/me/identity — the simplest call, and the one that reveals ACCOUNT_NOT_LINKED. */
    suspend fun getIdentity(accessToken: String): Result<Identity> = try {
        val obj = get(accessToken, "v4/users/me/identity")
        Result.Ok(
            Identity(
                hasHealthUserId = !obj["healthUserId"]?.jsonPrimitive?.content.isNullOrBlank(),
                hasLegacyUserId = !obj["legacyUserId"]?.jsonPrimitive?.content.isNullOrBlank(),
            )
        )
    } catch (e: HealthApiException) {
        Result.Error(e.httpCode, e.message ?: "Request failed.")
    }

    /** GET [path] (relative to https://health.googleapis.com/) with optional query parameters. */
    suspend fun get(accessToken: String, path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return execute(Request.Builder().url(url).get().auth(accessToken).build())
    }

    /** POST a JSON [body] to [path]. Used by rollUp and dailyRollUp. */
    suspend fun post(accessToken: String, path: String, body: JsonObject): JsonObject {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()
        val requestBody = body.toString().toRequestBody(JSON_TYPE)
        return execute(Request.Builder().url(url).post(requestBody).auth(accessToken).build())
    }

    private fun Request.Builder.auth(token: String) =
        header("Authorization", "Bearer $token").header("Accept", "application/json")

    private suspend fun execute(request: Request): JsonObject {
        var attempt = 0
        while (true) {
            attempt++
            val failure: HealthApiException = try {
                val (code, body, retryAfter) = withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { r ->
                        Triple(r.code, r.body.string(), r.header("Retry-After")?.toLongOrNull())
                    }
                }
                if (code in 200..299) {
                    return if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body).jsonObject
                }
                val e = HealthApiException(code, body, explain(code, body))
                if (!e.isTransient || attempt >= maxAttempts) throw e
                sleeper(retryAfter?.coerceIn(1, 60)?.times(1000) ?: backoffMillis(attempt))
                continue
            } catch (e: HealthApiException) {
                throw e
            } catch (e: IOException) {
                HealthApiException(null, "", "Network problem: ${e.message ?: "no connection"}")
            }
            if (attempt >= maxAttempts) throw failure
            sleeper(backoffMillis(attempt))
        }
    }

    companion object {
        const val BASE = "https://health.googleapis.com/"
        private val JSON_TYPE = "application/json".toMediaType()
        internal val json = Json { ignoreUnknownKeys = true }

        /** 1s, 2s, 4s, 8s … capped at 30s. */
        fun backoffMillis(attempt: Int): Long = (1000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun explain(code: Int, body: String): String = when {
            "ACCOUNT_NOT_LINKED" in body ->
                "This Google account is not linked to Google Health yet. Open the Google Health app with your Fitbit account and link it (Phase 2, Step 0)."
            code == 401 -> "Access token expired or invalid. Sign in again."
            code == 403 -> "Permission refused. Check: Google Health API is enabled, you signed in with the Fitbit account, it is a test user, and you allowed all 5 permissions."
            code == 400 -> "Google did not accept the request (400)."
            code == 429 -> "Too many requests. Wait a minute and try again."
            code >= 500 -> "Google's server had a problem ($code). Try again shortly."
            else -> "Request failed ($code)."
        }
    }
}
