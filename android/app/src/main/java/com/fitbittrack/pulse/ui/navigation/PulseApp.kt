package com.fitbittrack.pulse.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fitbittrack.pulse.AuthState
import com.fitbittrack.pulse.MainViewModel
import com.fitbittrack.pulse.UiState
import com.fitbittrack.pulse.ui.DashboardViewModel
import com.fitbittrack.pulse.ui.DataLayerBridge
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.screens.ActivityScreen
import com.fitbittrack.pulse.ui.screens.ConnectScreen
import com.fitbittrack.pulse.ui.screens.GoalsScreen
import com.fitbittrack.pulse.ui.screens.HeartScreen
import com.fitbittrack.pulse.ui.screens.SettingsScreen
import com.fitbittrack.pulse.ui.screens.SleepScreen
import com.fitbittrack.pulse.ui.screens.TodayScreen
import com.fitbittrack.pulse.ui.screens.TrendsScreen
import com.fitbittrack.pulse.ui.theme.PulseTheme
import kotlin.reflect.KClass

private data class Tab(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector, val tint: @Composable () -> Color)

@Composable
fun PulseApp(state: UiState, viewModel: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val dash: DashboardViewModel = viewModel()

    // Real data after sign-in; sample data otherwise.
    LaunchedEffect(state.auth is AuthState.SignedIn) {
        dash.useRepository(if (state.auth is AuthState.SignedIn) DataLayerBridge.realRepository(context) else null)
    }

    // Google's consent screen: launched once whenever the view model asks for it.
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(result.data)
    }
    LaunchedEffect(state.pendingConsent) {
        state.pendingConsent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it).build())
            viewModel.consentLaunched()
        }
    }

    val isSample by dash.isSample.collectAsStateWithLifecycle()
    val sync by dash.sync.collectAsStateWithLifecycle()
    val goals by dash.goalsUi.collectAsStateWithLifecycle()

    val tabs = listOf(
        Tab(TodayRoute, TodayRoute::class, "Today", PulseIcons.Today) { MaterialTheme.colorScheme.onBackground },
        Tab(HeartRoute, HeartRoute::class, "Heart", PulseIcons.Heart) { PulseTheme.colors.heartLabel },
        Tab(SleepRoute, SleepRoute::class, "Sleep", PulseIcons.Sleep) { PulseTheme.colors.sleepLabel },
        Tab(ActivityRoute, ActivityRoute::class, "Activity", PulseIcons.Activity) { PulseTheme.colors.moveLabel },
        Tab(TrendsRoute, TrendsRoute::class, "Trends", PulseIcons.Trends) { MaterialTheme.colorScheme.onBackground },
    )
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = tabs.any { destination?.hasRoute(it.type) == true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { tab ->
                        val selected = destination?.hasRoute(tab.type) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = tab.tint(),
                                selectedTextColor = tab.tint(),
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Always start on Connect. Once Google has granted access, sign-in is silent and Connect passes straight through.
        NavHost(navController = nav, startDestination = ConnectRoute) {
            composable<ConnectRoute> {
                ConnectScreen(
                    auth = state.auth,
                    contentPadding = padding,
                    onConnect = viewModel::connect,
                    onContinue = { nav.navigate(TodayRoute) { popUpTo<ConnectRoute> { inclusive = true } } },
                    onExploreSample = { nav.navigate(TodayRoute) },
                )
            }
            composable<TodayRoute> {
                val today by dash.todayUi.collectAsStateWithLifecycle()
                today?.let {
                    TodayScreen(
                        today = it, prefs = state.prefs, sync = sync, isSample = isSample, contentPadding = padding,
                        onRefresh = dash::refresh,
                        onOpenSettings = { nav.navigate(SettingsRoute) },
                        onOpenGoals = { nav.navigate(GoalsRoute) },
                        onOpenHeart = { nav.navigate(HeartRoute) },
                        onOpenSleep = { nav.navigate(SleepRoute) },
                        onOpenActivity = { nav.navigate(ActivityRoute) },
                    )
                } ?: Loading(padding)
            }
            composable<HeartRoute> {
                val heart by dash.heartUi.collectAsStateWithLifecycle()
                heart?.let { HeartScreen(it, isSample, padding, dash::setHeartRange) } ?: Loading(padding)
            }
            composable<SleepRoute> {
                val sleep by dash.sleepUi.collectAsStateWithLifecycle()
                sleep?.let { SleepScreen(it, isSample, padding) } ?: Loading(padding)
            }
            composable<ActivityRoute> {
                val activity by dash.activityUi.collectAsStateWithLifecycle()
                activity?.let { ActivityScreen(it, state.prefs.units, isSample, padding) } ?: Loading(padding)
            }
            composable<TrendsRoute> {
                val range by dash.reportRangeState.collectAsStateWithLifecycle()
                val days by dash.reportDays.collectAsStateWithLifecycle()
                TrendsScreen(range, days, goals.dailySteps, goals.sleepMinutes, isSample, padding, dash::setReportRange)
            }
            composable<GoalsRoute> {
                GoalsScreen(
                    goals = goals, prefs = state.prefs, contentPadding = padding,
                    onBack = { nav.popBackStack() },
                    onGoalsChange = { dash.setGoals(it) },
                    onPrefsChange = { viewModel.setPrefs(it) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    auth = state.auth, themeMode = state.themeMode, sync = sync, contentPadding = padding,
                    onBack = { nav.popBackStack() },
                    onThemeChange = { viewModel.setTheme(it) },
                    onSyncNow = dash::refresh,
                    onSignInAgain = viewModel::connect,
                    onDeleteData = dash::deleteLocalData,
                    onSignOut = {
                        dash.deleteLocalData()
                        viewModel.signOut()
                        nav.navigate(ConnectRoute) { popUpTo(0) }
                    },
                )
            }
        }
    }
}

@Composable
private fun Loading(padding: androidx.compose.foundation.layout.PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
