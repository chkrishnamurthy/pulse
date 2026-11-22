package com.fitbittrack.pulse.ui.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation routes. One object per screen on the design canvas.
@Serializable data object ConnectRoute
@Serializable data object TodayRoute
@Serializable data object HeartRoute
@Serializable data object SleepRoute
@Serializable data object ActivityRoute
@Serializable data object TrendsRoute
@Serializable data object GoalsRoute
@Serializable data object SettingsRoute
