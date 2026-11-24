package com.fitbittrack.pulse.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.BuildConfig
import com.fitbittrack.pulse.air.AirClient
import com.fitbittrack.pulse.air.AirEngine
import com.fitbittrack.pulse.air.AirState
import com.fitbittrack.pulse.air.Locator
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Outdoor air near the phone. Works only while an air card or page is on screen, and only after the user turned it on. */
class AirViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UiPrefsStore(app)
    private val client = AirClient(BuildConfig.WAQI_KEY)
    private val engine = AirEngine(locate = Locator(app)::find, read = client::read)

    val state: StateFlow<AirState> = engine.state

    /** Null until the setting has been read, so the "Turn on" card never flashes. */
    val enabled: StateFlow<Boolean?> = prefs.air.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var watchers = 0
    private var polling: Job? = null
    private var asking: Job? = null

    init {
        // Also covers signing out, which switches the setting off.
        viewModelScope.launch {
            enabled.collect { on ->
                if (on == true) poll()
                if (on == false) { halt(); engine.clear() }
            }
        }
    }

    fun start() {
        watchers++
        poll()
    }

    fun stop() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers == 0) halt()
    }

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { prefs.setAir(on) }
    }

    /** Pull to refresh, "Try again", or a permission just given. */
    fun refresh() {
        if (enabled.value != true || watchers == 0 || asking?.isActive == true) return
        asking = viewModelScope.launch { engine.refresh(force = true) }
    }

    private fun poll() {
        if (watchers == 0 || enabled.value != true || polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (true) {
                engine.refresh()
                delay(CHECK_MS)
            }
        }
    }

    private fun halt() {
        polling?.cancel()
        asking?.cancel()
        polling = null
        asking = null
    }

    private companion object {
        const val CHECK_MS = 5 * 60_000L
    }
}
