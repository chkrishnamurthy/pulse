package com.fitbittrack.pulse.auth

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Asks Google for permission to READ the user's Google Health data.
 *
 * Uses Google Identity Services' Authorization API. There is no client ID or secret in this
 * code: Google matches the app by its package name + signing-key SHA-1, which you register as
 * an "Android" OAuth client in the same Google Cloud project (done after Phase 2, Step 7).
 *
 * Status: NOT YET TESTED against the Google Health API. Google's Health docs only show web
 * clients and require the system browser (never a WebView). If this path is refused, Phase 3
 * falls back to AppAuth + Chrome Custom Tabs behind this same class.
 */
class GoogleHealthAuth(context: Context) {

    private val client = Identity.getAuthorizationClient(context.applicationContext)

    sealed interface Outcome {
        /** Access granted. The token lives in memory only and lasts about 1 hour. */
        data class Granted(val accessToken: String, val grantedScopes: List<String>) : Outcome
        /** Google must show its consent / account screen first. Launch this, then call [fromConsentIntent]. */
        data class NeedsConsent(val intentSender: IntentSender) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Silent if already granted; otherwise returns [Outcome.NeedsConsent]. */
    suspend fun authorize(): Outcome = try {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map { Scope(it) })
            .build()
        val result = client.authorize(request).await()
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) {
            Outcome.NeedsConsent(pending.intentSender)
        } else {
            result.toOutcome()
        }
    } catch (e: ApiException) {
        Outcome.Failed(describe(e))
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Call with the data Intent returned from the consent screen. */
    fun fromConsentIntent(data: Intent?): Outcome = try {
        client.getAuthorizationResultFromIntent(data).toOutcome()
    } catch (e: ApiException) {
        Outcome.Failed(describe(e))
    }

    private fun AuthorizationResult.toOutcome(): Outcome {
        val token = accessToken ?: return Outcome.Failed("Google returned no access token.")
        return Outcome.Granted(token, grantedScopes)
    }

    private fun describe(e: ApiException): String = when (e.statusCode) {
        10 -> "Developer error (10): this app's package name + SHA-1 are not registered as an Android OAuth client in your Google Cloud project yet."
        16 -> "Sign-in was cancelled."
        7 -> "No internet connection."
        else -> "Google sign-in error ${e.statusCode}: ${e.message}"
    }

    companion object {
        private const val P = "https://www.googleapis.com/auth/googlehealth."

        /** Read-only scopes, same five as the Phase 2 guide. Nothing that writes. */
        val SCOPES = listOf(
            P + "activity_and_fitness.readonly",
            P + "health_metrics_and_measurements.readonly",
            P + "sleep.readonly",
            P + "profile.readonly",
            P + "settings.readonly",
        )
    }
}
