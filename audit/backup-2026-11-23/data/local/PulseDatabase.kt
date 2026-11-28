package com.fitbittrack.pulse.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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
abstract class HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertDaily(rows: List<DailyMetricEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertHeartRate(rows: List<HeartRateEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertHourly(rows: List<HourlyStepsEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertSleep(rows: List<SleepEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertWorkouts(rows: List<WorkoutEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertFetched(row: FetchedDayEntity)

    @Query("SELECT * FROM daily_metric WHERE date >= :from AND date <= :to ORDER BY date")
    abstract fun daily(from: String, to: String): Flow<List<DailyMetricEntity>>

    @Query("SELECT * FROM heart_rate WHERE date = :date ORDER BY epochSecond")
    abstract fun heartRate(date: String): Flow<List<HeartRateEntity>>

    @Query("DELETE FROM heart_rate WHERE date = :date")
    abstract suspend fun deleteHeartRate(date: String)

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour")
    abstract fun hourly(date: String): Flow<List<HourlyStepsEntity>>

    @Query("DELETE FROM hourly_steps WHERE date = :date")
    abstract suspend fun deleteHourly(date: String)

    @Query("SELECT * FROM sleep_session WHERE wakeDate >= :from AND wakeDate <= :to ORDER BY startMs")
    abstract fun sleep(from: String, to: String): Flow<List<SleepEntity>>

    @Query("DELETE FROM sleep_session WHERE wakeDate >= :from")
    abstract suspend fun deleteSleepFrom(from: String)

    @Query("SELECT * FROM workout WHERE startDate >= :from AND startDate <= :to ORDER BY startMs DESC")
    abstract fun workouts(from: String, to: String): Flow<List<WorkoutEntity>>

    @Query("DELETE FROM workout WHERE startDate >= :from")
    abstract suspend fun deleteWorkoutsFrom(from: String)

    @Query("SELECT * FROM fetched_day WHERE date = :date AND kind = :kind")
    abstract suspend fun fetched(date: String, kind: String): FetchedDayEntity?

    /** Heart rate for one day, swapped in one step so the graph never flashes empty. */
    @Transaction
    open suspend fun replaceHeartRate(date: String, rows: List<HeartRateEntity>) {
        deleteHeartRate(date); upsertHeartRate(rows)
    }

    @Transaction
    open suspend fun replaceHourly(date: String, rows: List<HourlyStepsEntity>) {
        deleteHourly(date); upsertHourly(rows)
    }

    /** Makes the local copy match Google for every wake day from [from] on (removes sessions deleted at Google). */
    @Transaction
    open suspend fun replaceSleepFrom(from: String, rows: List<SleepEntity>) {
        deleteSleepFrom(from); upsertSleep(rows)
    }

    @Transaction
    open suspend fun replaceWorkoutsFrom(from: String, rows: List<WorkoutEntity>) {
        deleteWorkoutsFrom(from); upsertWorkouts(rows)
    }
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
