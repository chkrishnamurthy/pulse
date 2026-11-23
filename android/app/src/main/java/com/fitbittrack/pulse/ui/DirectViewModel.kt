package com.fitbittrack.pulse.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.direct.HeartDevice
import com.fitbittrack.pulse.direct.LiveHeart
import com.fitbittrack.pulse.direct.LiveState
import com.fitbittrack.pulse.direct.LocalLane
import com.fitbittrack.pulse.direct.LocalLaneStatus
import com.fitbittrack.pulse.direct.LocalSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The two direct lanes: Health Connect on this phone, and live heart rate from the band. */
class DirectViewModel(app: Application) : AndroidViewModel(app) {

    private val lane = LocalLane.get(app)
    private val heart = LiveHeart(app)

    val laneStatus: StateFlow<LocalLaneStatus> = lane.status
    val laneData: StateFlow<LocalSnapshot?> = lane.snapshot
    val live: StateFlow<LiveState> = heart.state

    private var polling: Job? = null

    /** Looks at Health Connect every [POLL_MS] while Pulse is on screen. */
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (true) {
                lane.poll()
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    fun setHealthConnect(on: Boolean) = viewModelScope.launch { lane.setEnabled(on) }

    fun searchBand() = heart.search()
    fun connectBand(d: HeartDevice) = heart.connect(d)
    fun stopBand() = heart.stop()

    override fun onCleared() = heart.stop()

    private companion object {
        const val POLL_MS = 20_000L
    }
}
