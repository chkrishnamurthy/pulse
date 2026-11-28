package com.fitbittrack.pulse.ui.navigation

import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.fitbittrack.pulse.AuthState
import com.fitbittrack.pulse.ConfigSaver
import com.fitbittrack.pulse.MainViewModel
import com.fitbittrack.pulse.UiState
import com.fitbittrack.pulse.chat.CHAT_FILE
import com.fitbittrack.pulse.chat.ChatPrefs
import com.fitbittrack.pulse.chat.ChatStore
import com.fitbittrack.pulse.chat.ChatViewModel
import com.fitbittrack.pulse.chat.TrainingAnswers
import com.fitbittrack.pulse.chat.loadChatData
import com.fitbittrack.pulse.chat.loadTrainingChat
import com.fitbittrack.pulse.direct.GoogleHealthApp
import com.fitbittrack.pulse.direct.LiveState
import com.fitbittrack.pulse.reminders.ReminderPrefs
import com.fitbittrack.pulse.reminders.Reminders
import com.fitbittrack.pulse.training.TrainingGraph
import com.fitbittrack.pulse.ui.AirViewModel
import com.fitbittrack.pulse.ui.DashboardViewModel
import com.fitbittrack.pulse.ui.DataLayerBridge
import com.fitbittrack.pulse.ui.DirectViewModel
import com.fitbittrack.pulse.ui.components.FitText
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.model.HomeLive
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.PersonalPrefs
import com.fitbittrack.pulse.ui.model.ReportRange
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import com.fitbittrack.pulse.ui.screens.ActivityScreen
import com.fitbittrack.pulse.ui.screens.AirScreen
import com.fitbittrack.pulse.ui.screens.CalendarGoalsScreen
import com.fitbittrack.pulse.ui.screens.CalendarScreen
import com.fitbittrack.pulse.ui.screens.ChatScreen
import com.fitbittrack.pulse.ui.screens.ChatSettingsScreen
import com.fitbittrack.pulse.ui.screens.ConnectScreen
import com.fitbittrack.pulse.ui.screens.CustomizeScreen
import com.fitbittrack.pulse.ui.screens.DayScreen
import com.fitbittrack.pulse.ui.screens.FasterDataCard
import com.fitbittrack.pulse.ui.screens.GoalsScreen
import com.fitbittrack.pulse.ui.screens.HealthScreen
import com.fitbittrack.pulse.ui.screens.HeartScreen
import com.fitbittrack.pulse.ui.screens.KnowMeScreen
import com.fitbittrack.pulse.ui.screens.LiveHeartScreen
import com.fitbittrack.pulse.ui.screens.MetricScreen
import com.fitbittrack.pulse.ui.screens.MyBandScreen
import com.fitbittrack.pulse.ui.screens.RemindersScreen
import com.fitbittrack.pulse.ui.screens.SleepScreen
import com.fitbittrack.pulse.ui.screens.SyncScreen
import com.fitbittrack.pulse.ui.screens.TodayScreen
import com.fitbittrack.pulse.ui.screens.TrendsScreen
import com.fitbittrack.pulse.ui.screens.WidgetGalleryScreen
import com.fitbittrack.pulse.ui.screens.WidgetSetupScreen
import com.fitbittrack.pulse.ui.screens.YouScreen
import com.fitbittrack.pulse.ui.screens.rememberNow
import com.fitbittrack.pulse.ui.training.MoveDialog
import com.fitbittrack.pulse.ui.training.TrainHost
import com.fitbittrack.pulse.ui.training.TrainTodayCard
import com.fitbittrack.pulse.ui.training.TrainingFilesViewModel
import com.fitbittrack.pulse.ui.training.TrainingViewModel
import com.fitbittrack.pulse.ui.training.WorkoutCalendar
import com.fitbittrack.pulse.ui.training.calendarActions
import com.fitbittrack.pulse.ui.training.trainingPages
import com.fitbittrack.pulse.ui.widget.art.ArtWidgets
import com.fitbittrack.pulse.ui.widget.art.WidgetConfig
import com.fitbittrack.pulse.ui.widget.art.WidgetDesign
import com.fitbittrack.pulse.ui.widget.art.WidgetOpen
import com.fitbittrack.pulse.ui.widget.art.WidgetStore
import com.fitbittrack.pulse.ui.widget.art.WidgetTap
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** The five tabs. Each tab has one first page; pages opened from it sit on top of it. */
private data class Tab(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector)

