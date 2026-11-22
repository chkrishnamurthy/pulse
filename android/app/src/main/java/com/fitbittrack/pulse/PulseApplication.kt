package com.fitbittrack.pulse

import android.app.Application
import com.fitbittrack.pulse.ui.widget.PulseWidgets

/** App start-up: redraw home-screen widgets after every successful sync (background or manual). */
class PulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PulseGraph.addSyncListener { ctx -> PulseWidgets.refreshAll(ctx) }
    }
}
