package com.fitbittrack.pulse.ui

import android.content.Context
import com.fitbittrack.pulse.PulseGraph
import com.fitbittrack.pulse.domain.HealthRepository

/** The only place the UI touches the data layer's entry points ([PulseGraph]). */
object DataLayerBridge {

    fun realRepository(context: Context): HealthRepository = PulseGraph.repository(context)

    /** Starts the history download and the 4-hourly background sync. */
    fun onSignedIn(context: Context, accessToken: String?) = PulseGraph.onSignedIn(context, accessToken)

    /** Stops background sync and forgets the in-memory token. Local data is deleted separately. */
    fun onSignedOut(context: Context) = PulseGraph.onSignedOut(context)
}
