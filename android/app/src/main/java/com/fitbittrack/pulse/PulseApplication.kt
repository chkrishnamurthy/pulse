package com.fitbittrack.pulse

import android.app.Application
import com.fitbittrack.pulse.chat.OldAiCleanup
import com.fitbittrack.pulse.ui.widget.PulseWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App start-up: redraw home-screen widgets after every successful sync (background or manual),
 * and delete anything the removed AI left on the phone.
 */
class PulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PulseGraph.addSyncListener { ctx -> PulseWidgets.refreshAll(ctx) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { OldAiCleanup.run(this@PulseApplication) }
        }
    }
}
