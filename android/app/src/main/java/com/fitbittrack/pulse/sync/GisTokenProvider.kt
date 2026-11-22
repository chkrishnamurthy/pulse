package com.fitbittrack.pulse.sync

import android.content.Context
import com.fitbittrack.pulse.auth.GoogleHealthAuth
import com.fitbittrack.pulse.data.HealthApiException
import com.fitbittrack.pulse.data.remote.NeedsSignInException
import com.fitbittrack.pulse.data.remote.TokenProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gets access tokens from Google Identity Services, silently when the user already agreed.
 * Works from background sync too. If Google needs to show its consent screen again,
 * sync stops with "sign in again" instead of opening anything by itself.
 *
 * The token is kept in memory only (about 1 hour), never on disk.
 */
class GisTokenProvider(context: Context) : TokenProvider {

    private val auth = GoogleHealthAuth(context)
    private val lock = Mutex()
    @Volatile private var cached: String? = null
    @Volatile private var cachedAtMs = 0L

    /** Lets the screen that just signed in hand over its fresh token. */
    fun seed(token: String) {
        cached = token
        cachedAtMs = System.currentTimeMillis()
    }

    override suspend fun accessToken(): String = lock.withLock {
        cached?.takeIf { System.currentTimeMillis() - cachedAtMs < MAX_AGE_MS }?.let { return@withLock it }
        when (val outcome = auth.authorize()) {
            is GoogleHealthAuth.Outcome.Granted -> {
                seed(outcome.accessToken)
                outcome.accessToken
            }
            is GoogleHealthAuth.Outcome.NeedsConsent ->
                throw NeedsSignInException("Google needs you to sign in again. Open Pulse and tap Connect.")
            is GoogleHealthAuth.Outcome.Failed ->
                if ("internet" in outcome.message.lowercase()) throw HealthApiException(null, "", outcome.message)
                else throw NeedsSignInException(outcome.message)
        }
    }

    override fun invalidate() {
        cached = null
    }

    private companion object {
        /** Google access tokens last about 60 minutes; refresh a little early. */
        const val MAX_AGE_MS = 50 * 60 * 1000L
    }
}
