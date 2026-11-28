package com.fitbittrack.pulse.ui.training

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.training.EnrollmentEntity
import com.fitbittrack.pulse.training.EdbEntity
import com.fitbittrack.pulse.training.EdbStatus
import com.fitbittrack.pulse.training.ExerciseCatalog
import com.fitbittrack.pulse.training.ExerciseEntity
import com.fitbittrack.pulse.training.Muscle
import com.fitbittrack.pulse.training.PlanDay
import com.fitbittrack.pulse.training.PlanDoc
import com.fitbittrack.pulse.training.PlanEdits
import com.fitbittrack.pulse.training.PlannedEntity
import com.fitbittrack.pulse.training.PlannedItem
import com.fitbittrack.pulse.training.PlannedState
import com.fitbittrack.pulse.training.ProgramEntity
import com.fitbittrack.pulse.training.ProgressRules
import com.fitbittrack.pulse.training.ScheduleRules
import com.fitbittrack.pulse.training.Scope
import com.fitbittrack.pulse.training.SessionEntity
import com.fitbittrack.pulse.training.SetEntity
import com.fitbittrack.pulse.training.Slot
import com.fitbittrack.pulse.training.SlotPreview
import com.fitbittrack.pulse.training.Track
import com.fitbittrack.pulse.training.TrainingGraph
import com.fitbittrack.pulse.training.TrainingHealth
import com.fitbittrack.pulse.training.TrainingReminder
import com.fitbittrack.pulse.training.TrainingSettings
import com.fitbittrack.pulse.training.UndoEntity
import com.fitbittrack.pulse.training.WorkoutService
import com.fitbittrack.pulse.training.dated
import com.fitbittrack.pulse.training.days
import com.fitbittrack.pulse.training.decodePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID


