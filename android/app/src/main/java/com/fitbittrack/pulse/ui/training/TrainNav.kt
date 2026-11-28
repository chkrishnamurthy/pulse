package com.fitbittrack.pulse.ui.training

import androidx.compose.runtime.mutableIntStateOf
import com.fitbittrack.pulse.ui.navigation.TrainAnimationsRoute
import com.fitbittrack.pulse.training.EdbEntity
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.fitbittrack.pulse.direct.LiveState
import com.fitbittrack.pulse.domain.HeartRateSample
import com.fitbittrack.pulse.reminders.notificationsAllowed
import com.fitbittrack.pulse.training.ExerciseEntity
import com.fitbittrack.pulse.training.PlanEdits
import com.fitbittrack.pulse.training.SessionEntity
import com.fitbittrack.pulse.training.TrainingGraph
import com.fitbittrack.pulse.training.days
import com.fitbittrack.pulse.ui.DirectViewModel
import com.fitbittrack.pulse.ui.navigation.BackupRoute
import com.fitbittrack.pulse.ui.navigation.BeforeRoute
import com.fitbittrack.pulse.ui.navigation.CalendarRoute
import com.fitbittrack.pulse.ui.navigation.ExerciseEditRoute
import com.fitbittrack.pulse.ui.navigation.ExercisePick
import com.fitbittrack.pulse.ui.navigation.ExerciseRoute
import com.fitbittrack.pulse.ui.navigation.ExercisesRoute
import com.fitbittrack.pulse.ui.navigation.FinishedRoute
import com.fitbittrack.pulse.ui.navigation.HistoryRoute
import com.fitbittrack.pulse.ui.navigation.ImportRoute
import com.fitbittrack.pulse.ui.navigation.LiveWorkoutRoute
import com.fitbittrack.pulse.ui.navigation.LoadingPage
import com.fitbittrack.pulse.ui.navigation.MusclesRoute
import com.fitbittrack.pulse.ui.navigation.NewPlanRoute
import com.fitbittrack.pulse.ui.navigation.PauseRoute
import com.fitbittrack.pulse.ui.navigation.PlanDayRoute
import com.fitbittrack.pulse.ui.navigation.PlanRoute
import com.fitbittrack.pulse.ui.navigation.PlansRoute
import com.fitbittrack.pulse.ui.navigation.ProgressRoute
import com.fitbittrack.pulse.ui.navigation.RestRoute
import com.fitbittrack.pulse.ui.navigation.ScheduleDraftRoute
import com.fitbittrack.pulse.ui.navigation.TrainRoute
import com.fitbittrack.pulse.ui.navigation.TrainSettingsRoute
import com.fitbittrack.pulse.ui.navigation.back
import com.fitbittrack.pulse.ui.navigation.open
import com.fitbittrack.pulse.ui.navigation.openTab
import com.fitbittrack.pulse.ui.navigation.page
import com.fitbittrack.pulse.ui.navigation.replace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** What the training pages need from the rest of the app. */
class TrainHost(
    val nav: NavHostController, val vm: TrainingViewModel,
    /** Backup copies and imports from another app. */
    val files: TrainingFilesViewModel,
    val direct: DirectViewModel, val isSample: () -> Boolean,
    /** Saved heart rate of one day, or nothing while Pulse shows sample data. */
    val heart: (LocalDate) -> Flow<List<HeartRateSample>>,
    val showWorkouts: () -> Unit, val barless: (Int) -> Unit,
)

/** Opens the Calendar with its Workouts chip on. */
private fun TrainHost.schedule() { showWorkouts(); nav.openTab(CalendarRoute) }

private fun TrainHost.startEmpty() = vm.startEmpty { nav.open(LiveWorkoutRoute) }

/** Workout actions for the Calendar page. [onMove] shows the move sheet there. */
fun TrainHost.calendarActions(onMove: (String) -> Unit) = WorkoutCalendarActions(
    month = vm::setMonth,
    before = { nav.open(BeforeRoute(it.id)) },
    move = { onMove(it.id) },
    session = { nav.open(FinishedRoute(it)) },
    undo = { vm.undo(it) },
    train = { nav.open(TrainRoute) },
)

