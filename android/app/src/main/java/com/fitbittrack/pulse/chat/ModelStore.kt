package com.fitbittrack.pulse.chat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The one file the AI needs. The address is fixed to one exact version, and the fingerprint must match it. */
data class ModelFile(val url: String, val name: String, val size: Long, val sha256: String)

val GEMMA = ModelFile(
    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/2e12bfd869b2962984f97a1f2a26807316d534cc/gemma-4-E2B-it.litertlm",
    name = "gemma-4-E2B-it.litertlm",
    size = 2_588_147_712L,
    sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
)

sealed interface ModelState {
    data class Unsupported(val reason: String) : ModelState
    data class Absent(val problem: String? = null) : ModelState
    /** [waiting] = not running right now (no Wi-Fi yet, or Android paused it); it continues by itself. */
    data class Downloading(val bytes: Long, val total: Long, val waiting: Boolean) : ModelState
    data object Checking : ModelState
    data object Ready : ModelState
}

/** Thrown when trying again won't help. */
class ModelProblem(message: String) : Exception(message)

/** Downloads one file: continues where it stopped, and accepts it only if the fingerprint matches. */
class ModelDownloader(private val http: OkHttpClient, private val model: ModelFile) {

    suspend fun download(part: File, done: File, freeBytes: () -> Long, onBytes: (Long) -> Unit, onChecking: () -> Unit = {}) = withContext(Dispatchers.IO) {
        if (done.length() == model.size) return@withContext
        part.parentFile?.mkdirs()
        if (part.length() > model.size) part.delete()
        val need = model.size - part.length() + SPARE
        if (freeBytes() < need) throw ModelProblem("Not enough free space. Free about ${(need / 1e9).toInt() + 1} GB and try again.")
        if (part.length() < model.size) fetch(part, onBytes)
        onChecking()
        if (sha256(part) != model.sha256) {
            part.delete()
            throw ModelProblem("The downloaded file was damaged, so it was deleted. Please download it again.")
        }
        done.delete()
        if (!part.renameTo(done)) throw IOException("Could not save the file.")
    }

    private suspend fun fetch(part: File, onBytes: (Long) -> Unit) {
        val have = part.length()
        val request = Request.Builder().url(model.url).header("Accept-Encoding", "identity")
            .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(request).execute().use { r ->
            when {
                r.code == 416 -> { part.delete(); throw IOException("Start again.") }
                !r.isSuccessful -> throw if (r.code == 404 || r.code == 403 || r.code == 401) ModelProblem("The AI file is not available right now (${r.code}).") else IOException("HTTP ${r.code}")
            }
            // A server that ignores "continue from here" sends the file from the start.
            val append = r.code == 206
            var total = if (append) have else 0L
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(1 shl 16)
                val input = r.body.byteStream()
                var reported = total
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    if (total > model.size) { part.delete(); throw ModelProblem("The AI file has the wrong size.") }
                    if (total - reported >= STEP) { reported = total; onBytes(total) }
                }
            }
            onBytes(total)
            if (total < model.size) throw IOException("The connection ended early.")
        }
    }

    private suspend fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = s.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SPARE = 500_000_000L
        const val STEP = 4_000_000L
    }
}

/** Where the AI file is, and how far its download got. One per app. */
class ModelStore private constructor(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "models")
    val file = File(dir, GEMMA.name)
    private val part = File(dir, GEMMA.name + ".part")

    private val _state = MutableStateFlow(now())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private fun now(): ModelState = when {
        file.length() == GEMMA.size -> ModelState.Ready
        DeviceCheck.problem(context) != null -> ModelState.Unsupported(DeviceCheck.problem(context)!!)
        part.exists() -> ModelState.Downloading(part.length(), GEMMA.size, waiting = true)
        else -> ModelState.Absent()
    }

    fun refresh() { _state.value = now() }

    fun start(wifiOnly: Boolean) {
        if (_state.value is ModelState.Ready || _state.value is ModelState.Unsupported) return
        _state.value = ModelState.Downloading(part.length(), GEMMA.size, waiting = true)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ModelWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** Stops the download and deletes the file. Health data is never touched. */
    fun delete() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        part.delete()
        file.delete()
        _state.value = now()
    }

    /** Runs inside [ModelWorker]. Android may stop it after about ten minutes; the next run continues. */
    internal suspend fun run() {
        try {
            downloader.download(
                part, file, freeBytes = { dir.apply { mkdirs() }.usableSpace },
                onBytes = { _state.value = ModelState.Downloading(it, GEMMA.size, waiting = false) },
                onChecking = { _state.value = ModelState.Checking },
            )
            _state.value = ModelState.Ready
        } catch (e: ModelProblem) {
            _state.value = ModelState.Absent(e.message)
            throw e
        } catch (e: CancellationException) {
            // Cancelled by the user (files already deleted) or paused by Android (the part file is kept).
            _state.value = now()
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Downloading(part.length(), GEMMA.size, waiting = true)
            throw e
        }
    }

    private val downloader by lazy {
        ModelDownloader(OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(), GEMMA)
    }

    companion object {
        private const val WORK = "pulse-ai-model"

        @android.annotation.SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: ModelStore? = null
        fun get(context: Context): ModelStore =
            instance ?: synchronized(this) { instance ?: ModelStore(context.applicationContext).also { instance = it } }
    }
}

class ModelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ModelStore.get(applicationContext).run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ModelProblem) {
        Result.failure()
    } catch (_: Exception) {
        Result.retry()
    }
}
