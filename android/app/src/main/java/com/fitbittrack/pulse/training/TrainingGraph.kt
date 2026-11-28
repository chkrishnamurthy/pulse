package com.fitbittrack.pulse.training

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.glance.appwidget.updateAll
import com.fitbittrack.pulse.ui.widget.TrainWidget
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Wiring for training: one of each for the whole app. Apart from PulseGraph, which belongs to the copy of Google's data. */
object TrainingGraph {
    @OptIn(FlowPreview::class)
    private class Graph(context: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = TrainingDatabase.create(context)
        val prefs = TrainingPrefs(context)
        val repository = TrainingRepository(context, db.dao(), prefs)
        val exerciseDb = ExerciseDbStore(context, db.dao(), repository)

        init {
            scope.launch {
                runCatching { repository.seed() }
                runCatching { repository.cleanUp() }
                runCatching { exerciseDb.refreshStatus() }
                runCatching { exerciseDb.schedule() }
            }
            // The widget follows every change of the plan or the workouts.
            scope.launch {
                merge(db.dao().finished().map { }, db.dao().activeSession().map { }, db.dao().allPlannedFlow().map { }).debounce(1_500).collect { runCatching { TrainWidget().updateAll(context) } }
            }
        }
    }

    @Volatile private var graph: Graph? = null

    private fun graph(context: Context): Graph = graph ?: synchronized(this) { graph ?: Graph(context.applicationContext).also { graph = it } }

    fun repository(context: Context): TrainingRepository = graph(context).repository
    fun prefs(context: Context): TrainingPrefs = graph(context).prefs
    /** Exercises and animations from ExerciseDB (personal use only). */
    fun exerciseDb(context: Context): ExerciseDbStore = graph(context).exerciseDb
    /** Outlives a screen: saving a set must not stop because the page closed. */
    fun scope(context: Context): CoroutineScope = graph(context).scope

    /** Heart rate from the band while a workout is open, for the notification. Null when there is none. */
    val liveBpm = MutableStateFlow<Int?>(null)
}