@Composable
fun TrainHost.MoveDialog(plannedId: String, onDismiss: () -> Unit) {
    val plan by vm.planOn.collectAsStateWithLifecycle()
    val on = plan ?: return
    val p = on.planned.firstOrNull { it.id == plannedId } ?: return
    val go = { onDismiss(); nav.open(ScheduleDraftRoute) }
    MoveSheet(
        p, on, LocalDate.now(), onDismiss,
        onMove = { if (vm.proposeMove(
            p.id,
            it,
        )) go() }, onShift = { if (vm.proposeShift(p.id)) go() }, onSwap = { if (vm.proposeSwap(p.id, it)) go() }, onSkip = { if (vm.proposeSkip(p.id)) go() },
        onPin = { vm.togglePin(p.id); onDismiss() },
    )
}

/** Asks for notifications once, the first time they are useful. */
@Composable
private fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

fun NavGraphBuilder.trainingPages(h: TrainHost, padding: PaddingValues) {
    val nav = h.nav
    val vm = h.vm
    val files = h.files
    val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    page<TrainRoute> {
        val home by vm.home.collectAsStateWithLifecycle()
        LaunchedEffect(home?.active?.id) { if (home?.active != null) vm.ensureService() }
        home?.let {
            TrainScreen(
                it,
                padding,
                onBack = { nav.back() },
                onBefore = { p -> nav.open(BeforeRoute(p.id)) },
                onLive = { nav.open(LiveWorkoutRoute) },
                onEmpty = { h.startEmpty() },
                onPlans = { nav.open(PlansRoute) },
                onNewPlan = { nav.open(NewPlanRoute) },
                onSchedule = { h.schedule() },
                onExercises = { nav.open(ExercisesRoute.browse()) },
                onProgress = { nav.open(ProgressRoute) },
                onImport = { nav.open(ImportRoute) },
                onSettings = { nav.open(TrainSettingsRoute) },
                onBackup = { nav.open(BackupRoute) },
            )
        } ?: LoadingPage(padding)
    }

    // ---------- exercises ----------

    page<ExercisesRoute> { entry ->
        val choice = entry.toRoute<ExercisesRoute>().choice
        val all by vm.exercises.collectAsStateWithLifecycle()
        val progress by vm.progress.collectAsStateWithLifecycle()
        val used = remember(progress) { progress?.workoutsPerExercise.orEmpty() }
        // Swapping in a running workout first asks whether the change is for today only.
        var swapTo by remember { mutableStateOf<ExerciseEntity?>(null) }
        val live by vm.live.collectAsStateWithLifecycle()
        val ties by vm.ties.collectAsStateWithLifecycle()
        ExercisesScreen(
            all, used, ties, if (choice == null) "Exercises" else "Choose an exercise", padding,
            onBack = { nav.back() }, onNew = { nav.open(ExerciseEditRoute()) }, onFavourite = { vm.toggleFavourite(it) },
            onPick = { e ->
                when (choice) {
                    null -> nav.open(ExerciseRoute(e.id))
                    is ExercisePick.SwapInSession -> swapTo = e
                    is ExercisePick.AddToSession -> { vm.addExercise(choice.sessionId, e.id); nav.back() }
                    ExercisePick.AddSlot -> { vm.addSlot(e); nav.back() }
                    is ExercisePick.SwapSlot -> { vm.swapSlot(choice.slotId, e.id); nav.back() }
                    is ExercisePick.ForImport -> { files.importChoose(choice.key, e.id); nav.back() }
                }
            },
        )
        val swap = choice as? ExercisePick.SwapInSession
        val to = swapTo
        if (swap != null && to != null) {
            SwapSheet(to.name, live?.session?.plannedId != null, { swapTo = null }) { fromNow ->
                vm.swap(swap.sessionExerciseId, to.id, fromNow)
                swapTo = null
                nav.back()
            }
        }
    }

    page<ExerciseEditRoute> { entry ->
        val id = entry.toRoute<ExerciseEditRoute>().id
        val all by vm.exercises.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val old by remember(id) { if (id == null) flowOf(null) else vm.exercise(id) }.collectAsStateWithLifecycle(null)
        if (id != null && old == null) LoadingPage(padding)
        else ExerciseEditScreen(
            old, settings.unit, remember(all) { all.filter { it.custom }.map { it.name.lowercase() }.toSet() }, padding,
            onBack = { nav.back() },
            onSave = { e -> vm.saveExercise(e) { nav.back() } },
            onRemove = {
                id?.let { vm.removeExercise(it) }
                // Back to the list, past the exercise's own page.
                if (!nav.popBackStack(ExercisesRoute.browse(), false)) nav.back()
            },
        )
    }

    page<ExerciseRoute> { entry ->
        val id = entry.toRoute<ExerciseRoute>().id
        val e by remember(id) { vm.exercise(id) }.collectAsStateWithLifecycle(null)
        val progress by vm.progress.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val steps by produceState(emptyList<String>(), id) { value = vm.steps(id) }
        val ties by vm.ties.collectAsStateWithLifecycle()
        val edb by produceState<EdbEntity?>(null, id, ties) { value = vm.edbFor(id) }
        ExerciseScreen(
            e, edb, remember(
                progress,
                id,
            ) { progress?.all.orEmpty().filter { it.exerciseId == id } }, steps, settings, LocalDate.now(), padding, onBack = { nav.back() },
            onFavourite = { vm.toggleFavourite(id) }, onEdit = { nav.open(ExerciseEditRoute(id)) }, onSession = { nav.open(FinishedRoute(it)) },
        )
    }

    // ---------- plans ----------

    page<PlansRoute> {
        val programs by vm.programs.collectAsStateWithLifecycle()
        val plan by vm.planOn.collectAsStateWithLifecycle()
        val enrollments by vm.enrollments.collectAsStateWithLifecycle()
        PlansScreen(programs, plan, enrollments, padding, onBack = { nav.back() }, onNew = { nav.open(NewPlanRoute) }, onOpen = { nav.open(PlanRoute(it)) })
    }

    page<NewPlanRoute> {
        NewPlanScreen(firstDay, padding, onBack = { nav.back() }) { name, weeks, days, easy ->
            vm.createPlan(name, weeks, days, easy) { id ->
                vm.rememberDays(id, days)
                nav.replace<NewPlanRoute>(PlanRoute(id))
            }
        }
    }

    page<PlanRoute> { entry ->
        val id = entry.toRoute<PlanRoute>().id
        val program by remember(id) { vm.program(id) }.collectAsStateWithLifecycle(null)
        val doc by remember(id) { vm.plan(id) }.collectAsStateWithLifecycle(null)
        val on by vm.planOn.collectAsStateWithLifecycle()
        val enrollments by vm.enrollments.collectAsStateWithLifecycle()
        val p = program
        val d = doc
        if (p == null || d == null) LoadingPage(padding)
        else PlanScreen(
            p, d, on?.takeIf { it.program.id == id }, firstDay, LocalDate.now(),
            vm.daysOf(id).ifEmpty { enrollments.firstOrNull { it.programId == id }?.days().orEmpty() }, padding, onBack = { nav.back() },
            onDay = { w, day -> vm.openDay(id, d, w, day); nav.open(PlanDayRoute(id, w, day)) }, onSave = { vm.savePlan(id, it) }, newId = vm::newId,
            onStart = { s, days -> vm.startPlan(
                id,
                s,
                days,
            ) }, onStop = { vm.stopPlan() }, onPause = { nav.open(PauseRoute) }, onRestart = { vm.restartFrom(it) },
            onRename = { vm.renamePlan(id, it) }, onCopy = { vm.copyPlan(id); nav.back() }, onRemove = { vm.removePlan(id); nav.back() },
        )
    }

    page<PlanDayRoute> { entry ->
        val r = entry.toRoute<PlanDayRoute>()
        val doc by remember(r.id) { vm.plan(r.id) }.collectAsStateWithLifecycle(null)
        val all by vm.exercises.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val draft by vm.dayDraft.collectAsStateWithLifecycle()
        // After Android closed Pulse in the background the draft is gone: start again from what is saved.
        LaunchedEffect(doc) { doc?.let { vm.openDay(r.id, it, r.week, r.day) } }
        val d = draft?.takeIf { it.programId == r.id && it.week == r.week && it.day == r.day }
        val plan = doc
        if (d == null || plan == null) LoadingPage(padding)
        else PlanDayScreen(
            d.now, r.week, plan.weeks.size, remember(all) { all.associateBy { it.id } }, settings.unit, padding, d.dirty,
            onBack = { vm.dropDay(); nav.back() },
            onChange = vm::changeDay,
            onAdd = { nav.open(ExercisesRoute.pick(ExercisePick.AddSlot)) },
            onSwap = { nav.open(ExercisesRoute.pick(ExercisePick.SwapSlot(it))) },
            onSave = { how -> vm.putDay(r.id, plan, r.week, r.day, d.now, how); vm.dropDay(); nav.back() },
            canRemove = plan.weeks[r.week.coerceIn(plan.weeks.indices)].days.size > 1,
            onRemoveDay = { vm.savePlan(r.id, PlanEdits.removeDay(plan, r.week, r.day)); vm.dropDay(); nav.back() },
        )
    }

    // ---------- schedule ----------

    page<ScheduleDraftRoute> {
        val draft by vm.draft.collectAsStateWithLifecycle()
        ScheduleDraftScreen(draft, padding, onBack = { vm.dropDraft(); nav.back() }, onSave = { vm.saveDraft(); h.schedule() })
    }

    page<PauseRoute> {
        val plan by vm.planOn.collectAsStateWithLifecycle()
        PauseScreen(
            plan,
            LocalDate.now(),
            padding,
            onBack = { nav.back() },
        ) { from, until, gently -> if (vm.proposePause(from, until, gently)) nav.open(ScheduleDraftRoute) }
    }

    page<BeforeRoute> { entry ->
        val id = entry.toRoute<BeforeRoute>().plannedId
        val planned by remember(id) { vm.planned(id) }.collectAsStateWithLifecycle(null)
        val plan by vm.planOn.collectAsStateWithLifecycle()
        val rows by remember(id, plan?.enrollment?.revisionId) { vm.preview(id) }.collectAsStateWithLifecycle(null)
        val settings by vm.settings.collectAsStateWithLifecycle()
        val progress by vm.progress.collectAsStateWithLifecycle()
        var move by remember { mutableStateOf(false) }
        val p = planned
        if (p == null) LoadingPage(padding)
        else {
            val locale = locale()
            val last = progress?.lastNamed(p.title)?.let { s -> lastTimeLabel(s, locale) }
            BeforeScreen(
                p, plan, rows, last, settings, LocalDate.now(), padding,
                onBack = { nav.back() },
                onEdit = {
                    plan?.let { on ->
                        vm.openDay(on.program.id, on.doc, p.week, p.day)
                        nav.open(PlanDayRoute(on.program.id, p.week, p.day))
                    }
                },
                onMove = { move = true }, onExercise = { nav.open(ExerciseRoute(it)) },
                onStart = { vm.startPlanned(id) { nav.replace<BeforeRoute>(LiveWorkoutRoute) } },
            )
            // Saves this workout's animations now, so they play during it, even without internet later.
            LaunchedEffect(rows) { rows?.let { r -> vm.saveAnimations(r.map { it.slot.exerciseId }) } }
            if (move) h.MoveDialog(id) { move = false }
        }
    }

    // ---------- live workout ----------

    page<LiveWorkoutRoute> {
        DisposableEffect(Unit) { h.barless(1); onDispose { h.barless(-1) } }
        val live by vm.live.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val bpm = h.liveBpm()
        val view = LocalView.current
        DisposableEffect(settings.keepScreenOn) { view.keepScreenOn = settings.keepScreenOn; onDispose { view.keepScreenOn = false } }
        val ask = rememberNotificationAsk()
        var asked by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(live?.session?.id) {
            if (live != null) { vm.ensureService(); if (!asked) { asked = true; ask() } }
            else { delay(2_000); if (vm.live.value == null) nav.back() }
        }
        val ui = live
        if (ui == null) LoadingPage(padding)
        else LiveWorkoutScreen(
            ui, settings, bpm, padding,
            LiveActions(
                tick = { s, done -> vm.tick(
                    s.id,
                    done,
                ) }, change = { id, c -> vm.changeSet(id, c) }, addSet = { vm.addSet(it) }, deleteSet = { vm.deleteSet(it) },
                addExercise = { nav.open(ExercisesRoute.pick(ExercisePick.AddToSession(ui.session.id))) },
                swap = { nav.open(ExercisesRoute.pick(ExercisePick.SwapInSession(it.entity.id))) },
                remove = { vm.removeExerciseFromSession(it) }, move = { id, up -> vm.moveExercise(id, up) }, open = { nav.open(ExerciseRoute(it)) },
                addRest = { vm.addRest(it) }, skipRest = { vm.skipRest() },
            ),
            onBack = { nav.back() }, onRest = { nav.open(RestRoute) },
            onFinish = { vm.finish(ui.session.id) { id -> nav.replace<LiveWorkoutRoute>(FinishedRoute(id, fresh = true)) } },
            onDiscard = { vm.discard(ui.session.id); nav.back() },
        )
    }

    page<RestRoute> {
        DisposableEffect(Unit) { h.barless(1); onDispose { h.barless(-1) } }
        val live by vm.live.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val bpm = h.liveBpm()
        val state by h.direct.live.collectAsStateWithLifecycle()
        RestScreen(
            live,
            settings,
            bpm,
            (state as? LiveState.Live)?.history.orEmpty(),
            padding,
            onBack = { nav.popBackStack(RestRoute, true) },
            onAdd = { vm.addRest(it) },
            onSkip = { vm.skipRest() },
        )
    }

    page<FinishedRoute> { entry ->
        val r = entry.toRoute<FinishedRoute>()
        val ui by remember(r.id) { vm.session(r.id) }.collectAsStateWithLifecycle(null)
        val progress by vm.progress.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val s = ui?.session
        val heart by produceState(emptyList<HeartRateSample>(), s?.id, s?.endMs) {
            val end = s?.endMs
            value = if (s == null || end == null || s.timeUnsure || h.isSample()) emptyList()
            else runCatching { h.heart(LocalDate.parse(s.date)).first().filter { it.time in Instant.ofEpochMilli(s.startMs)..Instant.ofEpochMilli(end) } }.getOrDefault(emptyList())
        }
        FinishedScreen(
            ui, r.fresh, progress, heart, settings, padding,
            onBack = { nav.back() },
            onFeeling = { vm.setFeeling(r.id, it) },
            onChange = { id, c -> vm.changeSet(id, c) },
            onDeleteSet = { vm.deleteSet(it) },
            onDelete = { vm.discard(r.id); nav.back() },
            onExercise = { nav.open(ExerciseRoute(it)) },
        )
    }

    // ---------- progress ----------

    page<ProgressRoute> {
        val ui by vm.progress.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val plan by vm.planOn.collectAsStateWithLifecycle()
        val weight by produceState<Pair<Instant, Int>?>(null, settings.healthConnect) { value = if (settings.healthConnect) vm.bodyWeight() else null }
        ProgressScreen(
            ui, settings, { from -> plan?.skippedSince(from) ?: 0 }, weight, padding,
            onBack = { nav.back() },
            onExercise = { nav.open(ExerciseRoute(it)) },
            onExercises = { nav.open(ExercisesRoute.browse()) },
            onMuscles = { nav.open(MusclesRoute) },
            onHistory = { nav.open(HistoryRoute) },
        )
    }

    page<MusclesRoute> {
        val ui by vm.progress.collectAsStateWithLifecycle()
        val all by vm.exercises.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        MusclesScreen(
            ui,
            { a, b -> ui?.let { vm.muscleSets(it, a, b, all) }.orEmpty() },
            settings,
            padding,
            onBack = { nav.back() },
        ) { m, r -> vm.setSettings(settings.copy(ranges = settings.ranges + (m to r))) }
    }

    page<HistoryRoute> {
        val ui by vm.progress.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val deleted by vm.deleted.collectAsStateWithLifecycle()
        HistoryScreen(ui, deleted, settings, padding, onBack = { nav.back() }, onOpen = { nav.open(FinishedRoute(it)) }, onRestore = { vm.restoreSession(it) })
    }

    // ---------- data ----------

    page<ImportRoute> {
        val step by files.import.collectAsStateWithLifecycle()
        val batches by files.batches.collectAsStateWithLifecycle()
        val all by vm.exercises.collectAsStateWithLifecycle()
        ImportScreen(
            step, batches.filter { it.workouts > 0 }, remember(all) { all.associateBy { it.id } }, padding,
            ImportActions(
                file = { files.importFile(it) }, back = files::importBack, another = files::importNextSample,
                unit = { files.importUnit(it) }, choose = files::importChoose,
                pick = { nav.open(ExercisesRoute.pick(ExercisePick.ForImport(it))) }, guesses = files::importUseGuesses,
                check = { files.importCheck() }, save = { files.importSave() }, undo = { files.undoImport(it) },
            ),
            onBack = { files.importReset(); nav.back() },
            onDone = { files.importReset(); nav.replace<ImportRoute>(HistoryRoute) },
        )
    }

    page<BackupRoute> {
        val settings by vm.settings.collectAsStateWithLifecycle()
        val note by files.note.collectAsStateWithLifecycle()
        DisposableEffect(Unit) { onDispose { files.clearNote() } }
        BackupScreen(
            settings, note, padding, onBack = { nav.back() },
            onSave = { files.backupTo(it) }, onRestore = { files.restoreFrom(it) }, onSettings = { vm.setSettings(it) },
            onDeleteAll = { files.deleteAll() }, onNoteSeen = files::clearNote,
        )
    }

    page<TrainSettingsRoute> {
        val settings by vm.settings.collectAsStateWithLifecycle()
        val anim by vm.exerciseDbStatus.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { vm.exerciseDbStatusNow() }
        TrainSettingsScreen(
            settings, remember { vm.healthAvailable() }, vm::healthAllowed, padding, onBack = { nav.back() }, onSettings = { vm.setSettings(it) },
            onBackup = { nav.open(BackupRoute) }, onImport = { nav.open(ImportRoute) },
            onAnimations = { nav.open(TrainAnimationsRoute) },
            animationsText = if (anim.listed == 0) "From ExerciseDB, once online" else "%,d of %,d saved, %s".format(anim.saved, anim.listed, megabytes(anim.bytes)),
            askNotifications = rememberNotificationAsk(),
        )
    }

    page<TrainAnimationsRoute> {
        val status by vm.exerciseDbStatus.collectAsStateWithLifecycle()
        var round by remember { mutableIntStateOf(0) }
        val close by produceState<List<Pair<ExerciseEntity, EdbEntity>>?>(null, status.listed, round) { value = vm.closeNames() }
        LaunchedEffect(Unit) { vm.exerciseDbStatusNow() }
        AnimationsScreen(
            status, close, padding, onBack = { nav.back() },
            onSaveAll = { vm.saveAllAnimations() }, onStop = { vm.stopSavingAll() }, onDelete = { vm.deleteAnimations() },
            onTryAgain = { vm.refreshExerciseDb() },
            onAnswer = { p, e, same -> vm.answerCloseName(p.id, e.id, same).invokeOnCompletion { round++ } },
        )
    }
}

/**
 * Live heart rate while a workout is on screen, if the user switched that on and Pulse shows real data. Unlike other pages
 * the connection stays when the phone is locked: the open workout shows it in its notification. It ends with the page.
 */
@Composable
private fun TrainHost.liveBpm(): Int? {
    val real = !isSample()
    DisposableEffect(real) {
        if (real) direct.watchHeart()
        onDispose { if (real) direct.unwatchHeart(); TrainingGraph.liveBpm.value = null }
    }
    val state by direct.live.collectAsStateWithLifecycle()
    val bpm = (state as? LiveState.Live)?.bpmAt(Instant.now())?.takeIf { real }
    LaunchedEffect(bpm) { TrainingGraph.liveBpm.value = bpm }
    return bpm
}

/** "Tue 12 Mar, 54 min": when a workout was last done, and how long it took when that is known. */
private fun lastTimeLabel(s: SessionEntity, locale: Locale): String {
    val end = s.endMs
    val day = LocalDate.parse(s.date).short(locale)
    return if (s.timeUnsure || end == null) day else "$day, ${(end - s.startMs) / 60_000} min"
}
