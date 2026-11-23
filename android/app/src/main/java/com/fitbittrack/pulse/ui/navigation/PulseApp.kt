package com.fitbittrack.pulse.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.fitbittrack.pulse.AuthState
import com.fitbittrack.pulse.MainViewModel
import com.fitbittrack.pulse.UiState
import com.fitbittrack.pulse.chat.CHAT_FILE
import com.fitbittrack.pulse.chat.ChatData
import com.fitbittrack.pulse.chat.ChatPrefs
import com.fitbittrack.pulse.chat.ChatStore
import com.fitbittrack.pulse.chat.ChatViewModel
import com.fitbittrack.pulse.chat.LiteRtModel
import com.fitbittrack.pulse.chat.ModelState
import com.fitbittrack.pulse.chat.ModelStore
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.ui.DashboardViewModel
import com.fitbittrack.pulse.ui.DataLayerBridge
import com.fitbittrack.pulse.ui.DirectViewModel
import com.fitbittrack.pulse.ui.components.FitText
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.ReportRange
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import com.fitbittrack.pulse.ui.screens.ActivityScreen
import com.fitbittrack.pulse.ui.screens.CalendarScreen
import com.fitbittrack.pulse.ui.screens.ChatScreen
import com.fitbittrack.pulse.ui.screens.ChatSettingsScreen
import com.fitbittrack.pulse.ui.screens.ChatSetupScreen
import com.fitbittrack.pulse.ui.screens.ConnectScreen
import com.fitbittrack.pulse.ui.screens.CustomizeScreen
import com.fitbittrack.pulse.ui.screens.DayScreen
import com.fitbittrack.pulse.ui.screens.FasterDataCard
import com.fitbittrack.pulse.ui.screens.GoalsScreen
import com.fitbittrack.pulse.ui.screens.HealthScreen
import com.fitbittrack.pulse.ui.screens.HeartScreen
import com.fitbittrack.pulse.ui.screens.LiveHeartScreen
import com.fitbittrack.pulse.ui.screens.MetricScreen
import com.fitbittrack.pulse.ui.screens.SleepScreen
import com.fitbittrack.pulse.ui.screens.SyncScreen
import com.fitbittrack.pulse.ui.screens.TodayScreen
import com.fitbittrack.pulse.ui.screens.TrendsScreen
import com.fitbittrack.pulse.ui.screens.YouScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.reflect.KClass

/** The five tabs. Each tab has one first page; pages opened from it sit on top of it. */
private data class Tab(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(TodayRoute, TodayRoute::class, "Today", PulseIcons.Today),
    Tab(CalendarRoute, CalendarRoute::class, "Calendar", PulseIcons.Calendar),
    Tab(HealthRoute, HealthRoute::class, "Health", PulseIcons.Health),
    Tab(TrendsRoute, TrendsRoute::class, "Trends", PulseIcons.Trends),
    Tab(YouRoute, YouRoute::class, "You", PulseIcons.You),
)

