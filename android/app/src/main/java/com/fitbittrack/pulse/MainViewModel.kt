package com.fitbittrack.pulse

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.auth.GoogleHealthAuth
import com.fitbittrack.pulse.chat.CHAT_FILE
import com.fitbittrack.pulse.chat.ChatStore
import com.fitbittrack.pulse.data.HealthApi
import com.fitbittrack.pulse.direct.LocalLane
import com.fitbittrack.pulse.ui.DataLayerBridge
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import com.fitbittrack.pulse.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** App-shell state: sign-in, theme, look-and-feel preferences. Health data lives in DashboardViewModel. */
data class UiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val prefs: UiPrefs = UiPrefs(),
    val auth: AuthState = AuthState.Checking,
    val accountName: String? = null,
    val connectionTest: TestState = TestState.NotRun,
    val signingOut: Boolean = false,
    /** Set when Google needs to show its consent screen; the UI launches it once. */
    val pendingConsent: IntentSender? = null,
)

sealed interface AuthState {
    /** App just started; reading whether an account was connected before. */
    data object Checking : AuthState
    data object SignedOut : AuthState
    data object Working : AuthState
    /** [grantedScopes] is null when restored from a previous session (not re-checked yet). */
    data class SignedIn(val grantedScopes: Int?) : AuthState
    data class Error(val message: String) : AuthState
}

sealed interface TestState {
    data object NotRun : TestState
    data object Running : TestState
    data class Passed(val detail: String) : TestState
    data class Failed(val message: String) : TestState
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val auth = GoogleHealthAuth(app)
    private val api = HealthApi()
    private val prefsStore = UiPrefsStore(app)

    /** In memory only. Never written to disk. */
    private var accessToken: String? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { prefsStore.theme.collect { t -> _state.update { it.copy(themeMode = t) } } }
        viewModelScope.launch { prefsStore.prefs.collect { p -> _state.update { it.copy(prefs = p) } } }
        viewModelScope.launch { restoreSession() }
    }

    /**
     * A previously connected account counts as signed in straight away, so cached data shows even
     * offline. A fresh token is then requested silently; if Google refuses, background sync reports
     * "Sign in again" and the user reconnects from Settings.
     */
    private suspend fun restoreSession() {
        val session = prefsStore.session.first()
        if (!session.connected) {
            _state.update { it.copy(auth = AuthState.SignedOut) }
            return
        }
        _state.update { it.copy(auth = AuthState.SignedIn(null), accountName = session.accountName) }
        DataLayerBridge.ensureBackgroundSync(app)
        val outcome = auth.authorize() // silent when already granted; never shows UI here
        if (outcome is GoogleHealthAuth.Outcome.Granted) {
            accessToken = outcome.accessToken
            _state.update { it.copy(auth = AuthState.SignedIn(outcome.grantedScopes.size)) }
            if (outcome.accountName != null && outcome.accountName != session.accountName) prefsStore.setConnected(outcome.accountName)
            runConnectionTest()
        }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefsStore.setTheme(mode) }
    fun setPrefs(prefs: UiPrefs) = viewModelScope.launch { prefsStore.setPrefs(prefs) }

    /** Interactive sign-in (button). May show Google's consent / account screen. */
    fun connect() {
        _state.update { it.copy(auth = AuthState.Working) }
        viewModelScope.launch { handleInteractive(auth.authorize()) }
    }

    fun consentLaunched() = _state.update { it.copy(pendingConsent = null) }

    fun onConsentResult(data: Intent?) = viewModelScope.launch { handleInteractive(auth.fromConsentIntent(data)) }

    fun runConnectionTest() {
        val token = accessToken ?: return
        _state.update { it.copy(connectionTest = TestState.Running) }
        viewModelScope.launch {
            val result = when (val r = api.getIdentity(token)) {
                is HealthApi.Result.Ok ->
                    if (r.value.hasHealthUserId) TestState.Passed("Google Health answered. Your health user ID was received.")
                    else TestState.Passed("Google Health answered, but no health user ID was in the reply.")
                is HealthApi.Result.Error -> TestState.Failed(r.message)
            }
            _state.update { it.copy(connectionTest = result) }
        }
    }

    /** Re-runs the connection check, getting a fresh token first if needed. */
    fun retryConnectionTest() = viewModelScope.launch {
        if (accessToken == null) {
            val o = auth.authorize()
            if (o is GoogleHealthAuth.Outcome.Granted) accessToken = o.accessToken
            else {
                _state.update { it.copy(connectionTest = TestState.Failed((o as? GoogleHealthAuth.Outcome.Failed)?.message ?: "Google needs you to sign in again.")) }
                return@launch
            }
        }
        runConnectionTest()
    }

    /**
     * Full sign-out, in a safe order: stop background sync → delete the local copy → remove the
     * app's permission at Google → forget the account. Health data at Google is not touched.
     */
    fun signOut(onDone: () -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(signingOut = true) }
        val accountName = _state.value.accountName ?: prefsStore.session.first().accountName
        DataLayerBridge.onSignedOut(app)
        LocalLane.get(app).turnOff()
        ChatStore(File(app.noBackupFilesDir, CHAT_FILE)).clear()
        try {
            DataLayerBridge.realRepository(app).deleteAllLocalData()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Local delete failed (very unlikely); continue so the account is still disconnected.
        }
        auth.revoke(accountName, accessToken)
        accessToken = null
        prefsStore.clearSession()
        _state.update {
            it.copy(auth = AuthState.SignedOut, accountName = null, connectionTest = TestState.NotRun, signingOut = false)
        }
        onDone()
    }

    private suspend fun handleInteractive(outcome: GoogleHealthAuth.Outcome) {
        when (outcome) {
            is GoogleHealthAuth.Outcome.Granted -> {
                accessToken = outcome.accessToken
                prefsStore.setConnected(outcome.accountName)
                _state.update { it.copy(auth = AuthState.SignedIn(outcome.grantedScopes.size), accountName = outcome.accountName) }
                // Starts the history download + periodic sync. Only on interactive sign-in (not every app start).
                DataLayerBridge.onSignedIn(app, outcome.accessToken)
                runConnectionTest()
            }
            is GoogleHealthAuth.Outcome.NeedsConsent ->
                _state.update { it.copy(pendingConsent = outcome.intentSender) }
            is GoogleHealthAuth.Outcome.Failed ->
                if (prefsStore.session.first().connected) {
                    // Re-connect attempt failed: keep showing the cached data, explain why in Settings.
                    _state.update { it.copy(auth = AuthState.SignedIn(null), connectionTest = TestState.Failed(outcome.message)) }
                } else {
                    _state.update { it.copy(auth = AuthState.Error(outcome.message)) }
                }
        }
    }
}
