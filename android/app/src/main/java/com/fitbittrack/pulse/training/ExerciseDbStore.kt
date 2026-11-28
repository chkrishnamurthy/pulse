package com.fitbittrack.pulse.training

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** What the Animations page shows. */
data class EdbStatus(
    val listed: Int = 0,
    val saved: Int = 0,
    val bytes: Long = 0,
    /** Something running now, in words. Null when quiet. */
    val busy: String? = null,
    /** Downloading every animation: done so far, of all. */
    val all: Pair<Int, Int>? = null,
    val problem: String? = null,
    val pausedUntilMs: Long = 0,
)

/**
 * Keeps ExerciseDB on the phone: the list (asked once, checked every 30 days) and the animations, kept for good in
 * Pulse's own files, so Android never clears them and they work offline.
 */
class ExerciseDbStore(
    private val context: Context,
    private val dao: TrainingDao,
    private val repo: TrainingRepository,
    private val client: EdbClient = EdbClient(),
) {
    private val prefs = context.getSharedPreferences("exercisedb", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "exercisedb").apply { mkdirs() }
    private val listLock = Mutex()

    private val _status = MutableStateFlow(EdbStatus())
    val status: StateFlow<EdbStatus> = _status.asStateFlow()

    /** Goes up each time an animation is saved, so screens waiting for one look again. */
    private val _saves = MutableStateFlow(0)
    val saves: StateFlow<Int> = _saves.asStateFlow()

    fun file(edbId: String): File = File(dir, "$edbId.gif")
    fun saved(edbId: String): Boolean = file(edbId).let { it.isFile && it.length() > 0 }

    private fun pausedUntil(): Long = prefs.getLong(PAUSED, 0)
    private fun paused(): Boolean = pausedUntil() > System.currentTimeMillis()

    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return online() && !cm.isActiveNetworkMetered
    }

    suspend fun refreshStatus() {
        val files = dir.listFiles { f -> f.name.endsWith(".gif") }.orEmpty()
        _status.update { it.copy(listed = dao.edbCount(), saved = files.size, bytes = files.sumOf { f -> f.length() }, pausedUntilMs = pausedUntil()) }
    }

    private fun trouble(e: Exception) {
        if (e is EdbUnavailable) prefs.edit { putLong(PAUSED, System.currentTimeMillis() + EdbPace.REST_MS) }
        _status.update { it.copy(problem = e.message ?: "Could not reach ExerciseDB.", pausedUntilMs = pausedUntil()) }
    }

    /** Due when the list is not complete, or older than 30 days. */
    suspend fun listDue(): Boolean =
        prefs.getString(CURSOR, null) != null || dao.edbCount() == 0 || System.currentTimeMillis() - prefs.getLong(LISTED_AT, 0) > TimeUnit.DAYS.toMillis(30)

    /**
     * Gets the whole list, 25 at a time. Each page is saved as it comes, with where to go on, so a stop (no internet,
     * the app closed) picks up there next time. Then ties it to Pulse's exercises.
     */
    suspend fun syncList(force: Boolean = false): Boolean = listLock.withLock {
        if (!force && !listDue()) return@withLock true
        if (paused()) return@withLock false
        var cursor = prefs.getString(CURSOR, null)
        var got = 0
        try {
            while (true) {
                _status.update { it.copy(busy = "Getting the exercise list…", problem = null) }
                val page = client.page(cursor)
                val now = System.currentTimeMillis()
                dao.putEdb(page.items.map { it.entity(now) })
                got += page.items.size
                cursor = page.next
                prefs.edit { putString(CURSOR, cursor) }
                _status.update { it.copy(listed = maxOf(it.listed, got), busy = "Getting the exercise list… $got of ${page.total}") }
                if (cursor == null) break
            }
            prefs.edit { putLong(LISTED_AT, System.currentTimeMillis()); remove(CURSOR) }
            repo.mergeExerciseDb()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trouble(e)
            false
        } finally {
            _status.update { it.copy(busy = null) }
            refreshStatus()
        }
    }

    /** Saves one animation now, unless it is there already. False when it can't be had right now. */
    suspend fun ensure(edbId: String): Boolean = get(edbId) == Got.Saved

    private enum class Got { Saved, Missing, Stop }

    /** Saved; Missing (ExerciseDB has no file for it, so skip it); or Stop (offline, resting or trouble). */
    private suspend fun get(edbId: String): Got {
        if (saved(edbId)) return Got.Saved
        if (paused() || !online()) return Got.Stop
        return try {
            client.animation(edbId, file(edbId))
            _saves.update { it + 1 }
            Got.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (_: EdbMissing) {
            Got.Missing
        } catch (e: Exception) {
            trouble(e)
            Got.Stop
        }
    }

    /** Saves the animations of these Pulse exercises, one by one. Stops when told, when Wi-Fi goes ([wifiOnly]) or after trouble. */
    suspend fun saveFor(exerciseIds: Collection<String>, wifiOnly: Boolean, stop: () -> Boolean = { false }): Int {
        val edbIds = exerciseIds.mapNotNull { id -> EdbRules.edbIdOf(id) ?: dao.link(id)?.takeIf { it.how != LinkHow.Declined }?.edbId }.distinct()
        return saveEdb(edbIds, wifiOnly, stop, progress = false)
    }

    private suspend fun saveEdb(edbIds: List<String>, wifiOnly: Boolean, stop: () -> Boolean, progress: Boolean): Int {
        val missing = edbIds.filter { !saved(it) }
        var done = edbIds.size - missing.size
        var n = 0
        if (progress) _status.update { it.copy(all = done to edbIds.size) }
        try {
            for (id in missing) {
                if (stop() || paused() || (wifiOnly && !onWifi())) break
                val got = get(id)
                if (got == Got.Stop) break
                done++
                if (got == Got.Saved) n++
                val added = if (got == Got.Saved) file(id).length() else 0L
                if (progress) _status.update { it.copy(all = done to edbIds.size, saved = it.saved + if (got == Got.Saved) 1 else 0, bytes = it.bytes + added) }
            }
        } finally {
            // Also when stopped by the user, so the page shows the real count and space.
            if (progress) _status.update { it.copy(all = null) }
            withContext(NonCancellable) { refreshStatus() }
        }
        return n
    }

    /** Every animation. Wi-Fi only; can be stopped and picks up again later. */
    suspend fun saveAll(stop: () -> Boolean): Int = saveEdb(dao.edbIds().sorted(), wifiOnly = true, stop, progress = true)

    fun deleteAnimations() {
        dir.listFiles().orEmpty().forEach { it.delete() }
        _saves.update { it + 1 }
        _status.update { it.copy(saved = 0, bytes = 0) }
    }

    /** Lets Pulse try again now, after it stopped for the day. */
    fun tryAgainNow() {
        prefs.edit { remove(PAUSED) }
        _status.update { it.copy(problem = null, pausedUntilMs = 0) }
    }

    // ---------- background work ----------

    /** Runs the list when due and saves what is coming up, every day when there is internet. Safe to call at each start. */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniqueWork(FIRST, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<EdbWorker>().setConstraints(net).setInputData(workDataOf(JOB to DAILY)).build())
        wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<EdbWorker>(1, TimeUnit.DAYS).setConstraints(net).setInputData(workDataOf(JOB to DAILY)).build())
    }

    /** Saves the animations for these exercises soon, on Wi-Fi. */
    fun saveSoon() {
        val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(AHEAD, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<EdbWorker>().setConstraints(wifi).setInputData(workDataOf(JOB to AHEAD)).build())
    }

    fun startSaveAll() {
        val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        _status.update { it.copy(busy = "Waiting for Wi-Fi…") }
        WorkManager.getInstance(context).enqueueUniqueWork(ALL, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<EdbWorker>().setConstraints(wifi).setInputData(workDataOf(JOB to ALL)).build())
    }

    fun stopSaveAll() {
        WorkManager.getInstance(context).cancelUniqueWork(ALL)
        _status.update { it.copy(all = null, busy = null) }
    }

    internal suspend fun runJob(job: String, stopped: () -> Boolean) {
        when (job) {
            DAILY -> {
                syncList()
                if (onWifi()) saveFor(repo.exercisesAhead(), wifiOnly = true, stop = stopped)
            }
            AHEAD -> saveFor(repo.exercisesAhead(), wifiOnly = true, stop = stopped)
            ALL -> {
                _status.update { it.copy(busy = null) }
                syncList()
                saveAll(stopped)
            }
        }
    }

    companion object {
        private const val CURSOR = "cursor"
        private const val LISTED_AT = "listedAt"
        private const val PAUSED = "pausedUntil"
        internal const val JOB = "job"
        private const val FIRST = "edb-first"
        private const val DAILY = "edb-daily"
        private const val AHEAD = "edb-ahead"
        private const val ALL = "edb-all"
    }
}

class EdbWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val job = inputData.getString(ExerciseDbStore.JOB) ?: return Result.failure()
        TrainingGraph.exerciseDb(applicationContext).runJob(job) { isStopped }
        return Result.success()
    }
}
