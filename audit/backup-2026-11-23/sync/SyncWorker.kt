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
import java.util.concurrent.TimeUnit

/** Runs one sync in the background. WorkManager restarts it after reboots and retries on network trouble. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val backfill = inputData.getBoolean(KEY_BACKFILL, true)
        return when (PulseGraph.engine(applicationContext).sync(includeBackfill = backfill)) {
            SyncEngine.Outcome.OK, SyncEngine.Outcome.ABORTED -> Result.success()
            SyncEngine.Outcome.RETRY_LATER -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            SyncEngine.Outcome.NEEDS_SIGN_IN, SyncEngine.Outcome.FAILED -> Result.failure()
        }
    }

    companion object {
        const val KEY_BACKFILL = "backfill"
    }
}

/** Schedules the first history download and the regular sync every 4 hours. */
object SyncScheduler {
    private const val INITIAL = "pulse-sync-initial"
    private const val PERIODIC = "pulse-sync-periodic"

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
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                // true: an interrupted first download is finished by the next periodic run.
                .setInputData(workDataOf(SyncWorker.KEY_BACKFILL to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }

    fun stop(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(INITIAL)
        wm.cancelUniqueWork(PERIODIC)
    }
}
