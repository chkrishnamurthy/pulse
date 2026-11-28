package com.fitbittrack.pulse.training

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.trainingStore: DataStore<Preferences> by preferencesDataStore(name = "training_prefs")

/** The user's choices for training. */
data class TrainingSettings(
    val unit: WeightUnit = WeightUnit.Kg,
    val effort: EffortMode = EffortMode.Rpe,
    val autoRest: Boolean = true,
    val signal: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Writes finished workouts to Health Connect. Off until the user switches it on. */
    val healthConnect: Boolean = false,
    val reminderOn: Boolean = false,
    val reminderAt: LocalTime = LocalTime.of(8, 0),
    val barGrams: Int = 20_000,
    /** Plates for one side, in grams. */
    val plates: List<Int> = KG_PLATES,
    /** Hard sets a week that the user aims for, per muscle. */
    val ranges: Map<Muscle, IntRange> = emptyMap(),
    val lastBackupMs: Long? = null,
    val backupReminder: Boolean = true,
) {
    fun range(m: Muscle): IntRange = ranges[m] ?: defaultRange(m)

    companion object {
        val KG_PLATES = listOf(25_000, 20_000, 15_000, 10_000, 5_000, 2_500, 1_250)
        val LB_PLATES = listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5).map { Weights.grams(it, WeightUnit.Lb) }

        fun defaultRange(m: Muscle): IntRange = when (m) {
            Muscle.Arms, Muscle.Shoulders, Muscle.Calves -> 8..14
            Muscle.Core, Muscle.Other -> 4..12
            else -> 10..16
        }

        // Written as plain text, so a backup can carry the choices as they are.
        fun decode(m: Map<String, String>): TrainingSettings {
            val d = TrainingSettings()
            fun on(k: String, default: Boolean) = m[k]?.let { it == "1" } ?: default
            return TrainingSettings(
                unit = WeightUnit.entries.firstOrNull { it.name == m["unit"] } ?: d.unit,
                effort = EffortMode.entries.firstOrNull { it.name == m["effort"] } ?: d.effort,
                autoRest = on("autoRest", d.autoRest), signal = on("signal", d.signal), keepScreenOn = on("screen", d.keepScreenOn),
                healthConnect = on("hc", d.healthConnect), reminderOn = on("remind", d.reminderOn),
                reminderAt = m["remindAt"]?.toIntOrNull()?.takeIf { it in 0 until 1440 }?.let { LocalTime.of(it / 60, it % 60) } ?: d.reminderAt,
                barGrams = m["bar"]?.toIntOrNull()?.takeIf { it in 0..60_000 } ?: d.barGrams,
                plates = m["plates"]?.split(",")?.mapNotNull { it.toIntOrNull()?.takeIf { g -> g in 100..60_000 } }?.takeIf { it.isNotEmpty() } ?: d.plates,
                ranges = m["ranges"].orEmpty().split("|").mapNotNull { e ->
                    val p = e.split(":")
                    val muscle = Muscle.entries.firstOrNull { it.name == p.getOrNull(0) } ?: return@mapNotNull null
                    val lo = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    val hi = p.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                    if (lo in 0..40 && hi in lo..40) muscle to lo..hi else null
                }.toMap(),
                lastBackupMs = m["backupAt"]?.toLongOrNull(), backupReminder = on("backupRemind", d.backupReminder),
            )
        }

        fun encode(s: TrainingSettings): Map<String, String> = buildMap {
            fun put(k: String, v: Boolean) = put(k, if (v) "1" else "0")
            put("unit", s.unit.name); put("effort", s.effort.name)
            put("autoRest", s.autoRest); put("signal", s.signal); put("screen", s.keepScreenOn); put("hc", s.healthConnect)
            put("remind", s.reminderOn); put("remindAt", "${s.reminderAt.hour * 60 + s.reminderAt.minute}")
            put("bar", "${s.barGrams}"); put("plates", s.plates.joinToString(","))
            put("ranges", s.ranges.entries.joinToString("|") { (m, r) -> "${m.name}:${r.first}:${r.last}" })
            s.lastBackupMs?.let { put("backupAt", "$it") }
            put("backupRemind", s.backupReminder)
        }
    }
}

class TrainingPrefs(context: Context) {
    private val store = context.applicationContext.trainingStore

    val settings: Flow<TrainingSettings> = store.data.map { p -> TrainingSettings.decode(p.asMap().entries.associate { (k, v) -> k.name to v.toString() }) }

    suspend fun set(s: TrainingSettings) {
        store.edit { p -> TrainingSettings.encode(s).forEach { (k, v) -> p[stringPreferencesKey(k)] = v } }
    }

    suspend fun update(change: (TrainingSettings) -> TrainingSettings) {
        store.edit { p ->
            val now = TrainingSettings.decode(p.asMap().entries.associate { (k, v) -> k.name to v.toString() })
            TrainingSettings.encode(change(now)).forEach { (k, v) -> p[stringPreferencesKey(k)] = v }
        }
    }
}
