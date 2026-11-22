package com.fitbittrack.pulse.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Offline copy of your health data. Every row has a natural key (date + metric, or Google's
 * data point name), and every write is an upsert, so syncing the same data twice never makes
 * duplicates. Dates are ISO strings ("2026-11-22") so they sort and compare correctly.
 *
 * Privacy: the file lives in the app's private storage (Android encrypts it at rest), backups
 * are switched off in the manifest, and nothing here is ever logged.
 */

@Entity(tableName = "daily_metric", primaryKeys = ["date", "metric"])
data class DailyMetricEntity(val date: String, val metric: String, val value: Double)

@Entity(tableName = "heart_rate")
data class HeartRateEntity(@PrimaryKey val epochSecond: Long, val date: String, val bpm: Int)

@Entity(tableName = "hourly_steps", primaryKeys = ["date", "hour"])
data class HourlyStepsEntity(val date: String, val hour: Int, val steps: Long)

@Entity(tableName = "sleep_session")
data class SleepEntity(
    @PrimaryKey val id: String,
    val startMs: Long,
    val endMs: Long,
    /** Local day you woke up. */
    val wakeDate: String,
    val mainSleep: Boolean,
    val minutesAsleep: Long?,
    /** Stages as compact JSON: [{"t":"DEEP","s":ms,"e":ms}, …] */
    val stagesJson: String,
)

@Entity(tableName = "workout")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val startDate: String,
    val activeSeconds: Long?,
    val caloriesKcal: Double?,
    val distanceMeters: Double?,
    val avgHeartRate: Int?,
)

/** Remembers when an on-demand detail fetch (heart rate, hourly steps) was done for a day. */
@Entity(tableName = "fetched_day", primaryKeys = ["date", "kind"])
data class FetchedDayEntity(val date: String, val kind: String, val fetchedAtMs: Long)

@Dao
interface HealthDao {
    @Upsert suspend fun upsertDaily(rows: List<DailyMetricEntity>)
    @Upsert suspend fun upsertHeartRate(rows: List<HeartRateEntity>)
    @Upsert suspend fun upsertHourly(rows: List<HourlyStepsEntity>)
    @Upsert suspend fun upsertSleep(rows: List<SleepEntity>)
    @Upsert suspend fun upsertWorkouts(rows: List<WorkoutEntity>)
    @Upsert suspend fun upsertFetched(row: FetchedDayEntity)

    @Query("SELECT * FROM daily_metric WHERE date >= :from AND date <= :to ORDER BY date")
    fun daily(from: String, to: String): Flow<List<DailyMetricEntity>>

    @Query("SELECT COUNT(*) FROM daily_metric WHERE metric = :metric AND date >= :from AND date < :toExclusive")
    suspend fun countDaily(metric: String, from: String, toExclusive: String): Int

    @Query("SELECT * FROM heart_rate WHERE date = :date ORDER BY epochSecond")
    fun heartRate(date: String): Flow<List<HeartRateEntity>>

    @Query("DELETE FROM heart_rate WHERE date = :date")
    suspend fun deleteHeartRate(date: String)

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour")
    fun hourly(date: String): Flow<List<HourlyStepsEntity>>

    @Query("SELECT * FROM sleep_session WHERE wakeDate >= :from AND wakeDate <= :to ORDER BY startMs")
    fun sleep(from: String, to: String): Flow<List<SleepEntity>>

    @Query("SELECT * FROM workout WHERE startDate >= :from AND startDate <= :to ORDER BY startMs DESC")
    fun workouts(from: String, to: String): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM fetched_day WHERE date = :date AND kind = :kind")
    suspend fun fetched(date: String, kind: String): FetchedDayEntity?
}

@Database(
    entities = [
        DailyMetricEntity::class, HeartRateEntity::class, HourlyStepsEntity::class,
        SleepEntity::class, WorkoutEntity::class, FetchedDayEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun dao(): HealthDao

    companion object {
        fun create(context: Context): PulseDatabase =
            Room.databaseBuilder(context.applicationContext, PulseDatabase::class.java, "pulse-health.db")
                // Early versions: if the table layout changes, rebuild the cache from Google.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
