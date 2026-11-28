package com.fitbittrack.pulse.training

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fitbittrack.pulse.R
import com.fitbittrack.pulse.reminders.notificationsAllowed
import com.fitbittrack.pulse.ui.widget.art.WidgetOpen
import com.fitbittrack.pulse.ui.widget.art.WidgetTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Keeps an open workout alive while the phone is in a pocket: shows the rest and the next set, and tells when the rest is over.
 * It holds nothing itself. The workout and the end of the rest are in the database, so nothing is lost when Android stops it.
 */
class WorkoutService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val ok = try {
            ServiceCompat.startForeground(
                this, ID, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Workout").setOngoing(true).setSilent(true).build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0,
            )
            true
        } catch (_: Exception) {
            // Not allowed right now (started from the background). The workout itself is safe in the database.
            false
        }
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        val repo = TrainingGraph.repository(this)
        when (intent?.action) {
            ADD -> act { repo.addRest(it, 15) }
            LESS -> act { repo.addRest(it, -15) }
            SKIP -> act { repo.skipRest(it) }
        }
        if (!started) { started = true; watch(repo) }
        return START_STICKY
    }

    private fun act(block: suspend (String) -> Unit) {
        TrainingGraph.scope(this).launch { TrainingGraph.repository(this@WorkoutService).dao.activeSessionNow()?.let { block(it.id) } }
    }

    private data class Look(val session: SessionEntity, val exercises: List<SessionExerciseEntity>, val sets: List<SetEntity>, val bpm: Int?, val unit: WeightUnit)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watch(repo: TrainingRepository) {
        val look = repo.dao.activeSession().distinctUntilChanged().flatMapLatest { s ->
            if (s == null) flowOf(null)
            else combine(repo.dao.sessionExercises(s.id), repo.dao.setsOfSession(s.id), TrainingGraph.liveBpm, repo.prefs.settings) { e, l, b, p -> Look(s, e, l, b, p.unit) }
        }
        scope.launch {
            look.collect { l ->
                if (l == null) { ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
                else if (notificationsAllowed(this@WorkoutService)) notify(this@WorkoutService, ID, build(l))
            }
        }
        // One alarm per rest. Adding or skipping time changes the end, and this starts again.
        scope.launch {
            repo.dao.activeSession().distinctUntilChanged { a, b -> a?.id == b?.id && a?.restEndsMs == b?.restEndsMs }.collectLatest { s ->
                release()
                val ends = s?.restEndsMs ?: return@collectLatest
                val left = ends - System.currentTimeMillis()
                if (left > 0) {
                    hold(left + 5_000)
                    delay(left)
                }
                repo.skipRest(s.id)
                if (left > -5_000 && repo.prefs.settings.first().signal) restOver()
                release()
            }
        }
    }

    private fun hold(ms: Long) {
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pulse:rest").apply { setReferenceCounted(false); acquire(ms.coerceAtMost(20 * 60_000L)) }
    }

    private fun release() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
    }

    private fun next(l: Look): String? {
        val by = l.exercises.map { x -> l.sets.filter { it.sessionExerciseId == x.id } }
        val (i, k) = NextSet.of(l.exercises.mapIndexed { n, x -> x.superset to by[n].map { it.done } }) ?: return null
        val e = l.exercises[i]
        val set = by[i][k]
        val n = k + 1
        val what = listOfNotNull(set.grams?.let { Weights.text(it, l.unit) }, set.reps?.let { "$it reps" } ?: set.seconds?.let { "$it seconds" }).joinToString(", ")
        return "Next: ${e.name}, set $n" + if (what.isEmpty()) "" else ". $what"
    }

    private fun build(l: Look): Notification {
        val done = l.sets.count { it.done }
        val rest = l.session.restEndsMs?.takeIf { it > System.currentTimeMillis() }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setCategory(if (rest != null) NotificationCompat.CATEGORY_STOPWATCH else NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(if (rest != null) "Rest, ${l.session.title}" else l.session.title)
            .setContentText(next(l) ?: "All sets done")
            .setSubText(listOfNotNull(l.bpm?.let { "$it bpm" }, "$done of ${l.sets.size} sets").joinToString(", "))
            .setContentIntent(PendingIntent.getActivity(this, 50, WidgetOpen.intent(this, WidgetTap.Train), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (rest != null) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(rest).setShowWhen(true)
                .addAction(0, "−15 sec", action(LESS, 1)).addAction(0, "+15 sec", action(ADD, 2)).addAction(0, "Skip", action(SKIP, 3))
        } else {
            b.setUsesChronometer(true).setWhen(l.session.startMs).setShowWhen(true)
        }
        return b.build()
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, WorkoutService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun restOver() {
        if (!notificationsAllowed(this)) return
        notify(
            this, ID_OVER,
            NotificationCompat.Builder(this, CHANNEL_OVER).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Rest is over").setContentText("Time for the next set.")
                .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setTimeoutAfter(15_000)
                .setContentIntent(PendingIntent.getActivity(this, 51, WidgetOpen.intent(this, WidgetTap.Train), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build(),
        )
    }

    override fun onDestroy() {
        release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "workout"
        private const val CHANNEL_OVER = "rest_over"
        private const val ID = 60
        private const val ID_OVER = 61
        private const val ADD = "pulse.rest.add"
        private const val LESS = "pulse.rest.less"
        private const val SKIP = "pulse.rest.skip"

        /** Call while Pulse is on screen and a workout is open. Safe to call again. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java))
            } catch (_: Exception) {
                // Android may refuse. The workout works without it, only the notification is missing.
            }
        }

        private fun channels(context: Context) {
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Open workout", NotificationManager.IMPORTANCE_LOW).apply { description = "Rest and next set while you train"; setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_OVER, "Rest is over", NotificationManager.IMPORTANCE_HIGH).apply { description = "Sound and buzz when a rest ends"; enableVibration(true); vibrationPattern = longArrayOf(0, 250, 150, 250) })
        }

        @Suppress("MissingPermission") // Checked with notificationsAllowed.
        private fun notify(context: Context, id: Int, n: Notification) = NotificationManagerCompat.from(context).notify(id, n)
    }
}
