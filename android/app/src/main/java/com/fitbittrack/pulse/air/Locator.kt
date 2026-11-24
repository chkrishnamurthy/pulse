package com.fitbittrack.pulse.air

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface Located {
    data class At(val place: Place) : Located
    data object NoPermission : Located
    data object SwitchedOff : Located
    data object NotFound : Located
}

/** The phone's rough position, asked for once each time. Never in the background, never saved. */
class Locator(private val context: Context) {

    fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked on the first line
    suspend fun find(): Located {
        if (!allowed()) return Located.NoPermission
        return try {
            val lm = context.getSystemService(LocationManager::class.java) ?: return Located.NotFound
            if (!LocationManagerCompat.isLocationEnabled(lm)) return Located.SwitchedOff
            val providers = lm.getProviders(true).filter(::usable)
            val known = providers.mapNotNull { lm.getLastKnownLocation(it) }.minByOrNull(::ageMillis)
            if (known != null && ageMillis(known) < RECENT_MS) return known.found()
            // All at once, first answer wins: one after another could take half a minute.
            val fresh = withTimeoutOrNull(WAIT_MS) {
                channelFlow { SOURCES.filter { it in providers }.forEach { p -> launch { current(lm, p)?.let { send(it) } } } }.firstOrNull()
            }
            (fresh ?: known?.takeIf { ageMillis(it) < USABLE_MS })?.found() ?: Located.NotFound
        } catch (_: SecurityException) {
            Located.NoPermission // switched off while we were asking
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val stop = CancellationSignal()
        cont.invokeOnCancellation { stop.cancel() }
        LocationManagerCompat.getCurrentLocation(lm, provider, stop, ContextCompat.getMainExecutor(context)) { l: Location? ->
            if (cont.isActive) cont.resume(l)
        }
    }

    /** Before Android 12, GPS and passive need the exact-location permission, which Pulse does not ask for here. */
    private fun usable(provider: String) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || (provider != LocationManager.GPS_PROVIDER && provider != LocationManager.PASSIVE_PROVIDER)

    private fun ageMillis(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
    private fun Location.found() = Located.At(Place(latitude, longitude))

    companion object {
        const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION
        private const val RECENT_MS = 15 * 60_000L
        private const val USABLE_MS = 2 * 3_600_000L
        private const val WAIT_MS = 10_000L
        private val SOURCES = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    }
}
