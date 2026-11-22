package com.fitbittrack.pulse.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Asks Google for permission to READ the user's Google Health data.
 *
 * Uses Google Identity Services' Authorization API. There is no client ID or secret in this
 * code: Google matches the app by its package name + signing-key SHA-1, which you register as
 * an "Android" OAuth client in the same Google Cloud project.
 *
 * Status: NOT YET TESTED against the Google Health API. Google's Health docs only show web
 * clients and require the system browser (never a WebView). If this path is refused, swap in
 * AppAuth + Chrome Custom Tabs behind this same class.
 */
class GoogleHealthAuth(context: Context) {

    private val client = Identity.getAuthorizationClient(context.applicationContext)

    sealed interface Outcome {
        /** Access granted. The token lives in memory only and lasts about 1 hour. */
        data class Granted(val accessToken: String, val grantedScopes: List<String>, val accountName: String?) : Outcome
        /** Google must show its consent / account screen first. Launch this, then call [fromConsentIntent]. */
        data class NeedsConsent(val intentSender: IntentSender) : Outcome
        /** [retryable] = a network or temporary problem; trying again later may work without signing in. */
        data class Failed(val message: String, val retryable: Boolean = false) : Outcome
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        failed(e)
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: e.javaClass.simpleName, retryable = true)
    }

    /** Call with the data Intent returned from the consent screen. */
    fun fromConsentIntent(data: Intent?): Outcome = try {
        client.getAuthorizationResultFromIntent(data).toOutcome()
    } catch (e: ApiException) {
        failed(e)
    }

    /**
     * Removes Pulse's permission at Google, so the next sign-in asks again and background sync can't
     * silently get a token. Returns false if Google could not be reached (the user can still remove
     * access at myaccount.google.com).
     */
    suspend fun revoke(accountName: String?, accessToken: String?): Boolean {
        var ok = true
        if (accessToken != null) {
            ok = runSafely { client.clearToken(ClearTokenRequest.builder().setToken(accessToken).build()).await() }
        }
        if (accountName != null) {
            ok = runSafely {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(accountName, GOOGLE_ACCOUNT_TYPE))
                        .setScopes(SCOPES.map { Scope(it) })
                        .build()
                ).await()
            } && ok
        } else {
            ok = false
        }
        return ok
    }

    private suspend fun runSafely(block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun AuthorizationResult.toOutcome(): Outcome {
        val token = accessToken ?: return Outcome.Failed("Google returned no access token.")
        return Outcome.Granted(token, grantedScopes, toGoogleSignInAccount()?.account?.name)
    }

    private fun failed(e: ApiException): Outcome.Failed = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            Outcome.Failed("Developer error (10): this app's package name + SHA-1 are not registered as an Android OAuth client in your Google Cloud project yet.")
        CommonStatusCodes.CANCELED -> Outcome.Failed("Sign-in was cancelled.")
        CommonStatusCodes.NETWORK_ERROR -> Outcome.Failed("No internet connection.", retryable = true)
        CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERRUPTED, CommonStatusCodes.INTERNAL_ERROR ->
            Outcome.Failed("Google sign-in is temporarily unavailable (${e.statusCode}). Try again shortly.", retryable = true)
        else -> Outcome.Failed("Google sign-in error ${e.statusCode}: ${e.message}")
    }

    companion object {
        private const val P = "https://www.googleapis.com/auth/googlehealth."
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"

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
