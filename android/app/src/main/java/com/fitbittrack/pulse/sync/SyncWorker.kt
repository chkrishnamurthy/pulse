package com.fitbittrack.pulse.sync

import android.content.Context
import androidx.work.BackoffPolicy
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
import com.fitbittrack.pulse.PulseGraph
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Runs one sync in the background. WorkManager restarts it after reboots and retries on network trouble. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val backfill = inputData.getBoolean(KEY_BACKFILL, true)
        val gap = Duration.ofMinutes(inputData.getLong(KEY_HISTORY_GAP_MIN, 0))
        return when (PulseGraph.engine(applicationContext).sync(includeBackfill = backfill, historyGap = gap)) {
            // PARTIAL: the next regular sync tries the missing parts again.
            SyncEngine.Outcome.OK, SyncEngine.Outcome.PARTIAL, SyncEngine.Outcome.ABORTED -> Result.success()
            SyncEngine.Outcome.RETRY_LATER -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            SyncEngine.Outcome.NEEDS_SIGN_IN, SyncEngine.Outcome.FAILED -> Result.failure()
        }
    }

    companion object {
        const val KEY_BACKFILL = "backfill"
        const val KEY_HISTORY_GAP_MIN = "history_gap_min"
    }
}

/** Schedules the first history download, the regular sync (about every 15 minutes) and "sync now" from widgets. */
object SyncScheduler {
    private const val INITIAL = "pulse-sync-initial"
    private const val PERIODIC = "pulse-sync-periodic"
    private const val NOW = "pulse-sync-now"

    /** Android's shortest allowed interval. The exact moment is up to Android (Doze, app standby). */
    private const val INTERVAL_MINUTES = 15L

    /** An unfinished history download is tried again at most this often by the regular sync. */
    private const val HISTORY_GAP_MINUTES = 60L

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun start(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            INITIAL,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(SyncWorker.KEY_BACKFILL to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        ensurePeriodic(context)
    }

    /**
     * Makes sure the regular sync is scheduled with the current settings. Safe on every app start: UPDATE
     * keeps the timing and moves installs that still have the old 4-hour schedule to the new one.
     */
    fun ensurePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(network)
                // true: an interrupted first download is finished by a later periodic run.
                .setInputData(workDataOf(SyncWorker.KEY_BACKFILL to true, SyncWorker.KEY_HISTORY_GAP_MIN to HISTORY_GAP_MINUTES))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** One sync as soon as Android allows it. Tapping again while one is waiting does nothing extra. */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(SyncWorker.KEY_BACKFILL to false))
                .build(),
        )
    }

    fun stop(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(INITIAL)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(NOW)
    }
}
