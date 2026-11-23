package com.fitbittrack.pulse.ui.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Look-and-feel choices: theme, units, Today cards. Separate from the data layer's store. */
private val Context.uiPrefsStore by preferencesDataStore(name = "ui_prefs")

class UiPrefsStore(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme")
    private val unitsKey = stringPreferencesKey("units")
    private val cardsKey = stringPreferencesKey("today_cards") // "Dial:1,Sleep:0,…"
    private val connectedKey = booleanPreferencesKey("connected")
    private val accountKey = stringPreferencesKey("account_name")
    private val healthConnectKey = booleanPreferencesKey("health_connect")

    /** Whether the user connected a Google account and has not signed out. No tokens are stored. */
    data class Session(val connected: Boolean, val accountName: String?)

    val session: Flow<Session> = context.uiPrefsStore.data.map { p ->
        Session(p[connectedKey] ?: false, p[accountKey])
    }

    suspend fun setConnected(accountName: String?) = context.uiPrefsStore.edit {
        it[connectedKey] = true
        if (accountName != null) it[accountKey] = accountName else it.remove(accountKey)
    }

    suspend fun clearSession() = context.uiPrefsStore.edit {
        it.remove(connectedKey); it.remove(accountKey)
    }

    /** Whether the user switched on reading today's numbers from Health Connect. Off by default. */
    val healthConnect: Flow<Boolean> = context.uiPrefsStore.data.map { it[healthConnectKey] ?: false }

    suspend fun setHealthConnect(on: Boolean) = context.uiPrefsStore.edit { it[healthConnectKey] = on }

    val theme: Flow<ThemeMode> = context.uiPrefsStore.data.map { p ->
        p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
    }

    val prefs: Flow<UiPrefs> = context.uiPrefsStore.data.map { p ->
        UiPrefs(
            units = p[unitsKey]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.Metric,
            todayCards = p[cardsKey]?.let(::decodeCards) ?: UiPrefs().todayCards,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.uiPrefsStore.edit { it[themeKey] = mode.name }

    suspend fun setPrefs(prefs: UiPrefs) = context.uiPrefsStore.edit {
        it[unitsKey] = prefs.units.name
        it[cardsKey] = prefs.todayCards.joinToString(",") { (c, on) -> "${c.name}:${if (on) 1 else 0}" }
    }

    private fun decodeCards(s: String): List<Pair<TodayCard, Boolean>> {
        val saved = s.split(",").mapNotNull { part ->
            val (name, on) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            runCatching { TodayCard.valueOf(name) }.getOrNull()?.let { it to (on == "1") }
        }
        // Cards added in a later app version are appended, switched off.
        val missing = TodayCard.entries.filter { c -> saved.none { it.first == c } }.map { it to false }
        return saved + missing
    }
}
