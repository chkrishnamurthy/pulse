package com.fitbittrack.pulse.chat

import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.training.PlannedState
import com.fitbittrack.pulse.training.TrainingRepository
import com.fitbittrack.pulse.training.dated
import com.fitbittrack.pulse.ui.model.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

/*
 * Where Ask Pulse gets its numbers. Each question reads the phone's own databases once; nothing is sent anywhere.
 */

/** The last [ChatData.HISTORY_DAYS] days of health data. */
suspend fun loadChatData(repo: HealthRepository, day: LocalDate, units: Units): ChatData = withContext(Dispatchers.Default) {
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

/** The user's own training, also while Pulse shows sample health data. */
suspend fun loadTrainingChat(repo: TrainingRepository, day: LocalDate): TrainingChatData = withContext(Dispatchers.Default) {
    val dao = repo.dao
    val rows = dao.history().first()
    val plan = dao.activeEnrollmentNow()
    val next = plan?.let { dao.plannedNow(it.id) }.orEmpty()
        .filter { it.state == PlannedState.Planned }
        .minWithOrNull(compareBy({ it.date }, { it.seq }))
    TrainingChatData(
        today = day,
        sets = rows.map { it.dated() },
        names = rows.associate { it.exerciseId to it.exerciseName },
        workoutDays = dao.finished().first().map { LocalDate.parse(it.date) },
        next = next?.let { it.title to LocalDate.parse(it.date) },
        planTitle = plan?.let { dao.program(it.programId)?.title },
        unit = repo.prefs.settings.first().unit,
    )
}