/** Everything the training pages show and do. Reads only the phone's own training database. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repo = TrainingGraph.repository(app)
    private val dao = repo.dao
    private val prefs = TrainingGraph.prefs(app)
    private val health = TrainingHealth(app)
    private val scope = TrainingGraph.scope(app)

    private fun <T> Flow<T>.keep(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    private val today: Flow<LocalDate> = flow {
        while (true) { emit(LocalDate.now()); delay(30_000) }
    }.distinctUntilChanged()

    private val firstDay: DayOfWeek get() = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    val settings: StateFlow<TrainingSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, TrainingSettings())
    val exercises: StateFlow<List<ExerciseEntity>> = dao.exercises().keep(emptyList())
    /** Pulse exercise id to ExerciseDB id, for exercises tied by name. */
    val ties: StateFlow<Map<String, String>> = repo.edbIds().keep(emptyMap())
    private val exerciseDb = TrainingGraph.exerciseDb(app)
    val exerciseDbStatus: StateFlow<EdbStatus> = exerciseDb.status
    suspend fun edbFor(id: String): EdbEntity? = withContext(Dispatchers.IO) { repo.edbFor(id) }
    suspend fun closeNames(): List<Pair<ExerciseEntity, EdbEntity>> = withContext(Dispatchers.IO) { repo.closeNames() }
    fun answerCloseName(exerciseId: String, edbId: String, same: Boolean) = scope.launch { repo.answerCloseName(exerciseId, edbId, same) }
    /** Saves the animations of a workout's exercises now, on any network, so they play during it. */
    fun saveAnimations(exerciseIds: List<String>) = scope.launch { exerciseDb.saveFor(exerciseIds, wifiOnly = false) }
    fun saveAllAnimations() = exerciseDb.startSaveAll()
    fun stopSavingAll() = exerciseDb.stopSaveAll()
    fun deleteAnimations() = exerciseDb.deleteAnimations()
    fun refreshExerciseDb() = scope.launch { exerciseDb.tryAgainNow(); exerciseDb.syncList(force = true); exerciseDb.refreshStatus() }
    fun exerciseDbStatusNow() = scope.launch { exerciseDb.refreshStatus() }
    val active: StateFlow<SessionEntity?> = dao.activeSession().keep(null)

    init {
        // The reminder always matches the settings.
        viewModelScope.launch {
            prefs.settings.map { listOf(
                it.reminderOn,
                it.backupReminder,
                it.reminderAt,
            ) }.distinctUntilChanged().collect { TrainingReminder.schedule(app, prefs.settings.first()) }
        }
    }

    // ---------- home ----------

    private val planNow: Flow<PlanOn?> = dao.activeEnrollment().flatMapLatest { e ->
        if (e == null) flowOf(null)
        else combine(
            dao.programFlow(e.programId),
            dao.planned(e.id),
        ) { p, rows -> p?.let { prog -> dao.revision(e.revisionId)?.let { PlanOn(e, prog, decodePlan(it.doc), rows) } } }
    }.flowOn(Dispatchers.Default)

    val planOn: StateFlow<PlanOn?> = planNow.keep(null)

    val progress: StateFlow<ProgressUi?> = combine(dao.history(), dao.finished(), dao.totals(), today) { rows, sessions, totals, day ->
        val all = rows.map { it.dated() }
        val bests = ProgressRules.bests(all)
        val recordSessions = bests.values.map { it.sessionId }.toSet()
        val by = totals.associateBy { it.sessionId }
        val (now, best) = ProgressRules.weekStreak(sessions.map { LocalDate.parse(it.date) }.toSet(), day, firstDay)
        ProgressUi(
            all,
            rows,
            bests,
            rows.associate { it.exerciseId to it.exerciseName },
            sessions.map { HistoryItem(it, by[it.id], it.id in recordSessions) },
            now,
            best,
            day,
            firstDay,
        )
    }.flowOn(Dispatchers.Default).keep(null)

    val home: StateFlow<TrainHome?> = combine(
        combine(planNow, dao.activeSession(), dao.programs()) { p, a, all -> Triple(p, a, all) },
        dao.finished(), dao.exercises(), today, progress,
    ) { (plan, act, programs), sessions, ex, day, prog ->
        val start = day.minusDays(((day.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
        val doneDays = sessions.map { it.date }.toSet()
        val open = plan?.planned.orEmpty().filter { it.state == PlannedState.Planned }.map { it.date }.toSet()
        val skipped = plan?.planned.orEmpty().filter { it.state == PlannedState.Skipped }.map { it.date }.toSet()
        TrainHome(
            day, plan, act, programs.isNotEmpty(), sessions.isNotEmpty(),
            (0L..6L).map { i -> start.plusDays(i).let { d -> WeekDay(d, mark(d.toString(), doneDays, open, skipped), d == day) } },
            ex.count { !it.custom }, ex.count { it.custom }, prog?.bests?.values?.count { it.date >= start } ?: 0,
        )
    }.flowOn(Dispatchers.Default).keep(null)

    private fun mark(d: String, done: Set<String>, open: Set<String>, skipped: Set<String>) = when (d) {
        in done -> DayMark.Done
        in open -> DayMark.Planned
        in skipped -> DayMark.Skipped
        else -> DayMark.None
    }

    // ---------- exercises ----------

    fun toggleFavourite(id: String) = scope.launch { repo.toggleFavourite(id) }
    fun saveExercise(e: ExerciseEntity, then: (String) -> Unit = {}) = viewModelScope.launch { then(repo.saveExercise(e)) }
    fun removeExercise(id: String) = scope.launch { repo.removeExercise(id) }
    fun exercise(id: String): Flow<ExerciseEntity?> = dao.exerciseFlow(id)
    suspend fun steps(id: String): List<String> = withContext(Dispatchers.IO) { runCatching { ExerciseCatalog.steps(app, id) }.getOrDefault(emptyList()) }

    // ---------- plans ----------

    val programs: StateFlow<List<ProgramEntity>> = dao.programs().keep(emptyList())
    val enrollments: StateFlow<List<EnrollmentEntity>> = dao.enrollments().keep(emptyList())

    fun program(id: String): Flow<ProgramEntity?> = dao.programFlow(id)
    fun plan(id: String): Flow<PlanDoc?> = repo.plan(id)

    fun createPlan(
        title: String,
        weeks: Int,
        days: Set<DayOfWeek>,
        easy: Boolean,
        then: (String) -> Unit,
    ) = viewModelScope.launch { then(repo.createPlan(title, weeks, days, easy)) }
    fun renamePlan(id: String, title: String) = scope.launch { repo.renamePlan(id, title) }
    fun copyPlan(id: String) = scope.launch { repo.copyPlan(id) }
    fun removePlan(id: String) = scope.launch { repo.removePlan(id) }
    fun startPlan(id: String, start: LocalDate, days: Set<DayOfWeek>) = scope.launch { repo.startPlan(id, start, days); exerciseDb.saveSoon() }
    fun stopPlan() = scope.launch { repo.stopPlan() }
    fun restartFrom(week: Int) = scope.launch { repo.restartFrom(week, LocalDate.now()) }

    fun savePlan(id: String, doc: PlanDoc) = scope.launch { repo.savePlan(id, doc) }

    /** Saves a changed day; [scope] says how far. Returns how many weeks change. */
    fun putDay(
        programId: String,
        doc: PlanDoc,
        week: Int,
        day: Int,
        changed: PlanDay,
        how: Scope,
    ) = savePlan(programId, PlanEdits.putDay(doc, week, day, changed, how))

    /** A day of a plan while it is being changed. Nothing is saved until the user says how far. */
    data class DayDraft(val programId: String, val week: Int, val day: Int, val saved: PlanDay, val now: PlanDay) {
        val dirty: Boolean get() = saved != now
    }

    private val _dayDraft = MutableStateFlow<DayDraft?>(null)
    val dayDraft: StateFlow<DayDraft?> = _dayDraft.asStateFlow()

    /** Opens a day. A draft of the same day with changes is kept: the exercise list comes back to it. */
    fun openDay(programId: String, doc: PlanDoc, week: Int, day: Int) {
        val old = _dayDraft.value
        if (old != null && old.programId == programId && old.week == week && old.day == day && old.dirty) return
        _dayDraft.value = doc.day(week, day)?.let { DayDraft(programId, week, day, it, it) }
    }

    fun changeDay(d: PlanDay) { _dayDraft.value = _dayDraft.value?.copy(now = d) }
    fun dropDay() { _dayDraft.value = null }

    fun addSlot(e: ExerciseEntity) {
        val d = _dayDraft.value ?: return
        val slot = when (e.track) {
            Track.Time -> Slot(newId(), e.id, repsMin = 30, repsMax = 45, restSec = e.restSec)
            Track.Reps -> Slot(newId(), e.id, restSec = e.restSec)
            Track.WeightReps -> Slot(newId(), e.id, restSec = e.restSec)
        }
        changeDay(d.now.copy(slots = d.now.slots + slot))
    }

    fun swapSlot(slotId: String, exerciseId: String) {
        val d = _dayDraft.value ?: return
        changeDay(d.now.copy(slots = d.now.slots.map { if (it.id == slotId) it.copy(exerciseId = exerciseId) else it }))
    }

    /** Training days chosen for a plan that has not started yet. */
    private val chosenDays = HashMap<String, Set<DayOfWeek>>()
    fun rememberDays(programId: String, days: Set<DayOfWeek>) { chosenDays[programId] = days }
    fun daysOf(programId: String): Set<DayOfWeek> = chosenDays[programId].orEmpty()

    // ---------- schedule ----------

    private val monthShown = MutableStateFlow(YearMonth.now())
    fun setMonth(m: YearMonth) { monthShown.value = m }

    val workoutMonth: StateFlow<WorkoutMonth?> = combine(
        monthShown, planOn, dao.finished(), today, dao.lastUndo(),
    ) { m, plan, sessions, day, undo ->
        val from = m.atDay(1); val to = m.atEndOfMonth()
        val done = sessions.groupBy { LocalDate.parse(it.date) }.filterKeys { it in from..to }
        val rows = plan?.planned.orEmpty().filter { it.state != PlannedState.Done }.groupBy { LocalDate.parse(it.date) }.filterKeys { it in from..to }
        val marks = (done.keys + rows.keys).associateWith { d -> if (d in done) DayMark.Done else if (rows[d].orEmpty().any { it.state == PlannedState.Planned }) DayMark.Planned else DayMark.Skipped }
        val (streak, best) = ProgressRules.weekStreak(sessions.map { LocalDate.parse(it.date) }.toSet(), day, firstDay)
        WorkoutMonth(
            m, day, firstDay, marks, marks.keys.associateWith { d -> (done[d]?.map { it.title } ?: rows[d].orEmpty().map { it.title }).joinToString(", ") },
            rows, done, plan?.next, streak, best, undo?.takeIf { it.enrollmentId == plan?.enrollment?.id },
        )
    }.flowOn(Dispatchers.Default).keep(null)

    fun planned(id: String): Flow<PlannedEntity?> = dao.plannedFlow(id)

    private val _draft = MutableStateFlow<ScheduleDraft?>(null)
    val draft: StateFlow<ScheduleDraft?> = _draft.asStateFlow()

    private fun propose(
        label: String,
        easy: (List<PlannedItem>) -> Map<String, Int> = { emptyMap() },
        change: (List<PlannedItem>, Set<DayOfWeek>) -> List<PlannedItem>,
    ): Boolean {
        val p = planOn.value ?: return false
        val after = change(p.items, p.enrollment.days())
        _draft.value = ScheduleDraft(label, p.items, after, p.planned.associate { it.id to it.title }, easy(after))
        return true
    }

    fun proposeMove(id: String, to: LocalDate) = propose("Workout moved") { l, _ -> ScheduleRules.moveOne(l, id, to) }
    fun proposeShift(id: String) = propose("Plan shifted") { l, d -> ScheduleRules.shift(l, id, d, LocalDate.now()) }
    fun proposeSwap(a: String, b: String) = propose("Workouts swapped") { l, _ -> ScheduleRules.swap(l, a, b) }
    fun proposeSkip(id: String) = propose("Workout skipped") { l, _ -> ScheduleRules.skip(l, id) }

    /** [gently] = the first week after the pause at 90% of the weight. */
    fun proposePause(from: LocalDate, until: LocalDate, gently: Boolean) = propose(
        "Plan paused",
        easy = { after ->
            if (!gently) emptyMap()
            else after.filter { it.state == PlannedState.Planned && it.date > until }.sortedBy { it.seq }.let { l ->
                val first = l.firstOrNull()?.date
                l.filter { first != null && it.date < first.plusDays(7) }.associate { it.id to 90 }
            }
        },
    ) { l, d -> ScheduleRules.pause(l, from, until, d) }

    fun dropDraft() { _draft.value = null }

    fun saveDraft() {
        val d = _draft.value ?: return
        _draft.value = null
        scope.launch { repo.saveSchedule(d.label, d.after, d.easy) }
    }

    fun undo(u: UndoEntity) = scope.launch { repo.undoSchedule(u) }
    fun togglePin(id: String) = scope.launch { repo.togglePin(id) }

    // ---------- before and during a workout ----------

    fun preview(plannedId: String): Flow<List<SlotPreview>> = dao.plannedFlow(plannedId).map { p -> p?.let { repo.previewDay(it) }.orEmpty() }.flowOn(Dispatchers.Default)

    fun startPlanned(id: String, then: () -> Unit) = viewModelScope.launch {
        if (repo.startPlanned(id) != null) { WorkoutService.start(app); then() }
    }

    fun startEmpty(then: () -> Unit) = viewModelScope.launch { repo.startEmpty(); WorkoutService.start(app); then() }

    /** The workout notification, again after Pulse was closed. */
    fun ensureService() = viewModelScope.launch { if (dao.activeSessionNow() != null) WorkoutService.start(app) }

    private fun detail(s: SessionEntity): Flow<LiveUi> =
        combine(dao.sessionExercises(s.id), dao.setsOfSession(s.id), dao.history(), dao.exercises()) { ex, sets, hist, all ->
            val byEx = sets.groupBy { it.sessionExerciseId }
            val info = all.associateBy { it.id }
            LiveUi(s, ex.map { x ->
                val before = hist.filter { it.exerciseId == x.exerciseId && it.sessionId != s.id && it.startMs < s.startMs }
                val lastId = before.maxByOrNull { it.startMs }?.sessionId
                LiveExercise(x, info[x.exerciseId], byEx[x.id].orEmpty(), before.filter { it.sessionId == lastId && it.kind.counts })
            })
        }.flowOn(Dispatchers.Default)

    val live: StateFlow<LiveUi?> = dao.activeSession().flatMapLatest { s -> if (s == null) flowOf(null) else detail(s) }.keep(null)

    fun session(id: String): Flow<LiveUi?> = dao.sessionFlow(id).flatMapLatest { s -> if (s == null) flowOf(null) else detail(s) }

    fun tick(setId: String, done: Boolean) = scope.launch { repo.tick(setId, done) }
    fun changeSet(setId: String, change: (SetEntity) -> SetEntity) = scope.launch { repo.changeSet(setId, change) }
    fun addSet(id: String) = scope.launch { repo.addSet(id) }
    fun deleteSet(id: String) = scope.launch { repo.deleteSet(id) }
    fun addExercise(sessionId: String, exerciseId: String) = scope.launch { repo.addToSession(sessionId, exerciseId) }
    fun removeExerciseFromSession(id: String) = scope.launch { repo.removeFromSession(id) }
    fun moveExercise(id: String, up: Boolean) = scope.launch { repo.moveInSession(id, up) }
    fun swap(id: String, exerciseId: String, fromNow: Boolean) = scope.launch { repo.swap(id, exerciseId, fromNow) }
    fun addRest(sec: Int) = scope.launch { dao.activeSessionNow()?.let { repo.addRest(it.id, sec) } }
    fun skipRest() = scope.launch { dao.activeSessionNow()?.let { repo.skipRest(it.id) } }

    fun finish(id: String, then: (String) -> Unit) = viewModelScope.launch {
        val s = repo.finish(id) ?: return@launch
        scope.launch { health.write(s) }
        then(s.id)
    }

    fun discard(id: String) = scope.launch { repo.discard(id); health.remove(id) }
    fun restoreSession(id: String) = scope.launch { repo.restore(id); dao.session(id)?.let { health.write(it) } }
    /** Thrown away in the last 30 days, with at least one finished set. */
    val deleted: StateFlow<List<SessionEntity>> = dao.discarded().keep(emptyList())
    fun setFeeling(id: String, f: Int) = scope.launch { repo.setFeeling(id, f) }

    // ---------- progress ----------

    fun muscleSets(ui: ProgressUi, from: LocalDate, to: LocalDate, all: List<ExerciseEntity>): Map<Muscle, Double> {
        val by = all.associateBy { it.id }
        return ProgressRules.muscleSets(ui.all.filter { it.date in from..to }, { by[it]?.muscle }, { by[it]?.alsoTrains.orEmpty() })
    }

    suspend fun hasOpenWorkout(): Boolean = dao.activeSessionNow() != null

    suspend fun bodyWeight(): Pair<Instant, Int>? = health.bodyWeight()

    // ---------- settings ----------

    fun setSettings(s: TrainingSettings) = scope.launch { prefs.set(s) }
    fun healthAvailable(): Boolean = health.available()
    suspend fun healthAllowed(): Boolean = health.canWrite()

    fun newId(): String = UUID.randomUUID().toString()
}