/** Pages that always hide the tab bar. Others hide it while open via `barless`. */
private val fullScreen = listOf(ConnectRoute::class, LiveHeartRoute::class, ChatRoute::class)

private val tabs = listOf(
    Tab(TodayRoute, TodayRoute::class, "Today", PulseIcons.Today),
    Tab(CalendarRoute, CalendarRoute::class, "Calendar", PulseIcons.Calendar),
    Tab(HealthRoute, HealthRoute::class, "Health", PulseIcons.Health),
    Tab(TrendsRoute, TrendsRoute::class, "Trends", PulseIcons.Trends),
    Tab(YouRoute, YouRoute::class, "You", PulseIcons.You),
)

@Composable
fun PulseApp(state: UiState, viewModel: MainViewModel, widgetOpen: WidgetOpen? = null, onWidgetOpened: () -> Unit = {}) {
    // Wait until we know whether an account was connected before, so Connect never flashes.
    if (state.auth is AuthState.Checking) {
        LoadingPage(PaddingValues())
        return
    }

    val nav = rememberNavController()
    val context = LocalContext.current
    val dash: DashboardViewModel = viewModel()
    val direct: DirectViewModel = viewModel()
    val air: AirViewModel = viewModel()
    val train: TrainingViewModel = viewModel()
    val trainFiles: TrainingFilesViewModel = viewModel()
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
    val showBar = destination != null && barless == 0 && fullScreen.none { destination.hasRoute(it) }

    val uiPrefs = remember { UiPrefsStore(context.applicationContext) }
    // For saves started by a tap: they finish even if the page closes right after.
    val scope = rememberCoroutineScope()

    // ---------- Ask Pulse ----------
    val chat: ChatViewModel = viewModel(factory = viewModelFactory {
        initializer {
            ChatViewModel(
                data = { day -> loadChatData(dash.repository, day, uiPrefs.prefs.first().units) },
                // Sample chats are never kept.
                prefs = { uiPrefs.chat.first().let { if (dash.isSample.value) it.copy(keep = false) else it } },
                store = ChatStore(File(context.applicationContext.noBackupFilesDir, CHAT_FILE)),
                training = { q, day -> TrainingAnswers.answer(q, loadTrainingChat(TrainingGraph.repository(context.applicationContext), day)) },
            )
        }
    })
    val chatPrefs by remember { uiPrefs.chat }.collectAsStateWithLifecycle(ChatPrefs())
    // Sample and real answers are never mixed, and signing out empties the chat.
    var chatSample by remember { mutableStateOf(isSample) }
    LaunchedEffect(isSample) { if (chatSample != isSample) { chatSample = isSample; if (isSample) chat.deleteChats() else chat.newChat() } }
    val ask: (String?) -> Unit = { nav.open(ChatRoute(it)) }

    // What the user told Pulse about themselves. The calendar follows their choice of goals.
    val personal by remember { uiPrefs.personal }.collectAsStateWithLifecycle(PersonalPrefs())
    LaunchedEffect(personal.calendarGoals) { dash.setCoreGoals(personal.calendarGoals) }
    // Reminders: the booked work always matches what the user chose.
    val reminders by remember { uiPrefs.reminders }.collectAsStateWithLifecycle(ReminderPrefs())
    LaunchedEffect(reminders) { Reminders.apply(context.applicationContext, reminders) }

    // ---------- training ----------
    var workoutsChip by rememberSaveable { mutableStateOf(false) }
    val trainHost = remember(nav, train, trainFiles, direct) {
        TrainHost(
            nav, train, trainFiles, direct,
            isSample = { dash.isSample.value },
            heart = { dash.repository.savedHeartRate(it) },
            showWorkouts = { workoutsChip = true },
            barless = { barless += it },
        )
    }

    val openMetric: (MetricKind) -> Unit = { nav.open(MetricRoute(it.name)) }
    val openDay: (LocalDate) -> Unit = { nav.open(DayRoute(it.toEpochDay())) }

    // A tap on a home-screen widget. Before an account is connected there is nothing to open.
    LaunchedEffect(widgetOpen, destination != null) {
        if (widgetOpen == null || destination == null) return@LaunchedEffect
        onWidgetOpened()
        // Training needs no account: its notification and widget open also before one is connected.
        if (destination.hasRoute<ConnectRoute>()) {
            if (widgetOpen.tap == WidgetTap.Train) nav.goHome() else return@LaunchedEffect
        }
        nav.openTab(TodayRoute)
        when (widgetOpen.tap) {
            WidgetTap.Today -> Unit
            WidgetTap.Goals -> nav.openTab(CalendarRoute)
            WidgetTap.Sleep -> nav.open(SleepRoute)
            WidgetTap.Heart -> nav.open(HeartRoute)
            WidgetTap.Trends -> nav.openTab(TrendsRoute)
            WidgetTap.Ask -> nav.open(ChatRoute(widgetOpen.question))
            // In its own scope: this effect ends as soon as the tap is marked as handled.
            WidgetTap.Train -> {
                nav.open(TrainRoute)
                scope.launch { if (train.hasOpenWorkout()) nav.open(LiveWorkoutRoute) }
            }
        }
    }

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
                // Location is used only while Today shows the air card.
                if (state.prefs.todayCards.any { (card, on) -> card == TodayCard.Air && on }) {
                    LifecycleStartEffect(air) { air.start(); onStopOrDispose { air.stop() } }
                }
                val airOn by air.enabled.collectAsStateWithLifecycle()
                val airState by air.state.collectAsStateWithLifecycle()
                if (!isSample) LifecycleStartEffect(direct) { direct.watchHeart(); onStopOrDispose { direct.unwatchHeart() } }
                val liveSteps by direct.liveSteps.collectAsStateWithLifecycle()
                val liveHeart by direct.live.collectAsStateWithLifecycle()
                val lane by direct.laneData.collectAsStateWithLifecycle()
                val canOpenHealth = remember { GoogleHealthApp.installed(context) }
                val now = rememberNow()
                today?.let {
                    TodayScreen(
                        today = it, prefs = state.prefs, sync = sync, isSample = isSample, contentPadding = padding, week = week,
                        lowBatteryAt = reminders.lowBatteryAt, airOn = airOn, air = airState,
                        live = if (isSample) HomeLive() else HomeLive(
                            steps = liveSteps?.takeIf { l -> l.day == it.date && l.added > 0 }?.steps,
                            bandUpTo = listOfNotNull(sync.lastTrackerAt, lane?.newestSteps).maxOrNull(),
                            bpm = (liveHeart as? LiveState.Live)?.bpmAt(now),
                        ),
                        onGetNewest = {
                            if (GoogleHealthApp.open(context)) Toast.makeText(context, "In Google Health, pull down on Today to sync your band. Then come back.", Toast.LENGTH_LONG).show()
                        }.takeIf { !isSample && canOpenHealth },
                        onRefresh = { dash.refresh(); air.refresh() },
                        onConnect = viewModel::connect,
                        onOpenSync = { nav.open(SyncRoute) },
                        onCalendar = { nav.openTab(CalendarRoute) },
                        onDay = openDay,
                        onMetric = openMetric,
                        onOpenSleep = { nav.open(SleepRoute) },
                        onOpenHeart = { nav.open(HeartRoute) },
                        onOpenBand = { nav.open(MyBandRoute) },
                        onOpenAir = { nav.open(AirRoute) },
                        onAsk = ask,
                        train = {
                            val home by train.home.collectAsStateWithLifecycle()
                            TrainTodayCard(home, onOpen = { nav.open(TrainRoute) }, onStart = {
                                when {
                                    home?.active != null -> nav.open(LiveWorkoutRoute)
                                    else -> home?.plan?.next?.let { nav.open(BeforeRoute(it.id)) }
                                }
                            })
                        },
                    )
                } ?: LoadingPage(padding)
            }
            page<CalendarRoute> {
                val month by dash.calendarUi.collectAsStateWithLifecycle()
                val workouts by train.workoutMonth.collectAsStateWithLifecycle()
                var moving by rememberSaveable { mutableStateOf<String?>(null) }
                month?.let {
                    CalendarScreen(
                        ui = it, units = state.prefs.units, isSample = isSample, contentPadding = padding,
                        onConnect = viewModel::connect,
                        onMetric = dash::setCalendarMetric, onMonth = dash::setCalendarMonth, onDay = openDay,
                        onEditGoals = { nav.open(GoalsRoute) },
                        workoutsOn = workoutsChip, onWorkouts = { on -> workoutsChip = on },
                        workouts = { workouts?.let { w -> WorkoutCalendar(w, trainHost.calendarActions { id -> moving = id }) } },
                    )
                } ?: LoadingPage(padding)
                moving?.let { id -> trainHost.MoveDialog(id) { moving = null } }
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
                } ?: LoadingPage(padding)
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
                    onReminders = { nav.open(RemindersRoute) }, onBand = { nav.open(MyBandRoute) },
                    remindersLabel = reminders.timedSummary,
                    onKnowMe = { nav.open(KnowMeRoute) }, onCalendarGoals = { nav.open(CalendarGoalsRoute) },
                    calendarGoalsLabel = personal.calendarGoals.sortedBy { it.ordinal }.joinToString(", ") { it.label },
                    onGoals = { nav.open(GoalsRoute) }, onCustomize = { nav.open(CustomizeRoute) }, onSync = { nav.open(SyncRoute) },
                    onChat = { nav.open(ChatSettingsRoute) }, onWidgets = { nav.open(WidgetsRoute) }, onTraining = { nav.open(TrainSettingsRoute) },
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
                HeartScreen(heart, personal.maxHeartRate, isSample, padding, dash::setHeartRange, onOpenLive = { nav.open(LiveHeartRoute) }, onBack = { nav.back() }, onMetric = openMetric, onAsk = ask)
            }
            page<LiveHeartRoute> {
                DisposableEffect(Unit) { barless++; onDispose { barless-- } }
                LifecycleStartEffect(direct) { direct.watchHeart(); onStopOrDispose { direct.unwatchHeart() } }
                val live by direct.live.collectAsStateWithLifecycle()
                val onHome by direct.liveHeartOn.collectAsStateWithLifecycle()
                LiveHeartScreen(
                    state = live, onHome = onHome, contentPadding = padding,
                    onHomeChange = { direct.setLiveHeart(it) },
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
                val trained by train.progress.collectAsStateWithLifecycle()
                ActivityScreen(activity, state.prefs.units, padding, onBack = { nav.back() }, inTrain = { w -> trained?.sameVisit(w.startMs, w.endMs)?.title })
            }
            page<GoalsRoute> {
                GoalsScreen(goals = goals, units = state.prefs.units, contentPadding = padding, onBack = { nav.back() }, onGoalsChange = { dash.setGoals(it) })
            }
            page<AirRoute> {
                LifecycleStartEffect(air) { air.start(); onStopOrDispose { air.stop() } }
                val airOn by air.enabled.collectAsStateWithLifecycle()
                val airState by air.state.collectAsStateWithLifecycle()
                airOn?.let { AirScreen(it, airState, state.prefs.units, padding, onBack = { nav.back() }, onEnable = air::setEnabled, onRefresh = air::refresh) }
            }
            page<RemindersRoute> {
                RemindersScreen(reminders, padding, onBack = { nav.back() }) { scope.launch { uiPrefs.setReminders(it) } }
            }
            page<MyBandRoute> {
                MyBandScreen(sync, isSample, reminders, padding, onBack = { nav.back() }) { scope.launch { uiPrefs.setReminders(it) } }
            }
            page<KnowMeRoute> {
                KnowMeScreen(personal, padding, onBack = { nav.back() }) { scope.launch { uiPrefs.setPersonal(it) } }
            }
            page<CalendarGoalsRoute> {
                CalendarGoalsScreen(personal.calendarGoals, goals, state.prefs.units, padding, onBack = { nav.back() }) { scope.launch { uiPrefs.setPersonal(personal.copy(calendarGoals = it)) } }
            }
            page<CustomizeRoute> {
                CustomizeScreen(prefs = state.prefs, contentPadding = padding, onBack = { nav.back() }, onPrefsChange = { viewModel.setPrefs(it) })
            }
            page<WidgetsRoute> {
                // Read again each time the page comes back: a widget may have been added or removed meanwhile.
                var placed by remember { mutableStateOf(ArtWidgets.placed(context)) }
                LifecycleResumeEffect(Unit) { placed = ArtWidgets.placed(context); onPauseOrDispose {} }
                WidgetGalleryScreen(placed, padding, onBack = { nav.back() }, onPick = { nav.open(WidgetSetupRoute(design = it.name)) }, onEdit = { nav.open(WidgetSetupRoute(id = it)) })
            }
            page<WidgetSetupRoute> { entry ->
                val route = entry.toRoute<WidgetSetupRoute>()
                val store = remember { WidgetStore(context) }
                val saved = remember { if (route.id != 0) store.saved(route.id) else null }
                var config by rememberSaveable(stateSaver = ConfigSaver) { mutableStateOf(saved ?: WidgetConfig(WidgetDesign.named(route.design) ?: WidgetDesign.Bloom)) }
                var note by rememberSaveable { mutableStateOf<String?>(null) }
                // Placed: show it at once. The home screen places a widget only when it is open, and Google's own
                // one can crash on a long queue of widgets that wait for that.
                var before by rememberSaveable { mutableIntStateOf(-1) }
                LifecycleResumeEffect(Unit) {
                    if (before in 0 until ArtWidgets.count(context)) { before = -1; note = "Added to your home screen."; ArtWidgets.showHome(context) }
                    onPauseOrDispose {}
                }
                WidgetSetupScreen(
                    config = config, choices = if (saved != null) WidgetDesign.bySize(saved.design.size) else emptyList(), contentPadding = padding,
                    action = if (saved != null) "Save" else "Add to home screen", note = note,
                    onBack = { nav.back() }, onChange = { config = it; note = null },
                    onAction = { data ->
                        if (saved != null) {
                            store.save(route.id, config)
                            scope.launch { ArtWidgets.refreshAll(context.applicationContext) }
                            nav.back()
                        } else if (ArtWidgets.pin(context, config, data)) {
                            before = ArtWidgets.count(context)
                        } else {
                            note = "Your home screen can't add it from here. Hold an empty spot on it, choose Widgets, then Pulse."
                        }
                    },
                )
            }
            page<ChatRoute> { entry ->
                DisposableEffect(Unit) { barless++; onDispose { barless-- } }
                val chatState by chat.state.collectAsStateWithLifecycle()
                LaunchedEffect(chat) { chat.open() }
                // A question passed in from another page is asked once, also after rotation.
                var asked by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val q = entry.toRoute<ChatRoute>().question
                    if (!asked && q != null) { asked = true; chat.ask(q) }
                }
                ChatScreen(
                    state = chatState, isSample = isSample, contentPadding = padding,
                    onBack = { nav.back() }, onAsk = chat::ask, onNewChat = chat::newChat,
                )
            }
            page<ChatSettingsRoute> {
                ChatSettingsScreen(
                    prefs = chatPrefs, contentPadding = padding,
                    onBack = { nav.back() },
                    onPrefs = { p -> scope.launch { uiPrefs.setChat(p) } },
                    onDeleteChats = chat::deleteChats,
                )
            }
            trainingPages(trainHost, padding)
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
                        val stepsOn by direct.liveStepsOn.collectAsStateWithLifecycle()
                        val heartOn by direct.liveHeartOn.collectAsStateWithLifecycle()
                        val bandKnown by direct.bandKnown.collectAsStateWithLifecycle()
                        FasterDataCard(
                            status = lane, data = data, lastTrackerLabel = sync.lastTrackerLabel,
                            onSetHealthConnect = { direct.setHealthConnect(it) },
                            stepSensor = direct.stepSensor, stepsOn = stepsOn, onLiveSteps = { direct.setLiveSteps(it); if (it) direct.retrySteps() },
                            heartOn = heartOn, bandKnown = bandKnown, onLiveHeart = { direct.setLiveHeart(it) },
                            onOpenLiveHeart = { nav.open(LiveHeartRoute) },
                        )
                    },
                )
            }
        }
    }
}
