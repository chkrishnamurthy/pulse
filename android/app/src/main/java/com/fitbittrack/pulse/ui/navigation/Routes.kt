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
@Serializable data object AirRoute
@Serializable data class ChatRoute(val question: String? = null)
@Serializable data object ChatSettingsRoute
@Serializable data object WidgetsRoute
/** [id] = a widget already on the home screen; otherwise a new one of [design]. */
@Serializable data class WidgetSetupRoute(val design: String? = null, val id: Int = 0)
@Serializable data class DayRoute(val epochDay: Long)
@Serializable data class MetricRoute(val kind: String)

// Training
@Serializable data object TrainRoute
/** The exercise list. Build it with [ExercisesRoute.browse] or [ExercisesRoute.pick]; read the reason with [choice]. */
@Serializable data class ExercisesRoute(val pick: String? = null) {
    /** Why an exercise is being chosen, or null when the list is only browsed. */
    val choice: ExercisePick? get() = pick?.let(ExercisePick::decode)

    companion object {
        fun browse() = ExercisesRoute()
        fun pick(choice: ExercisePick) = ExercisesRoute(choice.encode())
    }
}

/** What the chosen exercise is for. Kept in the route as plain text, so it survives Android closing Pulse. */
sealed interface ExercisePick {
    /** Add it to the open workout [sessionId]. */
    data class AddToSession(val sessionId: String) : ExercisePick
    /** Put it in place of one exercise of the open workout. */
    data class SwapInSession(val sessionExerciseId: String) : ExercisePick
    /** Add it to the plan day being edited. */
    data object AddSlot : ExercisePick
    /** Put it in place of one slot of the plan day being edited. */
    data class SwapSlot(val slotId: String) : ExercisePick
    /** Use it for an exercise name found in an imported file. */
    data class ForImport(val key: String) : ExercisePick

    fun encode(): String = when (this) {
        is AddToSession -> "session:$sessionId"
        is SwapInSession -> "swap:$sessionExerciseId"
        AddSlot -> "slot"
        is SwapSlot -> "slotswap:$slotId"
        is ForImport -> "import:$key"
    }

    companion object {
        fun decode(s: String): ExercisePick? {
            val arg = s.substringAfter(':', "")
            return when (s.substringBefore(':')) {
                "session" -> AddToSession(arg)
                "swap" -> SwapInSession(arg)
                "slot" -> AddSlot
                "slotswap" -> SwapSlot(arg)
                "import" -> ForImport(arg)
                else -> null
            }
        }
    }
}
@Serializable data class ExerciseEditRoute(val id: String? = null)
@Serializable data class ExerciseRoute(val id: String)
@Serializable data object PlansRoute
@Serializable data object NewPlanRoute
@Serializable data class PlanRoute(val id: String)
@Serializable data class PlanDayRoute(val id: String, val week: Int, val day: Int)
@Serializable data class BeforeRoute(val plannedId: String)
@Serializable data object ScheduleDraftRoute
@Serializable data object PauseRoute
@Serializable data object LiveWorkoutRoute
@Serializable data object RestRoute
@Serializable data class FinishedRoute(val id: String, val fresh: Boolean = false)
@Serializable data object ProgressRoute
@Serializable data object MusclesRoute
@Serializable data object HistoryRoute
@Serializable data object ImportRoute
@Serializable data object BackupRoute
@Serializable data object TrainSettingsRoute
@Serializable data object TrainAnimationsRoute
