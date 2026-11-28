package com.fitbittrack.pulse.training

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** ExerciseDB said no for now, or could not be reached after a few tries. Try again later. */
class EdbUnavailable(message: String) : IOException(message)

/** ExerciseDB has no usable file for this one (gone, or not a picture). Skip it; asking again will not help. */
class EdbMissing(message: String) : IOException(message)

/**
 * Talks to ExerciseDB's free API. Only one request at a time for the whole app, at least [EdbPace.GAP_MS] apart.
 * After trouble it waits longer each time and gives up after [EdbPace.TRIES] tries.
 */
class EdbClient(
    private val base: String = BASE,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    @Serializable
    private data class Meta(val total: Int = 0, val hasNextPage: Boolean = false, val nextCursor: String? = null)

    @Serializable
    private data class Page(val success: Boolean = true, val meta: Meta = Meta(), val data: List<EdbItem> = emptyList())

    data class PageResult(val items: List<EdbItem>, val total: Int, val next: String?)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val lock = Mutex()
    private var lastMs = 0L

    /** One page of 25, after [cursor] (an exercise id), or from the start. */
    suspend fun page(cursor: String?): PageResult {
        val url = "$base/api/v1/exercises?limit=$PAGE" + (cursor?.let { "&after=$it" } ?: "")
        val body = call(url) { it.body.string() }
        val p = json.decodeFromString(Page.serializer(), body)
        return PageResult(p.data, p.meta.total, p.meta.nextCursor.takeIf { p.meta.hasNextPage })
    }

    /** Saves the animation of [edbId] to [to]. Written to a side file first, so a cut-off download never looks finished. */
    suspend fun animation(edbId: String, to: File) {
        val tmp = File(to.parentFile, to.name + ".part")
        call("$media/$edbId.gif") { r ->
            val type = r.header("Content-Type").orEmpty()
            if (!type.startsWith("image/")) throw EdbMissing("Not a picture: $type")
            tmp.outputStream().use { out -> r.body.byteStream().use { it.copyTo(out) } }
        }
        if (tmp.length() < 64 || !tmp.renameTo(to)) { tmp.delete(); throw IOException("Could not save $edbId") }
    }

    private val media = if (base == BASE) MEDIA else "$base/media"

    private suspend fun <T> call(url: String, read: (okhttp3.Response) -> T): T = lock.withLock {
        var failures = 0
        while (true) {
            sleep(EdbPace.gap(lastMs, now()))
            lastMs = now()
            val outcome: Result<T> = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url).header("User-Agent", "Pulse personal app").build()).execute().use { r ->
                        if (!r.isSuccessful) throw Http(r.code, r.header("Retry-After")?.toLongOrNull())
                        read(r)
                    }
                }
            }
            outcome.onSuccess { return@withLock it }
            val e = outcome.exceptionOrNull()!!
            if (e is EdbMissing) throw e
            if (e is Http && (e.code == 404 || e.code == 410)) throw EdbMissing("ExerciseDB has no file here (${e.code})")
            if (e is Http && !EdbPace.retry(e.code)) throw IOException("ExerciseDB answered ${e.code}")
            failures++
            val wait = EdbPace.wait(failures, (e as? Http)?.retryAfter) ?: throw EdbUnavailable("ExerciseDB is busy or out of reach. Pulse tries again later.")
            sleep(wait)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private class Http(val code: Int, val retryAfter: Long?) : IOException("HTTP $code")

    companion object {
        const val BASE = "https://oss.exercisedb.dev"
        const val MEDIA = "https://static.exercisedb.dev/media"
        const val PAGE = 25
    }
}
