package com.fitbittrack.pulse

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.auth.GoogleHealthAuth
import com.fitbittrack.pulse.data.HealthApi
import com.fitbittrack.pulse.ui.DataLayerBridge
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import com.fitbittrack.pulse.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** App-shell state: sign-in, theme, look-and-feel preferences. Health data lives in DashboardViewModel. */
data class UiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val prefs: UiPrefs = UiPrefs(),
    val auth: AuthState = AuthState.SignedOut,
    val connectionTest: TestState = TestState.NotRun,
    /** Set when Google needs to show its consent screen; the UI launches it once. */
    val pendingConsent: IntentSender? = null,
)

sealed interface AuthState {
    data object SignedOut : AuthState
    data object Working : AuthState
    data class SignedIn(val grantedScopes: Int) : AuthState
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
        trySilentSignIn()
    }

    /** On app start: if Google already granted access, sign in without showing anything. */
    private fun trySilentSignIn() = viewModelScope.launch {
        val outcome = auth.authorize()
        if (outcome is GoogleHealthAuth.Outcome.Granted) handle(outcome)
        // NeedsConsent or Failed: stay signed out; the Connect screen offers the button.
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefsStore.setTheme(mode) }
    fun setPrefs(prefs: UiPrefs) = viewModelScope.launch { prefsStore.setPrefs(prefs) }

    fun connect() {
        _state.update { it.copy(auth = AuthState.Working) }
        viewModelScope.launch { handle(auth.authorize()) }
    }

    fun consentLaunched() = _state.update { it.copy(pendingConsent = null) }

    fun onConsentResult(data: Intent?) = handle(auth.fromConsentIntent(data))

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

    fun signOut() {
        accessToken = null
        DataLayerBridge.onSignedOut(app)
        _state.update { it.copy(auth = AuthState.SignedOut, connectionTest = TestState.NotRun) }
    }

    private fun handle(outcome: GoogleHealthAuth.Outcome) {
        when (outcome) {
            is GoogleHealthAuth.Outcome.Granted -> {
                accessToken = outcome.accessToken
                _state.update { it.copy(auth = AuthState.SignedIn(outcome.grantedScopes.size)) }
                DataLayerBridge.onSignedIn(app, outcome.accessToken)
                runConnectionTest()
            }
            is GoogleHealthAuth.Outcome.NeedsConsent ->
                _state.update { it.copy(pendingConsent = outcome.intentSender) }
            is GoogleHealthAuth.Outcome.Failed ->
                _state.update { it.copy(auth = AuthState.Error(outcome.message)) }
        }
    }
}
