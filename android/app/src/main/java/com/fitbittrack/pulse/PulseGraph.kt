package com.fitbittrack.pulse

import android.content.Context
import com.fitbittrack.pulse.data.HealthApi
import com.fitbittrack.pulse.data.HealthRepositoryImpl
import com.fitbittrack.pulse.data.local.PulseDatabase
import com.fitbittrack.pulse.data.local.SettingsStore
import com.fitbittrack.pulse.data.remote.HealthClient
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.sync.GisTokenProvider
import com.fitbittrack.pulse.sync.SyncEngine
import com.fitbittrack.pulse.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-made wiring for the data layer (one instance of each piece for the whole app).
 *
 * Screens use:
 *  - [repository] to read data,
 *  - [onSignedIn] right after Google sign-in succeeds (starts history download + 4-hourly sync),
 *  - [onSignedOut] when the user signs out (stops background sync; local data stays until deleted).
 */
object PulseGraph {

    private class Graph(context: Context) {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = PulseDatabase.create(context)
        val store = SettingsStore(context)
        val tokens = GisTokenProvider(context)
        val client = HealthClient(HealthApi(), tokens)
        val engine = SyncEngine(client, db.dao(), store, onSuccess = {
            syncListeners.forEach { listener -> runCatching { listener(context) } }
        })
        val repository: HealthRepository = HealthRepositoryImpl(db, store, engine, appScope)
    }

    @Volatile private var graph: Graph? = null

    private val syncListeners = java.util.concurrent.CopyOnWriteArrayList<suspend (Context) -> Unit>()

    /**
     * Run [listener] after every successful sync (background or pull-to-refresh), with the app context.
     * Register once, for example from Application.onCreate or the widget receiver.
     */
    fun addSyncListener(listener: suspend (Context) -> Unit) {
        syncListeners.addIfAbsent(listener)
    }

    private fun graph(context: Context): Graph =
        graph ?: synchronized(this) { graph ?: Graph(context.applicationContext).also { graph = it } }

    fun repository(context: Context): HealthRepository = graph(context).repository

    internal fun engine(context: Context): SyncEngine = graph(context).engine

    /** Call after sign-in succeeds. Passing the fresh [accessToken] saves one extra Google call. */
    fun onSignedIn(context: Context, accessToken: String? = null) {
        val g = graph(context)
        accessToken?.let(g.tokens::seed)
        SyncScheduler.start(context.applicationContext)
    }

    fun onSignedOut(context: Context) {
        graph(context).tokens.invalidate()
        SyncScheduler.stop(context.applicationContext)
    }
}
