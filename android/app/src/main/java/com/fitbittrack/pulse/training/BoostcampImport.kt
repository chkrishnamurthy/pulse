package com.fitbittrack.pulse.training

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Reads a history file the user saved from their own Boostcamp account. Pulse never signs in there and never calls it.
 * The file has no published format, so nothing is guessed: what can't be read is left out and counted.
 * This file is the only code that knows Boostcamp. Remove it and the rest still builds (apart from its pages).
 */

/** How the weights of a file are written. The user says which, by a set they remember. */
enum class FileUnit { Pounds, Kilos }

data class ImportSet(val kind: SetKind, val weight: Double?, val reps: Int?, val seconds: Int?, val rpe10: Int?)

data class ImportExercise(val name: String, val equipment: String?, val superset: String?, val sets: List<ImportSet>) {
    val key: String get() = ImportNames.key(name, equipment)
}

data class ImportWorkout(val externalId: String, val date: LocalDate, val title: String, val plan: String?, val week: Int?, val finished: LocalDateTime?, val exercises: List<ImportExercise>)

data class ImportDraft(val workouts: List<ImportWorkout>, val leftOut: Map<String, Int>) {
    val sets: Int get() = workouts.sumOf { w -> w.exercises.sumOf { it.sets.size } }
    val names: List<ImportExercise> get() = workouts.flatMap { it.exercises }.distinctBy { it.key }

    /** A heavy, recent set to ask about; [skip] gives the next one. */
    fun sample(skip: Int): Triple<ImportWorkout, ImportExercise, ImportSet>? {
        val all = workouts.sortedByDescending { it.date }.flatMap { w -> w.exercises.mapNotNull { e -> e.sets.filter { (it.weight ?: 0.0) > 0 && it.kind.counts }.maxByOrNull { it.weight!! }?.let { Triple(w, e, it) } } }
            .distinctBy { it.second.key }
        return if (all.isEmpty()) null else all[skip % all.size]
    }

    /** Weight times reps of all sets that count, in grams, for the last check. */
    fun volume(unit: FileUnit): Long = workouts.sumOf { w -> w.exercises.sumOf { e -> e.sets.filter { it.kind.counts }.sumOf { s -> (BoostcampImport.grams(s.weight, unit) ?: 0).toLong() * (s.reps ?: 0) } } }
}

