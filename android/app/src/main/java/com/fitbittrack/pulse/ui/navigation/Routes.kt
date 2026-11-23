package com.fitbittrack.pulse.ui.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation routes. One object per screen on the design canvas.
@Serializable data object ConnectRoute
@Serializable data object TodayRoute
@Serializable data object HeartRoute
@Serializable data object LiveHeartRoute
@Serializable data object SleepRoute
@Serializable data object ActivityRoute
@Serializable data object TrendsRoute
@Serializable data object CalendarRoute
@Serializable data object HealthRoute
@Serializable data object YouRoute
@Serializable data object GoalsRoute
@Serializable data object KnowMeRoute
@Serializable data object RemindersRoute
@Serializable data object MyBandRoute
@Serializable data object CalendarGoalsRoute
@Serializable data object CustomizeRoute
@Serializable data object SyncRoute
@Serializable data class ChatRoute(val question: String? = null)
@Serializable data object ChatSettingsRoute
@Serializable data class DayRoute(val epochDay: Long)
@Serializable data class MetricRoute(val kind: String)