@Composable
fun PulseApp(state: UiState, viewModel: MainViewModel) {
    // Wait until we know whether an account was connected before, so Connect never flashes.
    if (state.auth is AuthState.Checking) {
        Loading(PaddingValues())
        return
    }

    val nav = rememberNavController()
    val context = LocalContext.current
    val dash: DashboardViewModel = viewModel()
    val direct: DirectViewModel = viewModel()
    val signedIn = state.auth is AuthState.SignedIn

    // Real (cached) data whenever an account is connected, even offline; sample data otherwise.
    LaunchedEffect(signedIn) {
        dash.useRepository(if (signedIn) DataLayerBridge.realRepository(context) else null)
    }

    // While Pulse is on screen: sync on open if the data is old, then keep today's numbers fresh.
    // Off while signing out, so nothing is written back after the local data is deleted.
    LifecycleStartEffect(dash, state.signingOut) {
        if (!state.signingOut) dash.startLiveRefresh()
        onStopOrDispose { dash.stopLiveRefresh() }
    }
    LifecycleStartEffect(direct, signedIn, state.signingOut) {
        if (signedIn && !state.signingOut) direct.startPolling()
        onStopOrDispose { direct.stopPolling() }
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

    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    // A page opened from a tab keeps that tab highlighted.
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(destination) {
        tabs.indexOfFirst { destination?.hasRoute(it.type) == true }.takeIf { it >= 0 }?.let { currentTab = it }
    }
    // Full-screen pages still on screen, also while one is being swiped away: the bar waits until it is gone.
    var barless by remember { mutableIntStateOf(0) }
    val showBar = destination != null && !destination.hasRoute<ConnectRoute>() && !destination.hasRoute<LiveHeartRoute>() && !destination.hasRoute<ChatRoute>() && barless == 0

    // ---------- Ask Pulse ----------
    val uiPrefs = remember { UiPrefsStore(context.applicationContext) }
    val modelStore = remember { ModelStore.get(context) }
    val chatScope = rememberCoroutineScope()
    val chat: ChatViewModel = viewModel(factory = viewModelFactory {
        initializer {
            ChatViewModel(
                data = { day -> chatData(dash.repository, day, uiPrefs.prefs.first().units) },
                // Sample chats are never kept.
                prefs = { uiPrefs.chat.first().let { if (dash.isSample.value) it.copy(keep = false) else it } },
                store = ChatStore(File(context.applicationContext.noBackupFilesDir, CHAT_FILE)),
                model = LiteRtModel(context.applicationContext.cacheDir),
                modelFile = { modelStore.file.takeIf { modelStore.state.value == ModelState.Ready } },
            )
        }
    })
    val modelState by modelStore.state.collectAsStateWithLifecycle()
    val chatPrefs by remember { uiPrefs.chat }.collectAsStateWithLifecycle(ChatPrefs())
    // Sample and real answers are never mixed, and signing out empties the chat.
    var chatSample by remember { mutableStateOf(isSample) }
    LaunchedEffect(isSample) { if (chatSample != isSample) { chatSample = isSample; if (isSample) chat.deleteChats() else chat.newChat() } }
    val ask: (String?) -> Unit = { nav.open(ChatRoute(it)) }

    val openMetric: (MetricKind) -> Unit = { nav.open(MetricRoute(it.name)) }
    val openDay: (LocalDate) -> Unit = { nav.open(DayRoute(it.toEpochDay())) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEachIndexed { i, tab ->
                        NavigationBarItem(
                            selected = i == currentTab,
                            onClick = { nav.openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { FitText(tab.label, MaterialTheme.typography.labelMedium, if (i == currentTab) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Connected before → start on Today (cached data, works offline). Otherwise → Connect.
        // Decided once: changing it later rebuilds the graph, which threw away the move to Connect after sign-out.
        val start: Any = rememberSaveable { signedIn }.let { if (it) TodayRoute else ConnectRoute }
        NavHost(
            navController = nav, startDestination = start,
            enterTransition = { fadeIn(tween(160)) }, exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(160)) }, popExitTransition = { fadeOut(tween(120)) },
            // Back swipe: the page follows the finger off the screen, and the page before lies still underneath.
            // (The default shrinks the page and leaves it floating until it suddenly disappears.)
            predictivePopEnterTransition = { EnterTransition.None },
            predictivePopExitTransition = { edge ->
                slideOutHorizontally(tween(240, easing = LinearEasing)) { width -> if (edge == BackEventCompat.EDGE_RIGHT) -width else width }
            },
        ) {
            page<ConnectRoute> {
                ConnectScreen(
                    auth = state.auth,
                    contentPadding = padding,
                    onConnect = viewModel::connect,
                    onContinue = { nav.goHome() },
                    onExploreSample = { nav.goHome() },
                )
            }

            // ---------- tabs ----------
            page<TodayRoute> {
                val today by dash.todayUi.collectAsStateWithLifecycle()
                val week by dash.weekGoals.collectAsStateWithLifecycle()
                today?.let {
                    TodayScreen(
                        today = it, prefs = state.prefs, sync = sync, isSample = isSample, contentPadding = padding, week = week,
                        onRefresh = dash::refresh,
                        onConnect = viewModel::connect,
                        onOpenSync = { nav.open(SyncRoute) },
                        onCustomize = { nav.open(CustomizeRoute) },
                        onCalendar = { nav.openTab(CalendarRoute) },
                        onDay = openDay,
                        onMetric = openMetric,
                        onOpenSleep = { nav.open(SleepRoute) },
                        onAsk = ask,
                    )
                } ?: Loading(padding)
            }
            page<CalendarRoute> {
                val month by dash.calendarUi.collectAsStateWithLifecycle()
                month?.let {
                    CalendarScreen(
                        ui = it, units = state.prefs.units, isSample = isSample, contentPadding = padding,
                        onConnect = viewModel::connect,
                        onMetric = dash::setCalendarMetric, onMonth = dash::setCalendarMonth, onDay = openDay,
                        onEditGoals = { nav.open(GoalsRoute) },
                    )
                } ?: Loading(padding)
            }
            page<HealthRoute> {
                val hub by dash.hubUi.collectAsStateWithLifecycle()
                hub?.let {
                    HealthScreen(
                        hub = it, units = state.prefs.units, isSample = isSample, contentPadding = padding,
                        onConnect = viewModel::connect,
                        onMetric = openMetric, onHeart = { nav.open(HeartRoute) }, onSleep = { nav.open(SleepRoute) },
                        onWorkouts = { nav.open(ActivityRoute) }, onLiveHeart = { nav.open(LiveHeartRoute) },
                    )
                } ?: Loading(padding)
            }
            page<TrendsRoute> {
                val range by dash.reportRangeState.collectAsStateWithLifecycle()
                val days by dash.reportDays.collectAsStateWithLifecycle()
                TrendsScreen(
                    range, days, goals.dailySteps, goals.sleepMinutes, state.prefs.units, isSample, padding, dash::setReportRange,
                    onConnect = viewModel::connect, onMetric = openMetric, onSleep = { nav.open(SleepRoute) }, onDay = openDay,
                )
            }
            page<YouRoute> {
                YouScreen(
                    state = state, sync = sync, isSample = isSample, contentPadding = padding,
                    onConnect = viewModel::connect,
                    onGoals = { nav.open(GoalsRoute) }, onCustomize = { nav.open(CustomizeRoute) }, onSync = { nav.open(SyncRoute) },
                    onChat = { nav.open(ChatSettingsRoute) },
                    onThemeChange = { viewModel.setTheme(it) }, onPrefsChange = { viewModel.setPrefs(it) },
                )
            }

            // ---------- pages opened from a tab ----------
            page<DayRoute> { entry ->
                var epochDay by rememberSaveable { mutableStateOf(entry.toRoute<DayRoute>().epochDay) }
                val date = LocalDate.ofEpochDay(epochDay)
                val ui by remember(epochDay) { dash.day(date) }.collectAsStateWithLifecycle(null)
                DayScreen(
                    date = date, ui = ui, units = state.prefs.units, contentPadding = padding,
                    onBack = { nav.back() }, onDate = { epochDay = it.toEpochDay() }, onMetric = openMetric, onSleep = { nav.open(SleepRoute) },
                )
            }
            page<MetricRoute> { entry ->
                val kind = MetricKind.entries.firstOrNull { it.name == entry.toRoute<MetricRoute>().kind } ?: MetricKind.Steps
                var range by rememberSaveable { mutableStateOf(ReportRange.Week) }
                val ui by remember(kind, range) { dash.metric(kind, range) }.collectAsStateWithLifecycle(null)
                MetricScreen(
                    kind = kind, ui = ui, range = range, units = state.prefs.units, contentPadding = padding,
                    onBack = { nav.back() }, onRange = { range = it }, onDay = openDay, onAsk = ask,
                )
            }
            page<HeartRoute> {
                val heart by dash.heartUi.collectAsStateWithLifecycle()
                HeartScreen(heart, isSample, padding, dash::setHeartRange, onOpenLive = { nav.open(LiveHeartRoute) }, onBack = { nav.back() }, onMetric = openMetric, onAsk = ask)
            }
            page<LiveHeartRoute> {
                DisposableEffect(Unit) { barless++; onDispose { barless-- } }
                val live by direct.live.collectAsStateWithLifecycle()
                LiveHeartScreen(
                    state = live, contentPadding = padding,
                    onBack = { nav.back() },
                    onSearch = direct::searchBand, onConnect = direct::connectBand, onStop = direct::stopBand,
                )
            }
            page<SleepRoute> {
                val sleep by dash.sleepUi.collectAsStateWithLifecycle()
                SleepScreen(sleep, padding, onBack = { nav.back() }, onMetric = openMetric, onAsk = ask)
            }
            page<ActivityRoute> {
                val activity by dash.activityUi.collectAsStateWithLifecycle()
                ActivityScreen(activity, state.prefs.units, padding, onBack = { nav.back() })
            }
            page<GoalsRoute> {
                GoalsScreen(goals = goals, units = state.prefs.units, contentPadding = padding, onBack = { nav.back() }, onGoalsChange = { dash.setGoals(it) })
            }
            page<CustomizeRoute> {
                CustomizeScreen(prefs = state.prefs, contentPadding = padding, onBack = { nav.back() }, onPrefsChange = { viewModel.setPrefs(it) })
            }
            page<ChatRoute> { entry ->
                DisposableEffect(Unit) { barless++; onDispose { barless-- } }
                val chatState by chat.state.collectAsStateWithLifecycle()
                LifecycleStartEffect(chat) {
                    chat.open()
                    onStopOrDispose { chat.leave() }
                }
                // A question passed in from another page is asked once, also after rotation.
                var asked by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val q = entry.toRoute<ChatRoute>().question
                    if (!asked && q != null) { asked = true; chat.ask(q) }
                }
                ChatScreen(
                    state = chatState, model = modelState, isSample = isSample, contentPadding = padding,
                    onBack = { nav.back() }, onAsk = chat::ask, onStop = chat::stop, onNewChat = chat::newChat,
                    onSetup = { nav.open(ChatSetupRoute) },
                )
            }
            page<ChatSetupRoute> {
                ChatSetupScreen(
                    model = modelState, prefs = chatPrefs, contentPadding = padding,
                    onBack = { nav.back() },
                    onWifiOnly = { on -> chatScope.launch { uiPrefs.setChat(chatPrefs.copy(wifiOnly = on)) } },
                    onDownload = { modelStore.start(chatPrefs.wifiOnly) },
                    onCancel = { modelStore.delete() },
                    onDone = { nav.back() },
                )
            }
            page<ChatSettingsRoute> {
                ChatSettingsScreen(
                    model = modelState, prefs = chatPrefs, contentPadding = padding,
                    onBack = { nav.back() },
                    onPrefs = { p -> chatScope.launch { uiPrefs.setChat(p) } },
                    onSetup = { nav.open(ChatSetupRoute) },
                    onDeleteModel = { chat.releaseModel { modelStore.delete() } },
                    onDeleteChats = chat::deleteChats,
                )
            }
            page<SyncRoute> {
                SyncScreen(
                    state = state, sync = sync, contentPadding = padding,
                    onBack = { nav.back() },
                    onSyncNow = dash::refresh,
                    onConnect = viewModel::connect,
                    onRetryTest = viewModel::retryConnectionTest,
                    onDeleteData = dash::deleteLocalData,
                    onSignOut = {
                        viewModel.signOut {
                            nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } }
                        }
                    },
                    fasterData = {
                        val lane by direct.laneStatus.collectAsStateWithLifecycle()
                        val data by direct.laneData.collectAsStateWithLifecycle()
                        FasterDataCard(
                            status = lane, data = data, lastTrackerLabel = sync.lastTrackerLabel,
                            onSetHealthConnect = { direct.setHealthConnect(it) },
                            onOpenLiveHeart = { nav.open(LiveHeartRoute) },
                        )
                    },
                )
            }
        }
    }
}

/** The last 400 days, for chat. Read once per question. */
private suspend fun chatData(repo: HealthRepository, day: LocalDate, units: Units): ChatData = withContext(Dispatchers.Default) {
    val from = day.minusDays(ChatData.HISTORY_DAYS)
    ChatData(
        today = day,
        days = repo.daySummaries(from, day).first(),
        nights = repo.sleep(from, day).first(),
        workouts = repo.workouts(from, day).first(),
        goals = repo.goalHistory.first(),
        units = units,
    )
}

/**
 * A tab tap always shows that tab's first page: every page opened on top is closed, and nothing is saved to be
 * put back later. (Saving and restoring made Today come back as the page opened from it.)
 * Today stays at the bottom, so Back from any tab goes to Today, then leaves the app.
 */
private fun NavHostController.openTab(route: Any) {
    if (route == TodayRoute) {
        if (!popBackStack<TodayRoute>(inclusive = false)) navigate(TodayRoute) { launchSingleTop = true }
    } else {
        navigate(route) {
            popUpTo<TodayRoute>()
            launchSingleTop = true
        }
    }
}

/** One screen. It has its own solid background, so the screen below never shows through while one replaces the other. */
private inline fun <reified T : Any> NavGraphBuilder.page(noinline content: @Composable (NavBackStackEntry) -> Unit) = composable<T> { entry ->
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(entry) }
}

/** Opens a page on top of the current one. Double taps open it once. */
private fun NavHostController.open(route: Any) = navigate(route) { launchSingleTop = true }

/** Back arrow. A second quick tap can't empty the stack. */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
}

/** Go to Today and remove Connect from the back stack. */
private fun NavHostController.goHome() = navigate(TodayRoute) {
    popUpTo<ConnectRoute> { inclusive = true }
    launchSingleTop = true
}

@Composable
private fun Loading(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
