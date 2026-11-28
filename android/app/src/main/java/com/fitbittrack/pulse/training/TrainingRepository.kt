package com.fitbittrack.pulse.training

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun PlanDoc.encode(): String = json.encodeToString(PlanDoc.serializer(), this)
fun decodePlan(s: String): PlanDoc = json.decodeFromString(PlanDoc.serializer(), s)

fun EnrollmentEntity.days(): Set<DayOfWeek> = weekdays.split(",").mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 1..7 }?.let(DayOfWeek::of) }.toSet()
fun PlannedEntity.item() = PlannedItem(id, seq, LocalDate.parse(date), state, pinned)
fun HistoryRow.done() = DoneSet(kind, grams, reps, seconds, rpe10)
fun HistoryRow.dated() = DatedSet(sessionId, LocalDate.parse(date), exerciseId, done())

@Serializable
private data class SavedPlanned(val id: String, val date: String, val state: PlannedState, val easy: Int)

/** What is suggested for one slot of a plan, right now. */
data class SlotPreview(val slot: Slot, val exercise: ExerciseEntity?, val name: String, val suggestion: Suggestion, val last: List<DoneSet>)

/** The one place training data is read and written. Everything is on this phone. */
class TrainingRepository(
    private val context: Context,
    val dao: TrainingDao,
    val prefs: TrainingPrefs,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private fun newId() = UUID.randomUUID().toString()
    private fun today(): LocalDate = LocalDate.now(zone())
    private val lock = Mutex()

    // ---------- exercises ----------

    /** Adds catalogue exercises that are not there yet. The user's changes to them stay. */
    suspend fun seed() = lock.withLock {
        if (dao.seedCount() >= ExerciseCatalog.SIZE) return@withLock
        val rows = withContext(Dispatchers.IO) { ExerciseCatalog.load(context) }.map { it.entity() }
        dao.addMissingExercises(rows)
    }

    suspend fun saveExercise(e: ExerciseEntity): String {
        val row = if (e.id.isEmpty()) e.copy(id = "mine-" + newId(), custom = true) else e
        dao.putExercise(row)
        return row.id
    }

    suspend fun toggleFavourite(id: String) { dao.exercise(id)?.let { dao.putExercise(it.copy(favourite = !it.favourite)) } }

    /** Removes an own exercise. Once used it is only hidden, so old workouts keep it. */
    suspend fun removeExercise(id: String) {
        val e = dao.exercise(id) ?: return
        if (!e.custom) return
        if (dao.timesUsed(id) > 0) dao.putExercise(e.copy(archived = true)) else dao.deleteCustom(id)
    }

    // ---------- plans ----------

    fun plan(programId: String): Flow<PlanDoc?> = dao.latestRevision(programId).map { it?.let { r -> decodePlan(r.doc) } }

    suspend fun createPlan(title: String, weeks: Int, weekdays: Set<DayOfWeek>, easyWeeks: Boolean): String {
        val pid = newId()
        val t = now().toEpochMilli()
        dao.putProgram(ProgramEntity(pid, title.trim().ifEmpty { "My plan" }, weekdays.size, t))
        dao.addRevision(RevisionEntity(newId(), pid, 1, t, PlanDoc.empty(weeks, weekdays.size, easyWeeks, ::newId).encode()))
        return pid
    }

    suspend fun renamePlan(id: String, title: String) { dao.program(id)?.let { if (title.isNotBlank()) dao.putProgram(it.copy(title = title.trim())) } }

    suspend fun copyPlan(id: String): String? {
        val p = dao.program(id) ?: return null
        val rev = dao.latestRevisionNow(id) ?: return null
        val pid = newId()
        val t = now().toEpochMilli()
        dao.putProgram(p.copy(id = pid, title = p.title + " (copy)", createdAtMs = t))
        dao.addRevision(RevisionEntity(newId(), pid, 1, t, rev.doc))
        return pid
    }

    /** Hides a plan. If it is on, it stops first. Finished workouts stay. */
    suspend fun removePlan(id: String) {
        dao.activeEnrollmentNow()?.takeIf { it.programId == id }?.let { stopPlan() }
        dao.program(id)?.let { dao.putProgram(it.copy(archived = true)) }
    }

    /** Saves a changed plan as a new version. If it is on, the open workouts follow it; finished ones never change. */
    suspend fun savePlan(programId: String, doc: PlanDoc) = lock.withLock {
        val last = dao.latestRevisionNow(programId)
        val rev = RevisionEntity(newId(), programId, (last?.number ?: 0) + 1, now().toEpochMilli(), doc.encode())
        val e = dao.activeEnrollmentNow()?.takeIf { it.programId == programId }
        dao.saveRevision(rev, e, e?.let { open(it, doc) })
        if (e != null) dao.dropUndoOf(e.id) // the old dates no longer fit the new plan
    }

    /** The open workouts of [e] for a changed plan. A workout that is still there keeps its date; new ones go behind. */
    private suspend fun open(e: EnrollmentEntity, doc: PlanDoc): List<PlannedEntity> {
        val rows = dao.plannedNow(e.id)
        val closed = rows.filter { it.state != PlannedState.Planned }.map { it.week to it.day }.toSet()
        val had = rows.filter { it.state == PlannedState.Planned }.associateBy { it.week to it.day }
        val wanted = doc.weeks.flatMapIndexed { w, week -> week.days.mapIndexed { d, day -> Triple(w, d, day) } }.filter { (w, d, _) -> (w to d) !in closed }
        var seq = -1
        val list = wanted.map { (w, d, day) ->
            seq++
            val old = had[w to d]
            val easy = if (doc.weeks[w].easy) 90 else if (old != null && !wasEasyWeek(e, w)) old.easy else 100
            old?.copy(seq = seq, title = day.name, easy = easy) ?: PlannedEntity(newId(), e.id, seq, w, d, day.name, "", "", PlannedState.Planned, easy = easy)
        }
        // Plan order always equals date order for unpinned workouts: fill the dates again from the first open one.
        val done = rows.filter { it.state != PlannedState.Planned }
        val numbered = list.map { it.copy(seq = it.seq + (done.maxOfOrNull { d -> d.seq } ?: -1) + 1) }
        if (numbered.none { it.date.isEmpty() }) return numbered
        val start = numbered.filter { it.date.isNotEmpty() && !it.pinned }.minOfOrNull { LocalDate.parse(it.date) } ?: today()
        val pinnedDates = numbered.filter { it.pinned && it.date.isNotEmpty() }.map { LocalDate.parse(it.date) }.toSet()
        val free = numbered.filter { !(it.pinned && it.date.isNotEmpty()) }
        val dates = ScheduleRules.dates(free.size, start, e.days(), pinnedDates).map { it.toString() }
        val by = free.mapIndexed { i, x -> x.id to dates[i] }.toMap()
        return numbered.map { x -> by[x.id]?.let { x.copy(date = it, firstDate = x.firstDate.ifEmpty { it }) } ?: x }
    }

    private suspend fun wasEasyWeek(e: EnrollmentEntity, w: Int): Boolean =
        dao.revision(e.revisionId)?.let { runCatching { decodePlan(it.doc).weeks.getOrNull(w)?.easy }.getOrNull() } ?: false

    suspend fun startPlan(programId: String, start: LocalDate, weekdays: Set<DayOfWeek>): Boolean = lock.withLock {
        val rev = dao.latestRevisionNow(programId) ?: return@withLock false
        val doc = decodePlan(rev.doc)
        val eid = newId()
        val flat = doc.weeks.flatMapIndexed { w, week -> week.days.mapIndexed { d, day -> Triple(w, d, day) } }
        if (flat.isEmpty() || weekdays.isEmpty()) return@withLock false
        val dates = ScheduleRules.dates(flat.size, start, weekdays)
        val rows = flat.mapIndexed { i, (w, d, day) ->
            PlannedEntity(newId(), eid, i, w, d, day.name, dates[i].toString(), dates[i].toString(), PlannedState.Planned, easy = if (doc.weeks[w].easy) 90 else 100)
        }
        dao.startPlan(
            EnrollmentEntity(eid, programId, rev.id, start.toString(), weekdays.map { it.value }.sorted().joinToString(","), EnrollState.Active, now().toEpochMilli()),
            rows, now().toEpochMilli(),
        )
        true
    }

    suspend fun stopPlan() = lock.withLock {
        val e = dao.activeEnrollmentNow() ?: return@withLock
        dao.putEnrollment(e.copy(state = EnrollState.Stopped, endedAtMs = now().toEpochMilli()))
        dao.deleteOpenPlanned(e.id)
        dao.dropUndoOf(e.id)
    }

    /** Does the plan again from [week] on, on the same training days. */
    suspend fun restartFrom(week: Int, start: LocalDate) = lock.withLock {
        val e = dao.activeEnrollmentNow() ?: return@withLock
        val doc = dao.revision(e.revisionId)?.let { decodePlan(it.doc) } ?: return@withLock
        val rows = dao.plannedNow(e.id)
        val base = (rows.filter { it.state != PlannedState.Planned }.maxOfOrNull { it.seq } ?: -1) + 1
        val flat = doc.weeks.flatMapIndexed { w, x -> x.days.mapIndexed { d, day -> Triple(w, d, day) } }.filter { it.first >= week }
        val dates = ScheduleRules.dates(flat.size, start, e.days())
        dao.deleteOpenPlanned(e.id)
        dao.dropUndoOf(e.id)
        dao.putPlanned(flat.mapIndexed { i, (w, d, day) ->
            PlannedEntity(newId(), e.id, base + i, w, d, day.name, dates[i].toString(), dates[i].toString(), PlannedState.Planned, easy = if (doc.weeks[w].easy) 90 else 100)
        })
    }

    // ---------- schedule ----------

    /** Saves new dates and states for the plan that is on, and keeps the old ones for undo. [easy] changes the weight of some workouts. */
    suspend fun saveSchedule(label: String, after: List<PlannedItem>, easy: Map<String, Int> = emptyMap()) = lock.withLock {
        val e = dao.activeEnrollmentNow() ?: return@withLock
        val rows = dao.plannedNow(e.id)
        val by = after.associateBy { it.id }
        val changed = rows.mapNotNull { r ->
            val x = by[r.id] ?: return@mapNotNull null
            val next = r.copy(date = x.date.toString(), state = x.state, pinned = x.pinned, easy = easy[r.id] ?: r.easy)
            if (next != r) r to next else null
        }
        if (changed.isEmpty()) return@withLock
        val t = now().toEpochMilli()
        val old = json.encodeToString(ListSerializer(SavedPlanned.serializer()), changed.map { (r, _) -> SavedPlanned(r.id, r.date, r.state, r.easy) })
        dao.dropOldUndo(t - UNDO_MS)
        dao.saveSchedule(UndoEntity(newId(), t, e.id, label, old), changed.map { it.second })
        finishPlanIfDone(e)
    }

    /** Puts the dates back. A workout that was done meanwhile stays done. */
    suspend fun undoSchedule(u: UndoEntity) = lock.withLock {
        val old = json.decodeFromString(ListSerializer(SavedPlanned.serializer()), u.items)
        val rows = old.mapNotNull { o ->
            dao.plannedOne(o.id)?.takeIf { it.state != PlannedState.Done }?.copy(date = o.date, state = o.state, easy = o.easy)
        }
        dao.putPlanned(rows)
        dao.deleteUndo(u.id)
        dao.enrollment(u.enrollmentId)?.let { if (it.state == EnrollState.Finished && rows.any { r -> r.state == PlannedState.Planned } && dao.activeEnrollmentNow() == null) dao.putEnrollment(it.copy(state = EnrollState.Active, endedAtMs = null)) }
    }

    suspend fun togglePin(plannedId: String) { dao.plannedOne(plannedId)?.let { dao.putPlanned(listOf(it.copy(pinned = !it.pinned))) } }

    private suspend fun finishPlanIfDone(e: EnrollmentEntity) {
        val rows = dao.plannedNow(e.id)
        if (rows.isNotEmpty() && rows.none { it.state == PlannedState.Planned }) {
            dao.enrollment(e.id)?.let { dao.putEnrollment(it.copy(state = EnrollState.Finished, endedAtMs = now().toEpochMilli())) }
        }
    }

    // ---------- before a workout ----------

    /** The last two times an exercise was done, newest first. */
    private fun lastTimes(rows: List<HistoryRow>): Pair<List<DoneSet>, List<DoneSet>> {
        val by = rows.groupBy { it.sessionId }.values.sortedByDescending { it.first().startMs }
        return by.getOrNull(0).orEmpty().map { it.done() } to by.getOrNull(1).orEmpty().map { it.done() }
    }

    suspend fun preview(slot: Slot, easy: Int): SlotPreview {
        val e = dao.exercise(slot.exerciseId)
        val rows = dao.historyOf(slot.exerciseId)
        val (last, before) = lastTimes(rows)
        val best = rows.mapNotNull { Strength.best(it.done()) }.maxOrNull()
        val s = if (e?.track == Track.WeightReps) Progression.suggest(slot, last, before, best, e.stepGrams, easy)
        else Suggestion(null, if (last.isEmpty()) "First time. Find a number you can do well." else "Last time: ${last.filter { it.kind.counts }.joinToString(", ") { "${it.reps ?: it.seconds ?: 0}" }}.")
        return SlotPreview(slot, e, e?.name ?: "Exercise", s, last.filter { it.kind.counts })
    }

    suspend fun previewDay(planned: PlannedEntity): List<SlotPreview> {
        val e = dao.enrollment(planned.enrollmentId) ?: return emptyList()
        val doc = dao.revision(e.revisionId)?.let { decodePlan(it.doc) } ?: return emptyList()
        return doc.day(planned.week, planned.day)?.slots.orEmpty().map { preview(it, planned.easy) }
    }

    // ---------- live workout ----------

    private fun newSession(title: String, planned: PlannedEntity?, planTitle: String?): SessionEntity {
        val t = now()
        return SessionEntity(newId(), planned?.id, title, planTitle, planned?.week, t.toEpochMilli(), null, zone().id, t.atZone(zone()).toLocalDate().toString(), SessionState.Active, changedMs = t.toEpochMilli())
    }

    /** Starts a planned workout, or gives back the one that is already running: only one at a time. */
    suspend fun startPlanned(plannedId: String): String? = lock.withLock {
        dao.activeSessionNow()?.let { return@withLock it.id }
        val p = dao.plannedOne(plannedId) ?: return@withLock null
        val e = dao.enrollment(p.enrollmentId) ?: return@withLock null
        val s = newSession(p.title, p, dao.program(e.programId)?.title)
        val ex = mutableListOf<SessionExerciseEntity>()
        val sets = mutableListOf<SetEntity>()
        previewDay(p).forEachIndexed { i, pv ->
            val x = SessionExerciseEntity(newId(), s.id, pv.slot.exerciseId, pv.name, i, pv.slot.superset, pv.slot.restSec, targetText(pv), pv.suggestion.reason)
            ex += x
            sets += plannedSets(x.id, pv)
        }
        dao.addWorkout(s, ex, sets)
        s.id
    }

    private fun targetText(pv: SlotPreview): String {
        val s = pv.slot
        val time = pv.exercise?.track == Track.Time
        val reps = if (s.repsMin == s.repsMax) "${s.repsMin}" else "${s.repsMin}–${s.repsMax}"
        return "Target ${s.working} ${if (s.working == 1) "set" else "sets"} of $reps${if (time) " seconds" else ""}" + if (s.load == LoadKind.Effort && !time) ", RPE ${Weights.plain(s.loadValue / 10.0)}" else ""
    }

    private fun plannedSets(exId: String, pv: SlotPreview): List<SetEntity> {
        val s = pv.slot
        val time = pv.exercise?.track == Track.Time
        val weight = pv.suggestion.grams
        val step = pv.exercise?.stepGrams ?: 2500
        val goal = if (pv.suggestion.change > 0) s.repsMin else (pv.last.lastOrNull { it.kind != SetKind.Drop }?.reps ?: s.repsMin).coerceIn(s.repsMin, s.repsMax)
        fun row(pos: Int, kind: SetKind, grams: Int?, reps: Int?) = SetEntity(
            newId(), exId, pos, kind, grams = grams, reps = if (time) null else reps, seconds = if (time) s.repsMin else null, targetGrams = grams, targetReps = if (time) s.repsMin else reps,
        )
        val out = mutableListOf<SetEntity>()
        repeat(s.warmups) { i -> out += row(out.size, SetKind.Warmup, weight?.let { Weights.round(it * (0.4 + 0.5 * (i + 1) / (s.warmups + 1)), step) }, 8 - minOf(i * 2, 5)) }
        repeat(s.working) { i -> out += row(out.size, if (s.lastMax && i == s.working - 1) SetKind.Max else SetKind.Working, weight, goal) }
        repeat(s.drops) { i -> out += row(out.size, SetKind.Drop, weight?.let { Weights.round(it * (0.8 - 0.2 * i).coerceAtLeast(0.3), step) }, s.repsMax) }
        return out
    }

    suspend fun startEmpty(title: String = "Workout"): String = lock.withLock {
        dao.activeSessionNow()?.id ?: newSession(title, null, null).also { dao.putSession(it) }.id
    }

    /** Adds an exercise with one set, filled in from the last time. */
    suspend fun addToSession(sessionId: String, exerciseId: String) = lock.withLock {
        val e = dao.exercise(exerciseId) ?: return@withLock
        val pos = (dao.sessionExercisesNow(sessionId).maxOfOrNull { it.pos } ?: -1) + 1
        val (last, _) = lastTimes(dao.historyOf(exerciseId))
        val ref = last.filter { it.kind.counts }
        val x = SessionExerciseEntity(newId(), sessionId, exerciseId, e.name, pos, null, e.restSec)
        val n = ref.size.coerceIn(1, 8)
        val sets = (0 until n).map { i ->
            val r = ref.getOrNull(i)
            SetEntity(newId(), x.id, i, SetKind.Working, r?.grams, r?.reps, r?.seconds, targetGrams = r?.grams, targetReps = r?.reps ?: r?.seconds)
        }
        dao.putSessionExercises(listOf(x))
        dao.putSets(sets)
    }

    suspend fun addSet(sessionExerciseId: String) = lock.withLock {
        val sets = dao.setsOf(sessionExerciseId)
        val l = sets.lastOrNull()
        dao.putSets(listOf(SetEntity(newId(), sessionExerciseId, (l?.pos ?: -1) + 1, l?.kind?.takeIf { it != SetKind.Warmup } ?: SetKind.Working, l?.grams, l?.reps, l?.seconds, targetGrams = l?.grams, targetReps = l?.reps ?: l?.seconds)))
    }

    suspend fun deleteSet(setId: String) = lock.withLock { dao.deleteSet(setId) }

    /** Changes a set. A new weight also goes to the later open sets of the same kind that still had the old one. */
    suspend fun changeSet(setId: String, change: (SetEntity) -> SetEntity) = lock.withLock {
        val old = dao.set(setId) ?: return@withLock
        val next = change(old)
        val rows = mutableListOf(next)
        if (next.grams != old.grams) rows += dao.setsOf(old.sessionExerciseId).filter { it.pos > old.pos && !it.done && it.kind == old.kind && it.grams == old.grams }.map { it.copy(grams = next.grams) }
        dao.putSets(rows)
    }

    /** Ticks or unticks a set, and starts the rest when that is wanted. */
    suspend fun tick(setId: String, done: Boolean) = lock.withLock {
        val s = dao.set(setId) ?: return@withLock
        val x = dao.sessionExercise(s.sessionExerciseId) ?: return@withLock
        dao.putSets(listOf(s.copy(done = done, doneMs = if (done) now().toEpochMilli() else null)))
        val session = dao.session(x.sessionId) ?: return@withLock
        if (session.state != SessionState.Active) return@withLock
        if (!done) return@withLock
        if (!settingsNow().autoRest) return@withLock
        val all = dao.sessionExercisesNow(x.sessionId)
        val left = dao.setsOfSessionNow(x.sessionId).any { !it.done }
        // In a superset the rest comes after the last exercise of the round.
        val partnerOpen = x.superset != null && all.filter { it.superset == x.superset && it.pos > x.pos }.any { p -> dao.setsOf(p.id).any { it.pos == s.pos && !it.done } }
        if (!left || partnerOpen || x.restSec <= 0) return@withLock
        val rest = if (s.kind == SetKind.Warmup) minOf(x.restSec, 60) else x.restSec
        dao.setRest(x.sessionId, now().toEpochMilli() + rest * 1000L, rest)
    }

    private suspend fun settingsNow(): TrainingSettings = prefs.settings.first()

    suspend fun addRest(sessionId: String, seconds: Int) = lock.withLock {
        val s = dao.session(sessionId) ?: return@withLock
        val t = now().toEpochMilli()
        val ends = (s.restEndsMs ?: return@withLock) + seconds * 1000L
        if (ends <= t) dao.setRest(sessionId, null, 0) else dao.setRest(sessionId, ends, (s.restTotalSec + seconds).coerceAtLeast(1))
    }

    suspend fun skipRest(sessionId: String) = dao.setRest(sessionId, null, 0)

    /** Puts another exercise in place. Sets and reps stay, the weight is the new exercise's own. Sets already done stay with the old one. */
    suspend fun swap(sessionExerciseId: String, exerciseId: String, fromNow: Boolean) = lock.withLock {
        val x = dao.sessionExercise(sessionExerciseId) ?: return@withLock
        val e = dao.exercise(exerciseId) ?: return@withLock
        val (last, _) = lastTimes(dao.historyOf(exerciseId))
        val grams = last.filter { it.kind.counts }.mapNotNull { it.grams }.maxOrNull()
        val sets = dao.setsOf(x.id)
        val open = sets.filter { !it.done }
        if (sets.any { it.done }) {
            // Something was done already: keep it, and go on with a new exercise right behind it.
            val all = dao.sessionExercisesNow(x.sessionId)
            dao.putSessionExercises(all.filter { it.pos > x.pos }.map { it.copy(pos = it.pos + 1) })
            val y = x.copy(id = newId(), exerciseId = exerciseId, name = e.name, pos = x.pos + 1, reason = "")
            dao.putSessionExercises(listOf(y))
            open.forEach { dao.deleteSet(it.id) }
            dao.putSets(open.mapIndexed { i, s -> s.copy(id = newId(), sessionExerciseId = y.id, pos = i, grams = grams, targetGrams = grams) })
        } else {
            dao.putSessionExercises(listOf(x.copy(exerciseId = exerciseId, name = e.name, reason = "")))
            dao.putSets(open.map { it.copy(grams = grams, targetGrams = grams) })
        }
        if (fromNow) swapInPlan(x, exerciseId)
    }

    private suspend fun swapInPlan(x: SessionExerciseEntity, exerciseId: String) {
        val planned = dao.session(x.sessionId)?.plannedId?.let { dao.plannedOne(it) } ?: return
        val e = dao.enrollment(planned.enrollmentId) ?: return
        val last = dao.latestRevisionNow(e.programId) ?: return
        val doc = decodePlan(last.doc)
        val day = doc.day(planned.week, planned.day) ?: return
        val changed = day.copy(slots = day.slots.map { if (it.exerciseId == x.exerciseId) it.copy(exerciseId = exerciseId) else it })
        val next = PlanEdits.putDay(doc, planned.week, planned.day, changed, Scope.FromNow)
        val rev = RevisionEntity(newId(), e.programId, last.number + 1, now().toEpochMilli(), next.encode())
        dao.saveRevision(rev, e.takeIf { it.state == EnrollState.Active }, if (e.state == EnrollState.Active) dao.plannedNow(e.id).filter { it.state == PlannedState.Planned } else null)
    }

    suspend fun removeFromSession(sessionExerciseId: String) = lock.withLock {
        dao.deleteSetsOf(sessionExerciseId)
        dao.deleteSessionExerciseRow(sessionExerciseId)
    }

    suspend fun moveInSession(sessionExerciseId: String, up: Boolean) = lock.withLock {
        val x = dao.sessionExercise(sessionExerciseId) ?: return@withLock
        val all = dao.sessionExercisesNow(x.sessionId)
        val i = all.indexOfFirst { it.id == x.id }
        val other = all.getOrNull(if (up) i - 1 else i + 1) ?: return@withLock
        dao.putSessionExercises(listOf(x.copy(pos = other.pos), other.copy(pos = x.pos)))
    }

    /** Ends the workout. Sets without a tick are left out of everything. The planned workout counts as done. */
    suspend fun finish(sessionId: String): SessionEntity? = lock.withLock {
        val s = dao.session(sessionId) ?: return@withLock null
        if (s.state != SessionState.Active) return@withLock s
        val end = now().toEpochMilli()
        // A workout left open for hours ends a little after its last set, not now.
        val lastSet = dao.setsOfSessionNow(sessionId).mapNotNull { it.doneMs }.maxOrNull()
        val realEnd = if (lastSet != null && end - lastSet > FORGOTTEN_MS) lastSet + 60_000 else end
        val next = s.copy(state = SessionState.Finished, endMs = maxOf(realEnd, s.startMs + 1000), restEndsMs = null, restTotalSec = 0, changedMs = end)
        dao.updateSession(next)
        // Exercises nobody touched are not history.
        dao.sessionExercisesNow(sessionId).forEach { x -> if (dao.setsOf(x.id).none { it.done }) { dao.deleteSetsOf(x.id); dao.deleteSessionExerciseRow(x.id) } }
        s.plannedId?.let { pid ->
            dao.plannedOne(pid)?.let { p ->
                dao.putPlanned(listOf(p.copy(state = PlannedState.Done, date = s.date)))
                dao.enrollment(p.enrollmentId)?.takeIf { it.state == EnrollState.Active }?.let { finishPlanIfDone(it) }
            }
        }
        next
    }

    suspend fun setFeeling(sessionId: String, feeling: Int) { dao.session(sessionId)?.let { dao.updateSession(it.copy(feeling = feeling)) } }

    suspend fun setNote(sessionId: String, note: String) { dao.session(sessionId)?.let { dao.updateSession(it.copy(note = note.take(500))) } }

    /** Throws a workout away. It is kept for 30 days in case that was a mistake; its planned day is open again. */
    suspend fun discard(sessionId: String) = lock.withLock {
        val s = dao.session(sessionId) ?: return@withLock
        dao.updateSession(s.copy(state = SessionState.Discarded, restEndsMs = null, changedMs = now().toEpochMilli(), endMs = s.endMs ?: now().toEpochMilli()))
        s.plannedId?.let { pid -> dao.plannedOne(pid)?.takeIf { it.state == PlannedState.Done }?.let { dao.putPlanned(listOf(it.copy(state = PlannedState.Planned))) } }
    }

    suspend fun restore(sessionId: String) = lock.withLock {
        val s = dao.session(sessionId) ?: return@withLock
        if (s.state == SessionState.Discarded) {
            dao.updateSession(s.copy(state = SessionState.Finished, changedMs = now().toEpochMilli()))
            s.plannedId?.let { pid -> dao.plannedOne(pid)?.let { dao.putPlanned(listOf(it.copy(state = PlannedState.Done))) } }
        }
    }

    /** Removes for good what was thrown away more than 30 days ago. */
    suspend fun cleanUp() {
        val before = now().toEpochMilli() - UNDO_MS
        dao.oldDiscarded(before).forEach { dao.deleteSession(it) }
        dao.dropOldUndo(before)
    }

    // ---------- backup ----------

    suspend fun backup(): String = withContext(Dispatchers.IO) {
        val file = BackupFile(
            savedAtMs = now().toEpochMilli(), exercises = dao.allExercises(), aliases = dao.aliases(), programs = dao.allPrograms(), revisions = dao.allRevisions(),
            enrollments = dao.allEnrollments(), planned = dao.allPlanned(), sessions = dao.allSessions().filter { it.state != SessionState.Discarded }, sessionExercises = dao.allSessionExercises(),
            sets = dao.allSets(), batches = dao.allBatches(), refs = dao.allRefs(), links = dao.allLinks(), settings = TrainingSettings.encode(settingsNow()),
        )
        json.encodeToString(BackupFile.serializer(), file)
    }

    /** Reads and checks a backup. Nothing is changed. Throws [IllegalArgumentException] with a plain reason. */
    fun readBackup(text: String): BackupFile = Backups.read(text)

    suspend fun restore(b: BackupFile) = lock.withLock {
        dao.replaceAll(b)
        // Health Connect needs its own yes on this phone, so it never comes back switched on by itself.
        prefs.set(TrainingSettings.decode(b.settings).copy(healthConnect = false))
        val rows = withContext(Dispatchers.IO) { ExerciseCatalog.load(context) }.map { it.entity() }
        dao.addMissingExercises(rows)
        mergeLocked()
    }

    suspend fun deleteAll() {
        lock.withLock { dao.wipe() }
        seed()
        mergeExerciseDb()
    }

    // ---------- ExerciseDB ----------

    /** Ties same names and adds the ExerciseDB exercises Pulse does not have. Safe to run again and again. */
    suspend fun mergeExerciseDb(): EdbRules.Merge = lock.withLock { mergeLocked() }

    private suspend fun mergeLocked(): EdbRules.Merge {
        val edb = dao.allEdb()
        if (edb.isEmpty()) return EdbRules.Merge(emptyList(), emptyList(), emptyList())
        val m = withContext(Dispatchers.Default) { EdbRules.merge(dao.allExercises(), edb, dao.allLinks()) }
        dao.putLinks(m.same)
        dao.addMissingExercises(m.add)
        return m
    }

    /** Close names still to ask about: your exercise, and the ExerciseDB one that may be the same. */
    suspend fun closeNames(): List<Pair<ExerciseEntity, EdbEntity>> {
        val edb = dao.allEdb()
        if (edb.isEmpty()) return emptyList()
        val pulse = dao.allExercises()
        val m = withContext(Dispatchers.Default) { EdbRules.merge(pulse, edb, dao.allLinks()) }
        val byId = pulse.associateBy { it.id }
        val edbById = edb.associateBy { it.id }
        return m.close.mapNotNull { (p, e) -> byId[p]?.let { a -> edbById[e]?.let { a to it } } }
    }

    /** Yes: the exercise gets ExerciseDB's animation and steps, and the copy from ExerciseDB is hidden if never used. No: never asked again. */
    suspend fun answerCloseName(exerciseId: String, edbId: String, same: Boolean) = lock.withLock {
        dao.putLinks(listOf(EdbLinkEntity(exerciseId, edbId, if (same) LinkHow.Chosen else LinkHow.Declined)))
        if (same) {
            val copy = EdbRules.exerciseId(edbId)
            dao.exercise(copy)?.let { if (dao.timesUsed(copy) == 0) dao.putExercise(it.copy(archived = true)) }
        }
    }

    /** The ExerciseDB exercise behind a Pulse exercise, if there is one. */
    suspend fun edbFor(exerciseId: String): EdbEntity? {
        val id = EdbRules.edbIdOf(exerciseId) ?: dao.link(exerciseId)?.takeIf { it.how != LinkHow.Declined }?.edbId ?: return null
        return dao.edb(id)
    }

    /** Pulse exercise id to ExerciseDB id, for every tied exercise. */
    fun edbIds(): Flow<Map<String, String>> = dao.linksFlow().map { links ->
        links.filter { it.how != LinkHow.Declined }.associate { it.exerciseId to it.edbId }
    }

    /** Exercises worth having offline: the next week of the plan, and favourites. */
    suspend fun exercisesAhead(days: Long = 7): Set<String> {
        val out = dao.favouriteIds().toMutableSet()
        val e = dao.activeEnrollmentNow() ?: return out
        val until = today().plusDays(days)
        val docs = HashMap<String, PlanDoc?>()
        dao.plannedNow(e.id).filter { it.state == PlannedState.Planned && LocalDate.parse(it.date) <= until }.forEach { p ->
            val doc = docs.getOrPut(e.revisionId) { dao.revision(e.revisionId)?.let { runCatching { decodePlan(it.doc) }.getOrNull() } }
            doc?.day(p.week, p.day)?.slots?.forEach { out += it.exerciseId }
        }
        return out
    }

    companion object {
        val UNDO_MS: Long = Duration.ofDays(30).toMillis()
        val FORGOTTEN_MS: Long = Duration.ofHours(2).toMillis()
    }
}

object Backups {
    fun read(text: String): BackupFile {
        val b = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (_: Exception) {
            throw IllegalArgumentException("This is not a Pulse training file.")
        }
        require(b.format <= BackupFile.FORMAT) { "This file is from a newer Pulse. Update Pulse first." }
        require(b.exercises.isNotEmpty() || b.sessions.isNotEmpty() || b.programs.isNotEmpty()) { "This file is empty." }
        val ids = b.sessions.map { it.id }.toSet()
        val ex = b.sessionExercises.filter { it.sessionId in ids }
        val exIds = ex.map { it.id }.toSet()
        b.revisions.forEach { r -> try { decodePlan(r.doc) } catch (_: Exception) { throw IllegalArgumentException("A plan in this file is damaged.") } }
        return b.copy(sessionExercises = ex, sets = b.sets.filter { it.sessionExerciseId in exIds })
    }
}
