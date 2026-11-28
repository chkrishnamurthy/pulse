package com.fitbittrack.pulse.training

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The exercises that come with Pulse, from the public-domain free-exercise-db (see assets/exercises-LICENSE.txt). */
object ExerciseCatalog {
    const val SIZE = 876
    private const val FILE = "exercises.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Item(val id: String, val n: String, val e: Equipment, val t: Track, val m: Muscle, val s: List<Muscle> = emptyList(), val h: List<String> = emptyList()) {
        fun entity() = ExerciseEntity(
            id, n, e, t, m, s.joinToString(",") { it.name },
            restSec = if (t == Track.Time) 60 else if (m in BIG && e == Equipment.Barbell) 150 else 90,
            stepGrams = if (e == Equipment.Dumbbell || e == Equipment.Kettlebell) 2000 else 2500,
        )
    }

    private val BIG = setOf(Muscle.Chest, Muscle.Back, Muscle.FrontLegs, Muscle.BackLegs)

    fun parse(text: String): List<Item> = json.decodeFromString(ListSerializer(Item.serializer()), text)

    @Volatile private var items: List<Item>? = null

    fun load(context: Context): List<Item> = items ?: parse(context.assets.open(FILE).bufferedReader().use { it.readText() }).also { items = it }

    /** How an exercise is done, step by step. Empty for the user's own. */
    fun steps(context: Context, id: String): List<String> = load(context).firstOrNull { it.id == id }?.h.orEmpty()
}
