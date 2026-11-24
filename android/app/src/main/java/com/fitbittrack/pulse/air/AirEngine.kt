package com.fitbittrack.pulse.air

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

enum class AirProblem { NoPermission, LocationOff, NoLocation, Service }

data class AirState(
    val loading: Boolean = false,
    val reading: AirReading? = null,
    val problem: AirProblem? = null,
    val message: String? = null,
)

/** Decides when to ask for air, and what stays on screen when asking fails. */
class AirEngine(
    private val locate: suspend () -> Located,
    private val read: suspend (Place) -> AirReading,
    private val now: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(AirState())
    val state: StateFlow<AirState> = _state.asStateFlow()
    private val lock = Mutex()
    private var fetchedAt: Instant? = null

    /** Asks again only when the last answer is old, unless [force]. */
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val fresh = fetchedAt?.let { Duration.between(it, now()) < AirRules.FRESH } == true && _state.value.problem == null
        if (fresh && !force) return@withLock
        _state.update { it.copy(loading = true) }
        try {
            when (val l = locate()) {
                is Located.At -> {
                    val r = read(l.place)
                    fetchedAt = now()
                    _state.value = AirState(reading = r)
                }
                // Without permission nothing is shown: the user said no.
                Located.NoPermission -> {
                    fetchedAt = null
                    _state.value = AirState(problem = AirProblem.NoPermission)
                }
                Located.SwitchedOff -> fail(AirProblem.LocationOff, "Location is switched off on this phone.")
                Located.NotFound -> fail(AirProblem.NoLocation, "Your phone could not find where you are. Try again in a moment.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(AirProblem.Service, (e as? AirException)?.message ?: "Could not get the air reading.")
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    fun clear() {
        fetchedAt = null
        _state.value = AirState()
    }

    private fun fail(problem: AirProblem, message: String) = _state.update { s ->
        val keep = s.reading?.takeIf { fetchedAt?.let { f -> Duration.between(f, now()) <= AirRules.KEEP } == true }
        AirState(loading = s.loading, reading = keep, problem = problem, message = message)
    }
}
