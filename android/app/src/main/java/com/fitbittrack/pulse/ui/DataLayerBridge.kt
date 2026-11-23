package com.fitbittrack.pulse.ui

import android.content.Context
import com.fitbittrack.pulse.PulseGraph
import com.fitbittrack.pulse.direct.LocalLane
import com.fitbittrack.pulse.direct.MergedHealthRepository
import com.fitbittrack.pulse.domain.HealthRepository

/** The only place the UI touches the data layer's entry points ([PulseGraph]). */
object DataLayerBridge {

    @Volatile private var merged: HealthRepository? = null

    /** What the screens read: saved data from Google's servers, plus today's fresher numbers from Health Connect. */
    fun realRepository(context: Context): HealthRepository = merged ?: synchronized(this) {
        merged ?: LocalLane.get(context).let { lane ->
            MergedHealthRepository(PulseGraph.repository(context), lane.snapshot, lane::clear)
        }.also { merged = it }
    }

    /** Saved data only. Widgets use this: they also redraw when Pulse is closed, when Health Connect isn't read. */
    fun savedRepository(context: Context): HealthRepository = PulseGraph.repository(context)

    /** Keeps the regular background sync scheduled (every app start with a connected account). */
    fun ensureBackgroundSync(context: Context) = PulseGraph.ensureBackgroundSync(context)

    /** One background sync soon (widget refresh button). */
    fun syncNow(context: Context) = PulseGraph.syncNow(context)

    /** Starts the history download and the regular background sync. */
    fun onSignedIn(context: Context, accessToken: String?) = PulseGraph.onSignedIn(context, accessToken)

    /** Stops background sync and forgets the in-memory token. Local data is deleted separately. */
    fun onSignedOut(context: Context) = PulseGraph.onSignedOut(context)
}
