package com.fitbittrack.pulse.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.direct.BandAnchor
import com.fitbittrack.pulse.direct.HeartDevice
import com.fitbittrack.pulse.direct.LiveHeart
import com.fitbittrack.pulse.direct.LiveState
import com.fitbittrack.pulse.direct.LiveStepMath
import com.fitbittrack.pulse.direct.LiveSteps
import com.fitbittrack.pulse.direct.LocalLane
import com.fitbittrack.pulse.direct.LocalLaneStatus
import com.fitbittrack.pulse.direct.LocalSnapshot
import com.fitbittrack.pulse.direct.PhoneSteps
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The direct lanes: Health Connect on this phone, live heart rate from the band, and live steps with the phone's step sensor. */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = UiPrefsStore(app)
    private val lane = LocalLane.get(app)
    private val heart = LiveHeart(app)
    private val phone = PhoneSteps(app)

    val laneStatus: StateFlow<LocalLaneStatus> = lane.status
    val laneData: StateFlow<LocalSnapshot?> = lane.snapshot
    val live: StateFlow<LiveState> = heart.state

    val liveStepsOn: StateFlow<Boolean> = prefs.liveSteps.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val liveHeartOn: StateFlow<Boolean> = prefs.liveHeart.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val bandKnown: StateFlow<Boolean> = prefs.heartBand.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val stepSensor: Boolean get() = phone.supported

    init {
        // Remember the band that worked, so the next time needs no search.
        viewModelScope.launch {
            heart.state.map { (it as? LiveState.Live)?.device }.distinctUntilChanged().collect { d ->
                if (d != null) {
                    byHand = false
                    prefs.setHeartBand(d.address, d.name)
                }
            }
        }
    }

    // ---------- Health Connect ----------

    private var polling: Job? = null

    /** Looks at Health Connect every [POLL_MS] while Pulse is on screen, and keeps the live steps going. */
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (true) {
                lane.poll()
                delay(POLL_MS)
            }
        }
        startSteps()
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
        stepping?.cancel()
        stepping = null
    }

    fun setHealthConnect(on: Boolean) = viewModelScope.launch { lane.setEnabled(on) }

    // ---------- live steps ----------

    private val _liveSteps = MutableStateFlow<LiveSteps?>(null)
    /** Null while off, not allowed, or before the sensor has answered. */
    val liveSteps: StateFlow<LiveSteps?> = _liveSteps.asStateFlow()

    private val anchor = BandAnchor()
    private var stepping: Job? = null

    private val tick: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(TICK_MS)
        }
    }

    private fun startSteps() {
        if (stepping?.isActive == true) return
        stepping = viewModelScope.launch {
            prefs.liveSteps.distinctUntilChanged().collectLatest { on ->
                if (!on || !phone.start()) return@collectLatest
                try {
                    val saved = DataLayerBridge.savedRepository(app)
                    val cloud = tick.map { LocalDate.now() }.distinctUntilChanged().flatMapLatest { d -> saved.daySummary(d).map { d to it?.steps } }
                    val upTo = saved.syncStatus.map { it.lastTrackerSync }.distinctUntilChanged()
                    combine(cloud, upTo, lane.snapshot, phone.steps, tick) { (day, steps), t, local, _, _ ->
                        val now = Instant.now()
                        val band = anchor.take(LiveStepMath.bandTotal(day, steps, t, local), now)
                        LiveStepMath.estimate(band, phone.trail, local?.phoneSteps.orEmpty(), now, ZoneId.systemDefault())
                    }.collect { _liveSteps.value = it }
                } finally {
                    phone.stop()
                    _liveSteps.value = null
                }
            }
        }
    }

    fun setLiveSteps(on: Boolean) = viewModelScope.launch { prefs.setLiveSteps(on) }

    /** The permission was just given while the switch was already on. */
    fun retrySteps() {
        stepping?.cancel()
        stepping = null
        if (polling?.isActive == true) startSteps()
    }

    // ---------- live heart rate ----------

    private var watchers = 0
    private var auto: Job? = null
    /** The user is searching or has stopped it: don't connect by itself meanwhile. */
    private var byHand = false

    /** A page that shows live heart rate came on screen. Connects to the known band if the user switched that on. */
    fun watchHeart() {
        watchers++
        if (auto?.isActive == true) return
        auto = viewModelScope.launch {
            combine(prefs.liveHeart, prefs.heartBand) { on, band -> band.takeIf { on } }.distinctUntilChanged().collectLatest { band ->
                if (band == null) return@collectLatest
                while (true) {
                    val s = heart.state.value
                    if (!byHand && (s is LiveState.Off || s is LiveState.Failed)) heart.connect(band)
                    delay(RETRY_MS)
                }
            }
        }
    }

    /** The last page that shows it left the screen: the connection ends, so it never uses battery in the background. */
    fun unwatchHeart() {
        watchers = (watchers - 1).coerceAtLeast(0)
        byHand = false
        if (watchers > 0) return
        auto?.cancel()
        auto = null
        heart.stop()
    }

    fun setLiveHeart(on: Boolean) = viewModelScope.launch { prefs.setLiveHeart(on) }

    fun searchBand() { byHand = true; heart.search() }
    fun connectBand(d: HeartDevice) { byHand = true; heart.connect(d) }
    fun stopBand() { byHand = true; heart.stop() }

    override fun onCleared() {
        heart.stop()
        phone.stop()
    }

    private companion object {
        const val POLL_MS = 10_000L
        const val TICK_MS = 5_000L
        const val RETRY_MS = 30_000L
    }
}
