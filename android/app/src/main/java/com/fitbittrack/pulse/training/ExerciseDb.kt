package com.fitbittrack.pulse.training

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Exercises and their animations from ExerciseDB (free V1 API by AscendAPI, https://oss.exercisedb.dev).
 *
 * PERSONAL USE ONLY. The free version allows personal and non-commercial apps, with credit to AscendAPI.
 * Selling Pulse, ads or subscriptions need their paid plan. The release bundle task refuses to run while this is on
 * (see app/build.gradle.kts, exerciseDbFree).
 */

/** One exercise as ExerciseDB has it. Lists are kept as text, one item per line. */
@Serializable
@Entity(tableName = "edb_exercise")
data class EdbEntity(
    @PrimaryKey val id: String,
    val name: String,
    val bodyParts: String,
    val equipments: String,
    val targets: String,
    val secondaries: String,
    val steps: String,
    val syncedMs: Long,
) {
    fun stepList(): List<String> = steps.lines().filter { it.isNotBlank() }
    fun targetList(): List<String> = targets.lines().filter { it.isNotBlank() }
    fun secondaryList(): List<String> = secondaries.lines().filter { it.isNotBlank() }
    fun bodyPartList(): List<String> = bodyParts.lines().filter { it.isNotBlank() }
    fun equipmentList(): List<String> = equipments.lines().filter { it.isNotBlank() }
}

/** How a Pulse exercise is tied to an ExerciseDB one. */
@Serializable
enum class LinkHow {
    /** Same name: tied by Pulse. */
    Same,
    /** The user said yes to a close name. */
    Chosen,
    /** The user said no to a close name: never asked again. */
    Declined,
}

@Serializable
@Entity(tableName = "edb_link")
data class EdbLinkEntity(@PrimaryKey val exerciseId: String, val edbId: String, val how: LinkHow)

/** What the API sends for one exercise. */
@Serializable
data class EdbItem(
    val exerciseId: String,
    val name: String,
    val gifUrl: String? = null,
    val targetMuscles: List<String> = emptyList(),
    val bodyParts: List<String> = emptyList(),
    val equipments: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
) {
    fun entity(now: Long) = EdbEntity(
        exerciseId, name.trim(),
        bodyParts.joinToString("\n"), equipments.joinToString("\n"), targetMuscles.joinToString("\n"), secondaryMuscles.joinToString("\n"),
        instructions.map(EdbRules::cleanStep).filter { it.isNotBlank() }.joinToString("\n"), now,
    )
}

/** Pure rules: names, muscles, equipment and how the two lists come together. */
object EdbRules {
    const val ID_PREFIX = "edb-"
    const val CREDIT = "Exercise data and animations: ExerciseDB by AscendAPI"

    fun exerciseId(edbId: String) = ID_PREFIX + edbId
    fun edbIdOf(exerciseId: String): String? = exerciseId.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)

    /** "Step:3 Brace the torso." → "Brace the torso." */
    fun cleanStep(s: String): String = s.trim().replace(Regex("^(?i)step\\s*:?\\s*\\d+\\s*[:.)-]?\\s*"), "").trim()

    /** "barbell bench press" → "Barbell Bench Press". Short words stay small, "ez" becomes "EZ". */
    fun title(name: String): String = name.trim().split(Regex("\\s+")).mapIndexed { i, w ->
        when {
            w.equals("ez", true) -> "EZ"
            i > 0 && w.lowercase() in SMALL -> w.lowercase()
            else -> w.replaceFirstChar { it.uppercaseChar() }
        }
    }.joinToString(" ")

    private val SMALL = setOf("on", "of", "to", "with", "and", "a", "the", "in", "at", "for", "or")

    fun muscle(name: String): Muscle = when (name.trim().lowercase()) {
        "pectorals", "serratus anterior" -> Muscle.Chest
        "latissimus dorsi", "upper back", "rhomboids", "trapezius", "back", "erector spinae" -> Muscle.Back
        "deltoids", "rear deltoids", "rotator cuff" -> Muscle.Shoulders
        "biceps", "triceps", "forearms", "brachialis", "wrist extensors", "wrist flexors", "wrists", "grip muscles", "hands" -> Muscle.Arms
        "quadriceps", "adductors", "abductors", "hip flexors" -> Muscle.FrontLegs
        "hamstrings", "glutes" -> Muscle.BackLegs
        "calves", "soleus", "tibialis anterior", "ankles", "ankle stabilizers", "feet" -> Muscle.Calves
        "abdominals", "obliques", "core" -> Muscle.Core
        else -> Muscle.Other
    }

    private fun bodyPartMuscle(part: String): Muscle = when (part.trim().lowercase()) {
        "chest" -> Muscle.Chest
        "back" -> Muscle.Back
        "shoulders" -> Muscle.Shoulders
        "upper arms", "lower arms" -> Muscle.Arms
        "upper legs" -> Muscle.FrontLegs
        "lower legs" -> Muscle.Calves
        "waist" -> Muscle.Core
        else -> Muscle.Other
    }

    fun equipment(list: List<String>): Equipment {
        val e = list.map { it.trim().lowercase() }
        return when {
            e.any { it in setOf("barbell", "olympic barbell", "ez bar", "trap bar") } -> Equipment.Barbell
            e.any { it == "dumbbell" } -> Equipment.Dumbbell
            e.any { it == "kettlebell" } -> Equipment.Kettlebell
            e.any { it == "cable" } -> Equipment.Cable
            e.any { it in setOf("leverage machine", "smith machine", "sled machine", "stationary bike", "elliptical machine", "stepmill machine", "upper body ergometer", "ski ergometer") } -> Equipment.Machine
            e.any { it == "resistance band" || it == "band" } -> Equipment.Band
            e.any { it in setOf("bodyweight", "assisted", "weighted") } -> Equipment.Body
            else -> Equipment.Other
        }
    }

    private val TIMED = Regex("\\b(stretch|plank|hold|wall sit|hang|walk|run|jog|bike|cycling|elliptical|stepmill|ergometer|jump rope|march)\\b")

    fun track(e: EdbEntity): Track {
        val n = e.name.lowercase()
        val eq = e.equipmentList().map { it.lowercase() }
        return when {
            "cardio" in e.bodyPartList().map { it.lowercase() } || TIMED.containsMatchIn(n) -> Track.Time
            eq.all { it in setOf("bodyweight", "assisted", "resistance band", "band", "stability ball", "bosu ball", "roller", "towel", "suspension trainer") } -> Track.Reps
            else -> Track.WeightReps
        }
    }

    /** The main muscle: the first target, else the body part. */
    fun mainMuscle(e: EdbEntity): Muscle =
        e.targetList().map(::muscle).firstOrNull { it != Muscle.Other } ?: e.bodyPartList().map(::bodyPartMuscle).firstOrNull { it != Muscle.Other } ?: Muscle.Other

    fun alsoTrains(e: EdbEntity, main: Muscle): List<Muscle> = (e.targetList().drop(1) + e.secondaryList()).map(::muscle).filter { it != main && it != Muscle.Other }.distinct()

    /** An ExerciseDB exercise as a Pulse exercise, for those Pulse does not have yet. */
    fun pulseExercise(e: EdbEntity): ExerciseEntity {
        val main = mainMuscle(e)
        val track = track(e)
        val equipment = equipment(e.equipmentList())
        return ExerciseEntity(
            exerciseId(e.id), title(e.name), equipment, track, main, alsoTrains(e, main).joinToString(",") { it.name },
            restSec = if (track == Track.Time) 60 else if (equipment == Equipment.Barbell && main in setOf(Muscle.Chest, Muscle.Back, Muscle.FrontLegs, Muscle.BackLegs)) 150 else 90,
            stepGrams = if (equipment == Equipment.Dumbbell || equipment == Equipment.Kettlebell) 2000 else 2500,
        )
    }

    /** Words compared, without small words and plurals. */
    fun key(name: String): String = ImportNames.plain(name.replace("-", " "))

    /** 1 for the same text, 0 for nothing alike. */
    fun likeness(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            cur.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    const val CLOSE = 0.86

    /** What a merge would change. */
    data class Merge(
        /** Pulse exercises with the very same name: tie them. */
        val same: List<EdbLinkEntity>,
        /** ExerciseDB exercises Pulse does not have: add them as Pulse exercises. */
        val add: List<ExerciseEntity>,
        /** Close names to ask about once: Pulse exercise id to ExerciseDB id. */
        val close: List<Pair<String, String>>,
    )

    /**
     * Brings the lists together. Nothing here removes or renames a Pulse exercise, so history keeps every name.
     * [links] are the ties made before; a declined pair is never asked again.
     */
    fun merge(pulse: List<ExerciseEntity>, edb: List<EdbEntity>, links: List<EdbLinkEntity>): Merge {
        val tied = links.filter { it.how != LinkHow.Declined }.map { it.edbId }.toSet()
        val tiedPulse = links.map { it.exerciseId }.toSet()
        val declined = links.filter { it.how == LinkHow.Declined }.map { it.exerciseId to it.edbId }.toSet()
        val byKey = edb.groupBy { key(it.name) }
        val have = pulse.map { it.id }.toSet()
        val same = mutableListOf<EdbLinkEntity>()
        val usedEdb = tied.toMutableSet()
        for (p in pulse) {
            if (p.id in tiedPulse || p.custom || p.id.startsWith(ID_PREFIX)) continue
            val hit = byKey[key(p.name)]?.firstOrNull { it.id !in usedEdb } ?: continue
            same += EdbLinkEntity(p.id, hit.id, LinkHow.Same)
            usedEdb += hit.id
        }
        val add = edb.filter { it.id !in usedEdb && exerciseId(it.id) !in have }.map(::pulseExercise)
        // Close names: only Pulse's own list (not ExerciseDB rows), sharing a word, not tied yet.
        val linked = tiedPulse + same.map { it.exerciseId }
        val free = edb.filter { it.id !in usedEdb }
        val byWord = HashMap<String, MutableList<EdbEntity>>()
        free.forEach { e -> key(e.name).split(" ").toSet().forEach { w -> byWord.getOrPut(w) { mutableListOf() } += e } }
        val close = pulse.filter { !it.custom && !it.id.startsWith(ID_PREFIX) && it.id !in linked }.mapNotNull { p ->
            val k = key(p.name)
            val candidates = k.split(" ").toSet().flatMap { byWord[it].orEmpty() }.distinct()
            candidates.map { it to likeness(k, key(it.name)) }.filter { (e, l) -> l >= CLOSE && (p.id to e.id) !in declined }
                .maxByOrNull { it.second }?.let { p.id to it.first.id }
        }
        return Merge(same, add, close)
    }
}

/** Being gentle with the free API: one request at a time, a pause between, longer waits after trouble. */
object EdbPace {
    const val GAP_MS = 1_200L
    val WAITS_MS = listOf(10_000L, 30_000L, 120_000L)
    /** After this many failures in a row, stop until tomorrow. */
    val TRIES = WAITS_MS.size
    const val REST_MS = 24 * 60 * 60 * 1000L

    /** How long to wait before the next request, given when the last one was made. */
    fun gap(lastMs: Long, nowMs: Long): Long = (lastMs + GAP_MS - nowMs).coerceAtLeast(0)

    /** Wait after the [failures]th failure in a row, or null when it is time to stop. A server's Retry-After wins when longer. */
    fun wait(failures: Int, retryAfterSec: Long?): Long? {
        val base = WAITS_MS.getOrNull(failures - 1) ?: return null
        return maxOf(base, (retryAfterSec ?: 0) * 1000)
    }

    /** 429 and server trouble are worth another try. Other answers are not. */
    fun retry(code: Int): Boolean = code == 429 || code == 408 || code in 500..599
}

/** Where an exercise's picture comes from, best first. */
enum class Picture { Saved, Download, Drawing }

object EdbPictures {
    /** Saved on the phone, else download it when online, else a drawing that says it comes later. */
    fun pick(edbId: String?, saved: Boolean, online: Boolean): Picture = when {
        edbId == null -> Picture.Drawing
        saved -> Picture.Saved
        online -> Picture.Download
        else -> Picture.Drawing
    }
}
