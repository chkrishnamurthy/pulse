package com.fitbittrack.pulse.ui.training

import com.fitbittrack.pulse.training.Best
import com.fitbittrack.pulse.training.BoostcampImport
import com.fitbittrack.pulse.training.DatedSet
import com.fitbittrack.pulse.training.EnrollmentEntity
import com.fitbittrack.pulse.training.ExerciseEntity
import com.fitbittrack.pulse.training.FileUnit
import com.fitbittrack.pulse.training.HistoryRow
import com.fitbittrack.pulse.training.ImportDraft
import com.fitbittrack.pulse.training.ImportExercise
import com.fitbittrack.pulse.training.NextSet
import com.fitbittrack.pulse.training.PlanDay
import com.fitbittrack.pulse.training.PlanDoc
import com.fitbittrack.pulse.training.PlannedEntity
import com.fitbittrack.pulse.training.PlannedItem
import com.fitbittrack.pulse.training.PlannedState
import com.fitbittrack.pulse.training.ProgramEntity
import com.fitbittrack.pulse.training.ProgressRules
import com.fitbittrack.pulse.training.ScheduleRules
import com.fitbittrack.pulse.training.SessionEntity
import com.fitbittrack.pulse.training.SessionExerciseEntity
import com.fitbittrack.pulse.training.SessionTotals
import com.fitbittrack.pulse.training.SetEntity
import com.fitbittrack.pulse.training.UndoEntity
import com.fitbittrack.pulse.training.item
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/*
 * What the training pages show. Plain data built by TrainingViewModel; small questions about it are answered here,
 * so pages and the navigation code never repeat the rules.
 */

enum class DayMark { None, Planned, Done, Skipped }

data class WeekDay(val date: LocalDate, val mark: DayMark, val today: Boolean)

/** The plan that is on. */
data class PlanOn(val enrollment: EnrollmentEntity, val program: ProgramEntity, val doc: PlanDoc, val planned: List<PlannedEntity>) {
    val done: Int get() = planned.count { it.state == PlannedState.Done }
    val skipped: Int get() = planned.count { it.state == PlannedState.Skipped }
    val open: List<PlannedEntity> get() = planned.filter { it.state == PlannedState.Planned }.sortedWith(compareBy({ it.date }, { it.seq }))
    val next: PlannedEntity? get() = open.firstOrNull()
    val week: Int get() = (next ?: planned.maxByOrNull { it.seq })?.week ?: 0
    val items: List<PlannedItem> get() = planned.map { it.item() }
    fun day(p: PlannedEntity): PlanDay? = doc.day(p.week, p.day)
    /** Planned workouts skipped on or after [from]. */
    fun skippedSince(from: LocalDate): Int = planned.count { it.state == PlannedState.Skipped && LocalDate.parse(it.date) >= from }
}

data class TrainHome(
    val today: LocalDate, val plan: PlanOn?, val active: SessionEntity?, val hasPlans: Boolean, val hasHistory: Boolean, val week: List<WeekDay>,
    val exercises: Int, val own: Int, val recordsThisWeek: Int,
) {
    val weekDone: Int get() = week.count { it.mark == DayMark.Done }
    val weekAll: Int get() = week.count { it.mark == DayMark.Done || it.mark == DayMark.Planned }
    val empty: Boolean get() = !hasPlans && !hasHistory && active == null
}

data class LiveExercise(val entity: SessionExerciseEntity, val exercise: ExerciseEntity?, val sets: List<SetEntity>, val last: List<HistoryRow>)

data class LiveUi(val session: SessionEntity, val exercises: List<LiveExercise>) {
    val all: Int get() = exercises.sumOf { it.sets.size }
    val done: Int get() = exercises.sumOf { e -> e.sets.count { it.done } }
    val next: Pair<LiveExercise, Int>? get() = NextSet.of(exercises.map { e -> e.entity.superset to e.sets.map { it.done } })?.let { (e, s) -> exercises[e] to s }
    val current: LiveExercise? get() = next?.first
}

data class HistoryItem(val session: SessionEntity, val totals: SessionTotals?, val record: Boolean)

data class ProgressUi(
    val all: List<DatedSet>, val rows: List<HistoryRow>, val bests: Map<String, Best>, val names: Map<String, String>, val sessions: List<HistoryItem>,
    val streak: Int, val bestStreak: Int, val today: LocalDate, val firstDay: DayOfWeek,
) {
    /** The latest records first. */
    val records: List<Best> get() = bests.values.sortedWith(compareByDescending<Best> { it.date }.thenByDescending { it.grams })

    /** In how many workouts each exercise was done. */
    val workoutsPerExercise: Map<String, Int> get() = rows.groupBy { it.exerciseId }.mapValues { (_, l) -> l.map { it.sessionId }.distinct().size }

    /** The most recent finished workout called [title]. */
    fun lastNamed(title: String): SessionEntity? = sessions.firstOrNull { it.session.title == title }?.session

    /** The Pulse workout that is the same gym visit as a workout seen by the band from [startMs] to [endMs]. */
    fun sameVisit(startMs: Long, endMs: Long): SessionEntity? = sessions.map { it.session }.firstOrNull { s ->
        val end = s.endMs
        end != null && !s.timeUnsure && ProgressRules.sameVisit(startMs, endMs, s.startMs, end)
    }
}

data class WorkoutMonth(
    val month: YearMonth, val today: LocalDate, val firstDay: DayOfWeek, val marks: Map<LocalDate, DayMark>, val titles: Map<LocalDate, String>,
    val planned: Map<LocalDate, List<PlannedEntity>>,
    val sessions: Map<LocalDate, List<SessionEntity>>,
    val next: PlannedEntity?,
    val streak: Int,
    val bestStreak: Int,
    val undo: UndoEntity?,
)

/** A change to the dates, shown before it is saved. */
data class ScheduleDraft(
    val label: String,
    val before: List<PlannedItem>,
    val after: List<PlannedItem>,
    val titles: Map<String, String>,
    val easy: Map<String, Int> = emptyMap(),
) {
    val changes: List<Pair<PlannedItem, PlannedItem>> get() = ScheduleRules.changed(before, after)
    val endBefore: LocalDate? get() = before.filter { it.state == PlannedState.Planned }.maxOfOrNull { it.date }
    val endAfter: LocalDate? get() = after.filter { it.state == PlannedState.Planned }.maxOfOrNull { it.date }
}

sealed interface ImportStep {
    data object Choose : ImportStep
    data object Reading : ImportStep
    data class Failed(val message: String) : ImportStep
    data class Unit(val draft: ImportDraft, val file: String, val sample: Int = 0) : ImportStep
    data class Names(val draft: ImportDraft, val file: String, val unit: FileUnit, val sure: Map<String, String>, val ask: List<ImportExercise>, val chosen: Map<String, String?>,
        /** Likely matches. They count only when the user says yes. */
        val guess: Map<String, String> = emptyMap(),
    ) : ImportStep
    data class Check(val draft: ImportDraft, val file: String, val unit: FileUnit, val chosen: Map<String, String>, val rows: BoostcampImport.Rows) : ImportStep
    data class Saved(val workouts: Int, val sets: Int, val already: Int) : ImportStep
}
