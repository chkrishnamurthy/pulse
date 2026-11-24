package com.fitbittrack.pulse.ui.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fitbittrack.pulse.chat.Area
import com.fitbittrack.pulse.chat.ChatPrefs
import com.fitbittrack.pulse.direct.HeartDevice
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.PersonalPrefs
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.ThemeMode
import androidx.datastore.preferences.core.longPreferencesKey
import com.fitbittrack.pulse.reminders.ReminderPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import kotlinx.coroutines.flow.map

/** Look-and-feel choices: theme, units, Today cards. Separate from the data layer's store. */
private val Context.uiPrefsStore by preferencesDataStore(name = "ui_prefs")

class UiPrefsStore(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme")
    private val unitsKey = stringPreferencesKey("units")
    private val cardsKey = stringPreferencesKey("today_cards") // "Ring:1,Band:0,…"
    private val ringGoalsKey = stringPreferencesKey("circle_goals") // "Steps,Calories"
    private val connectedKey = booleanPreferencesKey("connected")
    private val accountKey = stringPreferencesKey("account_name")
    private val healthConnectKey = booleanPreferencesKey("health_connect")
    private val airKey = booleanPreferencesKey("air")
    private val liveStepsKey = booleanPreferencesKey("live_steps")
    private val liveHeartKey = booleanPreferencesKey("live_heart")
    private val bandAddressKey = stringPreferencesKey("heart_band")
    private val bandNameKey = stringPreferencesKey("heart_band_name")
    private val chatKeepKey = booleanPreferencesKey("chat_keep")
    private val chatOffKey = stringPreferencesKey("chat_areas_off") // "Sleep,Body"
    private val calGoalsKey = stringPreferencesKey("calendar_goals") // "Steps,Calories"
    private val ageKey = intPreferencesKey("age")
    private val topHrKey = intPreferencesKey("top_hr")

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
        it.remove(airKey) // the next person decides about location for themselves
        it.remove(liveStepsKey); it.remove(liveHeartKey); it.remove(bandAddressKey); it.remove(bandNameKey)
    }

    /** Whether the user switched on reading today's numbers from Health Connect. Off by default. */
    val healthConnect: Flow<Boolean> = context.uiPrefsStore.data.map { it[healthConnectKey] ?: false }

    suspend fun setHealthConnect(on: Boolean) = context.uiPrefsStore.edit { it[healthConnectKey] = on }

    /** Whether the user switched on air quality, which uses the phone's rough location. Off by default. */
    val air: Flow<Boolean> = context.uiPrefsStore.data.map { it[airKey] ?: false }

    suspend fun setAir(on: Boolean) = context.uiPrefsStore.edit { it[airKey] = on }

    /** Band steps plus the phone's own steps since the last band sync. Off by default. */
    val liveSteps: Flow<Boolean> = context.uiPrefsStore.data.map { it[liveStepsKey] ?: false }

    suspend fun setLiveSteps(on: Boolean) = context.uiPrefsStore.edit { it[liveStepsKey] = on }

    /** Whether Home connects to the band for live heart rate. Off by default. */
    val liveHeart: Flow<Boolean> = context.uiPrefsStore.data.map { it[liveHeartKey] ?: false }

    suspend fun setLiveHeart(on: Boolean) = context.uiPrefsStore.edit { it[liveHeartKey] = on }

    /** The band that shared its heart rate last. */
    val heartBand: Flow<HeartDevice?> = context.uiPrefsStore.data.map { p ->
        p[bandAddressKey]?.let { HeartDevice(it, p[bandNameKey] ?: "Band", sharing = false) }
    }

    suspend fun setHeartBand(address: String, name: String) = context.uiPrefsStore.edit { it[bandAddressKey] = address; it[bandNameKey] = name }

    val chat: Flow<ChatPrefs> = context.uiPrefsStore.data.map { p ->
        val off = p[chatOffKey].orEmpty().split(",")
        ChatPrefs(
            keep = p[chatKeepKey] ?: false,
            areas = Area.entries.filter { it.name !in off }.toSet(),
        )
    }

    suspend fun setChat(c: ChatPrefs) = context.uiPrefsStore.edit {
        it[chatKeepKey] = c.keep
        it[chatOffKey] = Area.entries.filter { a -> a !in c.areas }.joinToString(",") { a -> a.name }
    }

    val personal: Flow<PersonalPrefs> = context.uiPrefsStore.data.map { p ->
        val goals = p[calGoalsKey]?.split(",")?.mapNotNull { runCatching { GoalMetric.valueOf(it) }.getOrNull() }?.toSet()
        PersonalPrefs(goals?.takeIf { it.isNotEmpty() } ?: GoalCalendar.CORE, p[ageKey], p[topHrKey])
    }

    suspend fun setPersonal(x: PersonalPrefs) = context.uiPrefsStore.edit {
        it[calGoalsKey] = x.calendarGoals.joinToString(",") { g -> g.name }
        x.age?.let { a -> it[ageKey] = a } ?: it.remove(ageKey)
        x.topHeartRate?.let { h -> it[topHrKey] = h } ?: it.remove(topHrKey)
    }

    private fun time(p: androidx.datastore.preferences.core.Preferences, k: androidx.datastore.preferences.core.Preferences.Key<Int>, d: LocalTime) =
        p[k]?.let { runCatching { LocalTime.of(it / 60, it % 60) }.getOrNull() } ?: d
    private fun mins(t: LocalTime) = t.hour * 60 + t.minute
    private val rEveningOn = booleanPreferencesKey("rem_evening_on")
    private val rEvening = intPreferencesKey("rem_evening")
    private val rBedOn = booleanPreferencesKey("rem_bed_on")
    private val rBed = intPreferencesKey("rem_bed")
    private val rMorningOn = booleanPreferencesKey("rem_morning_on")
    private val rMorning = intPreferencesKey("rem_morning")
    private val rQuietOn = booleanPreferencesKey("rem_quiet_on")
    private val rQuietFrom = intPreferencesKey("rem_quiet_from")
    private val rQuietTo = intPreferencesKey("rem_quiet_to")
    private val rLowOn = booleanPreferencesKey("rem_low_on")
    private val rLowAt = intPreferencesKey("rem_low_at")
    private val rStaleOn = booleanPreferencesKey("rem_stale_on")

    val reminders: Flow<ReminderPrefs> = context.uiPrefsStore.data.map { p ->
        val d = ReminderPrefs()
        ReminderPrefs(
            p[rEveningOn] ?: d.eveningOn, time(p, rEvening, d.evening), p[rBedOn] ?: d.bedtimeOn, time(p, rBed, d.bedtime),
            p[rMorningOn] ?: d.morningOn, time(p, rMorning, d.morning), p[rQuietOn] ?: d.quietOn, time(p, rQuietFrom, d.quietFrom), time(p, rQuietTo, d.quietTo),
            p[rLowOn] ?: d.lowBatteryOn, (p[rLowAt] ?: d.lowBatteryAt).coerceIn(5, 50), p[rStaleOn] ?: d.staleOn,
        )
    }

    suspend fun setReminders(x: ReminderPrefs) = context.uiPrefsStore.edit {
        it[rEveningOn] = x.eveningOn; it[rEvening] = mins(x.evening); it[rBedOn] = x.bedtimeOn; it[rBed] = mins(x.bedtime)
        it[rMorningOn] = x.morningOn; it[rMorning] = mins(x.morning); it[rQuietOn] = x.quietOn; it[rQuietFrom] = mins(x.quietFrom); it[rQuietTo] = mins(x.quietTo)
        it[rLowOn] = x.lowBatteryOn; it[rLowAt] = x.lowBatteryAt; it[rStaleOn] = x.staleOn
    }

    /** The day (epoch day) a band alert was last sent, so each comes at most once a day. */
    suspend fun alertDay(kind: String): Long? = context.uiPrefsStore.data.map { it[longPreferencesKey("alert_$kind")] }.first()
    suspend fun setAlertDay(kind: String, day: Long) = context.uiPrefsStore.edit { it[longPreferencesKey("alert_$kind")] = day }

    val theme: Flow<ThemeMode> = context.uiPrefsStore.data.map { p ->
        p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
    }

    val prefs: Flow<UiPrefs> = context.uiPrefsStore.data.map { p ->
        UiPrefs(
            units = p[unitsKey]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.Metric,
            todayCards = p[cardsKey]?.let(::decodeTodayCards) ?: UiPrefs().todayCards,
            ringGoals = decodeRingGoals(p[ringGoalsKey]),
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.uiPrefsStore.edit { it[themeKey] = mode.name }

    suspend fun setPrefs(prefs: UiPrefs) = context.uiPrefsStore.edit {
        it[unitsKey] = prefs.units.name
        it[cardsKey] = prefs.todayCards.joinToString(",") { (c, on) -> "${c.name}:${if (on) 1 else 0}" }
        it[ringGoalsKey] = prefs.ringGoals.joinToString(",") { g -> g.name }
    }
}

internal fun decodeTodayCards(s: String): List<Pair<TodayCard, Boolean>> {
    val saved = s.split(",").mapNotNull { part ->
        val (name, on) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { TodayCard.valueOf(name) }.getOrNull()?.let { it to (on == "1") }
    }
    // Saved by the older Today screen, whose cards are gone: start fresh, but keep a hidden air card hidden.
    if (saved.none { it.first != TodayCard.Air }) return UiPrefs().todayCards.map { (c, on) -> c to (saved.firstOrNull { it.first == c }?.second ?: on) }
    // Cards added in a later app version are appended, switched off.
    return saved + TodayCard.entries.filter { c -> saved.none { it.first == c } }.map { it to false }
}

internal fun decodeRingGoals(s: String?): Set<GoalMetric> =
    s?.split(",")?.mapNotNull { runCatching { GoalMetric.valueOf(it) }.getOrNull() }?.filter { it in GoalCalendar.TODAY }?.toSet()
        ?.takeIf { it.isNotEmpty() } ?: GoalCalendar.CIRCLE