object ImportNames {
    fun plain(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().split(" ").filter { it.isNotEmpty() && it !in SKIP }.joinToString(" ") { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
    private val SKIP = setOf("the", "a", "with", "and", "grip", "medium")

    fun key(name: String, equipment: String?): String = plain(name) + "|" + plain(equipment.orEmpty())

    fun equipment(text: String?): Equipment? = when (plain(text.orEmpty()).split(" ").firstOrNull()) {
        "barbell", "ez", "smith" -> Equipment.Barbell
        "dumbbell" -> Equipment.Dumbbell
        "machine" -> Equipment.Machine
        "cable" -> Equipment.Cable
        "kettlebell" -> Equipment.Kettlebell
        "body", "bodyweight" -> Equipment.Body
        "band" -> Equipment.Band
        else -> null
    }

    /** Sure matches only: an alias the user confirmed, or the same words and not another equipment. Anything else is asked. */
    fun sure(e: ImportExercise, exercises: List<ExerciseEntity>, aliases: Map<String, String>): ExerciseEntity? {
        aliases[e.key]?.let { id -> exercises.firstOrNull { it.id == id }?.let { return it } }
        val want = plain(e.name).split(" ").toSortedSet()
        val eq = equipment(e.equipment)
        val wanted = if (eq == null || eq == Equipment.Body) setOf(want) else setOf(want, (want + plain(eq.label)).toSortedSet())
        val hits = exercises.filter { plain(it.name).split(" ").toSortedSet() in wanted && (eq == null || it.equipment == eq) }
        return hits.singleOrNull() ?: hits.singleOrNull { it.custom }
    }

    /** A proposal to confirm: most words in common. */
    fun guess(e: ImportExercise, exercises: List<ExerciseEntity>): ExerciseEntity? {
        val eq = equipment(e.equipment)
        val want = (plain(e.name) + if (eq != null) " " + plain(eq.label) else "").split(" ").toSet()
        return exercises.map { x -> x to plain(x.name).split(" ").toSet() }
            .map { (x, w) -> x to (w intersect want).size * 2.0 / (w.size + want.size) + (if (eq != null && x.equipment == eq) 0.1 else 0.0) }
            .filter { it.second >= 0.6 }.maxByOrNull { it.second }?.first
    }
}

object BoostcampImport {
    const val SOURCE = "boostcamp"
    const val MAX_BYTES = 40 * 1024 * 1024
    private val json = Json { isLenient = true }

    fun grams(v: Double?, unit: FileUnit): Int? = v?.takeIf { it > 0 }?.let { Weights.grams(it, if (unit == FileUnit.Pounds) WeightUnit.Lb else WeightUnit.Kg) }

    /** Reads the whole file. Throws [IllegalArgumentException] with a plain reason when it is not a history file. */
    fun read(text: String): ImportDraft {
        val root = try { json.parseToJsonElement(text) } catch (_: Exception) { throw IllegalArgumentException("This file can't be read. Save the Response of the history request as it is.") }
        val days = (root as? JsonObject)?.let { o -> (o["data"] as? JsonObject) ?: o.takeIf { it.keys.any(::isDate) } }
            ?: throw IllegalArgumentException("This is not a Boostcamp history file.")
        val out = HashMap<String, Int>()
        fun drop(why: String) { out[why] = (out[why] ?: 0) + 1 }
        val workouts = mutableListOf<ImportWorkout>()
        days.forEach { (day, list) ->
            val date = runCatching { LocalDate.parse(day.take(10)) }.getOrNull()
            if (date == null || list !is JsonArray) { drop("days that can't be read"); return@forEach }
            list.forEach { w -> workout(w as? JsonObject, date, ::drop)?.let { workouts += it } }
        }
        require(workouts.isNotEmpty()) { "There are no workouts in this file." }
        return ImportDraft(workouts.distinctBy { it.externalId }.sortedBy { it.date }, out)
    }

    private fun isDate(s: String) = Regex("\\d{4}-\\d{2}-\\d{2}.*").matches(s)

    private fun workout(o: JsonObject?, date: LocalDate, drop: (String) -> Unit): ImportWorkout? {
        if (o == null) { drop("workouts that can't be read"); return null }
        val exercises = (o["records"] as? JsonArray).orEmpty().flatMap { r -> record(r as? JsonObject, null, drop) }
        if (exercises.isEmpty()) { drop("workouts without sets"); return null }
        val program = o.text("name")
        val title = o.text("title") ?: program ?: "Workout"
        // Without an id, what was done that day is the id: the same file again gives the same one.
        val id = o.text("id") ?: sha("$date|$title|" + exercises.joinToString { e -> e.name + e.sets.joinToString { "${it.weight}x${it.reps}" } })
        return ImportWorkout(id, date, title, program.takeIf { it != title }, o.int("week"), o.text("finished_at")?.let(::time), exercises)
    }

    private fun record(o: JsonObject?, group: String?, drop: (String) -> Unit): List<ImportExercise> {
        if (o == null) { drop("exercises that can't be read"); return emptyList() }
        if (o.text("type").equals("superset", ignoreCase = true)) {
            val g = o.text("uniq") ?: o.text("id") ?: "S"
            return (o["supersets"] as? JsonArray).orEmpty().flatMap { record(it as? JsonObject, g, drop) }
        }
        val name = o.text("name")
        if (name == null) { drop("exercises without a name"); return emptyList() }
        val sets = (o["sets"] as? JsonArray).orEmpty().mapNotNull { set(it as? JsonObject, drop) }
        return if (sets.isEmpty()) emptyList() else listOf(ImportExercise(name, o.text("type"), group, sets))
    }

    /** Only what was really done: the archived numbers. The planned ones beside them (value, amount) are never used as results. */
    private fun set(o: JsonObject?, drop: (String) -> Unit): ImportSet? {
        if (o == null) { drop("sets that can't be read"); return null }
        if (o.bool("skipped")) { drop("skipped sets"); return null }
        val weight = o.num("archived_weight")?.takeIf { it >= 0 && it < 5000 }
        val amount = o.num("archived_reps")?.takeIf { it > 0 && it < 100_000 }
        if (amount == null) { drop(if (o.containsKey("archived_reps")) "empty sets" else "sets with only planned numbers"); return null }
        val type = o.text("target_type").orEmpty().lowercase()
        val timed = type == "time"
        val label = listOfNotNull(o.text("set_type"), o.text("setType"), o.text("type")).joinToString(" ").lowercase()
        val kind = when {
            o.bool("is_warmup") || o.bool("isWarmup") || "warm" in label -> SetKind.Warmup
            "drop" in label -> SetKind.Drop
            "fail" in label -> SetKind.Failure
            "amrap" in type || o.text("amount")?.endsWith("+") == true -> SetKind.Max
            else -> SetKind.Working
        }
        // 0 means "not given" in the file.
        val rpe = (o.num("archived_rpe") ?: o.num("rpe"))?.takeIf { it in 1.0..10.0 }?.let { (it * 2).roundToInt() * 5 }
        return ImportSet(kind, weight?.takeIf { it > 0 }, if (timed) null else amount.roundToInt(), if (timed) amount.roundToInt() else null, rpe)
    }

    private fun time(s: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(s.take(19).replace('T', ' '), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.getOrNull()

    private fun JsonObject.prim(k: String) = this[k] as? JsonPrimitive
    private fun JsonObject.text(k: String): String? = prim(k)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.num(k: String): Double? = text(k)?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun JsonObject.int(k: String): Int? = num(k)?.roundToInt()
    private fun JsonObject.bool(k: String): Boolean = prim(k)?.let { it.booleanOrNull ?: (it.contentOrNull == "1") } ?: false
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** What would be saved. [chosen] says which exercise each name is; names without one become new own exercises. */
    class Rows(
        val batch: ImportBatchEntity, val newExercises: List<ExerciseEntity>, val aliases: List<AliasEntity>, val sessions: List<SessionEntity>,
        val exercises: List<SessionExerciseEntity>, val sets: List<SetEntity>, val refs: List<ImportRefEntity>, val already: Int,
    ) {
        val volume: Long get() = sets.filter { it.kind.counts }.sumOf { (it.grams ?: 0).toLong() * (it.reps ?: 0) }
    }

    fun rows(draft: ImportDraft, unit: FileUnit, chosen: Map<String, String>, known: Set<String>, fileName: String, nowMs: Long, zone: ZoneId): Rows {
        val batchId = UUID.randomUUID().toString()
        val fresh = draft.workouts.filter { it.externalId !in known }
        val newEx = LinkedHashMap<String, ExerciseEntity>()
        fun exercise(e: ImportExercise): String = chosen[e.key] ?: newEx.getOrPut(e.key) {
            val track = if (e.sets.any { it.seconds != null }) Track.Time else if (e.sets.any { it.weight != null }) Track.WeightReps else Track.Reps
            ExerciseEntity("mine-" + sha(e.key), e.name, ImportNames.equipment(e.equipment) ?: Equipment.Other, track, Muscle.Other, custom = true)
        }.id
        val sessions = mutableListOf<SessionEntity>()
        val ex = mutableListOf<SessionExerciseEntity>()
        val sets = mutableListOf<SetEntity>()
        val refs = mutableListOf<ImportRefEntity>()
        fresh.forEach { w ->
            // The file says when a workout ended, not when it began. The length is an estimate and marked as unsure.
            val end = w.finished?.takeIf { abs(Duration.between(w.date.atStartOfDay(), it).toHours()) < 48 }?.atZone(zone)?.toInstant()
            val guess = w.exercises.sumOf { it.sets.size } * 150_000L
            val endMs = end?.toEpochMilli() ?: w.date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val s = SessionEntity(UUID.randomUUID().toString(), null, w.title, w.plan, w.week, endMs - guess, endMs, zone.id, w.date.toString(), SessionState.Finished, SOURCE, changedMs = nowMs, timeUnsure = true)
            sessions += s
            refs += ImportRefEntity(SOURCE, w.externalId, s.id, batchId)
            w.exercises.forEachIndexed { i, e ->
                val x = SessionExerciseEntity(UUID.randomUUID().toString(), s.id, exercise(e), e.name, i, e.superset)
                ex += x
                e.sets.forEachIndexed { k, set -> sets += SetEntity(UUID.randomUUID().toString(), x.id, k, set.kind, grams(set.weight, unit), set.reps, set.seconds, set.rpe10, done = true, doneMs = endMs) }
            }
        }
        return Rows(
            ImportBatchEntity(batchId, nowMs, fileName, sessions.size, sets.size, draft.leftOut.values.sum()), newEx.values.toList(),
            chosen.map { (k, v) -> AliasEntity(k, v) }, sessions, ex, sets, refs, draft.workouts.size - fresh.size,
        )
    }
}
