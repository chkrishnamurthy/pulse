package com.fitbittrack.pulse.training

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/*
 * The only copy of what the user made: exercises, plans, the calendar and every logged set.
 *
 * It is its own file, apart from pulse-health.db on purpose. That one is a copy of Google's data and may be erased and
 * downloaded again at any time (it is, on sign-out and when its layout changes). This one can't be downloaded again, so:
 * never a destructive migration, never cleared by signing out, and every layout is written to app/schemas.
 *
 * Plan, calendar and history are apart. History keeps its own copy of names and targets, so a change to a plan can never
 * change a workout that was done.
 */

@Serializable
@Entity(tableName = "exercise")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val equipment: Equipment,
    val track: Track,
    val muscle: Muscle,
    /** Muscle names, comma separated. */
    val secondary: String = "",
    val custom: Boolean = false,
    val favourite: Boolean = false,
    val archived: Boolean = false,
    val restSec: Int = 120,
    val stepGrams: Int = 2500,
) {
    val alsoTrains: List<Muscle> get() = secondary.split(",").mapNotNull { n -> Muscle.entries.firstOrNull { it.name == n } }
    val about: String get() = "${muscle.label}, ${equipment.label.lowercase()}" + if (custom) ", yours" else ""
}

/** Another name for an exercise, from an import. */
@Serializable
@Entity(tableName = "exercise_alias")
data class AliasEntity(@PrimaryKey val alias: String, val exerciseId: String)

@Serializable
@Entity(tableName = "program")
data class ProgramEntity(@PrimaryKey val id: String, val title: String, val daysPerWeek: Int, val createdAtMs: Long, val archived: Boolean = false)

/** [doc] is a [PlanDoc] as JSON. Rows are only added, never changed. The highest [number] is the plan as it is now. */
@Serializable
@Entity(tableName = "program_revision", indices = [Index("programId")])
data class RevisionEntity(@PrimaryKey val id: String, val programId: String, val number: Int, val createdAtMs: Long, val doc: String)

@Serializable
enum class EnrollState { Active, Finished, Stopped }

/** A plan the user is following, or followed. At most one is active. */
@Serializable
@Entity(tableName = "enrollment", indices = [Index("programId")])
data class EnrollmentEntity(
    @PrimaryKey val id: String, val programId: String, val revisionId: String, val startDate: String,
    /** ISO day numbers, comma separated: "2,3,5,6". */
    val weekdays: String, val state: EnrollState, val startedAtMs: Long, val endedAtMs: Long? = null,
)

@Serializable
@Entity(tableName = "planned_session", indices = [Index("enrollmentId"), Index("date")])
data class PlannedEntity(
    @PrimaryKey val id: String, val enrollmentId: String, val seq: Int, val week: Int, val day: Int,
    val title: String, val date: String, val firstDate: String, val state: PlannedState,
    val pinned: Boolean = false,
    /** Part of the normal weight, in percent: 90 in an easy week. */
    val easy: Int = 100,
)

@Serializable
enum class SessionState { Active, Finished, Discarded }

@Serializable
@Entity(tableName = "session", indices = [Index("date"), Index("state")])
data class SessionEntity(
    @PrimaryKey val id: String, val plannedId: String?, val title: String, val planTitle: String?, val week: Int?,
    val startMs: Long, val endMs: Long?, val zone: String,
    /** The local day it began. */
    val date: String, val state: SessionState,
    /** "pulse" or "boostcamp". */
    val source: String = "pulse",
    val feeling: Int? = null, val note: String = "",
    val restEndsMs: Long? = null, val restTotalSec: Int = 0,
    val changedMs: Long = 0,
    /** True when only the day is known: an import without times. */
    val timeUnsure: Boolean = false,
)

@Serializable
@Entity(tableName = "session_exercise", indices = [Index("sessionId"), Index("exerciseId")])
data class SessionExerciseEntity(
    @PrimaryKey val id: String, val sessionId: String, val exerciseId: String,
    /** The name on that day. */
    val name: String,
    val pos: Int, val superset: String? = null, val restSec: Int = 120, val target: String = "", val reason: String = "",
)

@Serializable
@Entity(tableName = "set_log", indices = [Index("sessionExerciseId")])
data class SetEntity(
    @PrimaryKey val id: String, val sessionExerciseId: String, val pos: Int, val kind: SetKind,
    val grams: Int? = null, val reps: Int? = null, val seconds: Int? = null, val rpe10: Int? = null,
    val done: Boolean = false, val doneMs: Long? = null,
    val targetGrams: Int? = null, val targetReps: Int? = null,
)

@Serializable
@Entity(tableName = "import_batch")
data class ImportBatchEntity(@PrimaryKey val id: String, val atMs: Long, val fileName: String, val workouts: Int, val sets: Int, val leftOut: Int)

/** Which Pulse session came from which outside workout, so the same file twice adds nothing. */
@Serializable
@Entity(tableName = "import_ref", primaryKeys = ["source", "externalId"], indices = [Index("batchId")])
data class ImportRefEntity(val source: String, val externalId: String, val sessionId: String, val batchId: String)

/** The dates before a change to the schedule, so it can be undone. [items] is JSON. */
@Serializable
@Entity(tableName = "schedule_undo")
data class UndoEntity(@PrimaryKey val id: String, val atMs: Long, val enrollmentId: String, val label: String, val items: String)

/** A finished set with what the screens need around it. */
data class HistoryRow(
    val sessionId: String, val date: String, val startMs: Long, val title: String, val exerciseId: String, val exerciseName: String,
    val kind: SetKind, val grams: Int?, val reps: Int?, val seconds: Int?, val rpe10: Int?, val pos: Int,
)

data class SessionTotals(val sessionId: String, val sets: Int, val reps: Int, val volume: Long)

@Dao
abstract class TrainingDao {
    // ---- exercises
    @Query("SELECT * FROM exercise WHERE archived = 0 ORDER BY name COLLATE NOCASE") abstract fun exercises(): Flow<List<ExerciseEntity>>
    @Query("SELECT * FROM exercise") abstract suspend fun allExercises(): List<ExerciseEntity>
    @Query("SELECT * FROM exercise WHERE id = :id") abstract suspend fun exercise(id: String): ExerciseEntity?
    @Query("SELECT * FROM exercise WHERE id = :id") abstract fun exerciseFlow(id: String): Flow<ExerciseEntity?>
    @Query("SELECT COUNT(*) FROM exercise WHERE custom = 0 AND id NOT LIKE 'edb-%'") abstract suspend fun seedCount(): Int

    // ---- ExerciseDB
    @Query("SELECT * FROM edb_exercise") abstract suspend fun allEdb(): List<EdbEntity>
    @Query("SELECT * FROM edb_exercise WHERE id = :id") abstract suspend fun edb(id: String): EdbEntity?
    @Query("SELECT COUNT(*) FROM edb_exercise") abstract suspend fun edbCount(): Int
    @Query("SELECT COUNT(*) FROM edb_exercise") abstract fun edbCountFlow(): Flow<Int>
    @Query("SELECT id FROM edb_exercise") abstract suspend fun edbIds(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putEdb(rows: List<EdbEntity>)
    @Query("SELECT * FROM edb_link") abstract suspend fun allLinks(): List<EdbLinkEntity>
    @Query("SELECT * FROM edb_link") abstract fun linksFlow(): Flow<List<EdbLinkEntity>>
    @Query("SELECT * FROM edb_link WHERE exerciseId = :id") abstract suspend fun link(id: String): EdbLinkEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putLinks(rows: List<EdbLinkEntity>)
    @Query("DELETE FROM edb_link") abstract suspend fun wipeLinks()
    @Query("SELECT id FROM exercise WHERE favourite = 1") abstract suspend fun favouriteIds(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addMissingExercises(rows: List<ExerciseEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putExercise(row: ExerciseEntity)
    @Query("SELECT COUNT(*) FROM session_exercise WHERE exerciseId = :id") abstract suspend fun timesUsed(id: String): Int
    @Query("DELETE FROM exercise WHERE id = :id AND custom = 1") abstract suspend fun deleteCustom(id: String)
    @Query("SELECT * FROM exercise_alias") abstract suspend fun aliases(): List<AliasEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putAliases(rows: List<AliasEntity>)

    // ---- plans
    @Query("SELECT * FROM program WHERE archived = 0 ORDER BY createdAtMs DESC") abstract fun programs(): Flow<List<ProgramEntity>>
    @Query("SELECT * FROM program WHERE id = :id") abstract suspend fun program(id: String): ProgramEntity?
    @Query("SELECT * FROM program WHERE id = :id") abstract fun programFlow(id: String): Flow<ProgramEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putProgram(row: ProgramEntity)
    @Query("SELECT * FROM program_revision WHERE programId = :programId ORDER BY number DESC LIMIT 1") abstract fun latestRevision(programId: String): Flow<RevisionEntity?>
    @Query("SELECT * FROM program_revision WHERE programId = :programId ORDER BY number DESC LIMIT 1") abstract suspend fun latestRevisionNow(programId: String): RevisionEntity?
    @Query("SELECT * FROM program_revision WHERE id = :id") abstract suspend fun revision(id: String): RevisionEntity?
    @Insert abstract suspend fun addRevision(row: RevisionEntity)
    @Query("SELECT * FROM enrollment ORDER BY startedAtMs DESC") abstract fun enrollments(): Flow<List<EnrollmentEntity>>
    @Query("SELECT * FROM enrollment WHERE state = 'Active' ORDER BY startedAtMs DESC LIMIT 1") abstract fun activeEnrollment(): Flow<EnrollmentEntity?>
    @Query("SELECT * FROM enrollment WHERE state = 'Active' ORDER BY startedAtMs DESC LIMIT 1") abstract suspend fun activeEnrollmentNow(): EnrollmentEntity?
    @Query("SELECT * FROM enrollment WHERE id = :id") abstract suspend fun enrollment(id: String): EnrollmentEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putEnrollment(row: EnrollmentEntity)

    // ---- calendar
    @Query("SELECT * FROM planned_session WHERE enrollmentId = :id ORDER BY seq") abstract fun planned(id: String): Flow<List<PlannedEntity>>
    @Query("SELECT * FROM planned_session WHERE enrollmentId = :id ORDER BY seq") abstract suspend fun plannedNow(id: String): List<PlannedEntity>
    @Query("SELECT * FROM planned_session WHERE date >= :from AND date <= :to ORDER BY date, seq") abstract fun plannedBetween(from: String, to: String): Flow<List<PlannedEntity>>
    @Query("SELECT * FROM planned_session WHERE id = :id") abstract suspend fun plannedOne(id: String): PlannedEntity?
    @Query("SELECT * FROM planned_session WHERE id = :id") abstract fun plannedFlow(id: String): Flow<PlannedEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putPlanned(rows: List<PlannedEntity>)
    @Query("DELETE FROM planned_session WHERE enrollmentId = :id AND state = 'Planned'") abstract suspend fun deleteOpenPlanned(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putUndo(row: UndoEntity)
    @Query("SELECT * FROM schedule_undo ORDER BY atMs DESC LIMIT 1") abstract fun lastUndo(): Flow<UndoEntity?>
    @Query("DELETE FROM schedule_undo WHERE id = :id") abstract suspend fun deleteUndo(id: String)
    @Query("DELETE FROM schedule_undo WHERE atMs < :before") abstract suspend fun dropOldUndo(before: Long)
    @Query("DELETE FROM schedule_undo WHERE enrollmentId = :id") abstract suspend fun dropUndoOf(id: String)

    // ---- history
    @Query("SELECT * FROM session WHERE state = 'Active' ORDER BY startMs DESC LIMIT 1") abstract fun activeSession(): Flow<SessionEntity?>
    @Query("SELECT * FROM session WHERE state = 'Active' ORDER BY startMs DESC LIMIT 1") abstract suspend fun activeSessionNow(): SessionEntity?
    @Query("SELECT * FROM session WHERE id = :id") abstract suspend fun session(id: String): SessionEntity?
    @Query("SELECT * FROM session WHERE id = :id") abstract fun sessionFlow(id: String): Flow<SessionEntity?>
    @Query("SELECT * FROM session WHERE state = 'Finished' ORDER BY date DESC, startMs DESC") abstract fun finished(): Flow<List<SessionEntity>>
    @Query(
        "SELECT * FROM session WHERE state = 'Discarded' AND EXISTS (SELECT 1 FROM session_exercise e JOIN set_log l ON l.sessionExerciseId = e.id WHERE e.sessionId = session.id AND l.done = 1) ORDER BY changedMs DESC",
    )
    abstract fun discarded(): Flow<List<SessionEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putSession(row: SessionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putSessions(rows: List<SessionEntity>)
    @Update abstract suspend fun updateSession(row: SessionEntity)
    @Query("UPDATE session SET restEndsMs = :ends, restTotalSec = :total WHERE id = :id") abstract suspend fun setRest(id: String, ends: Long?, total: Int)
    @Query("DELETE FROM session WHERE id = :id") abstract suspend fun deleteSessionRow(id: String)
    @Query("SELECT id FROM session WHERE state = 'Discarded' AND changedMs < :before") abstract suspend fun oldDiscarded(before: Long): List<String>
    @Query("SELECT * FROM session_exercise WHERE sessionId = :id ORDER BY pos") abstract fun sessionExercises(id: String): Flow<List<SessionExerciseEntity>>
    @Query("SELECT * FROM session_exercise WHERE sessionId = :id ORDER BY pos") abstract suspend fun sessionExercisesNow(id: String): List<SessionExerciseEntity>
    @Query("SELECT * FROM session_exercise WHERE id = :id") abstract suspend fun sessionExercise(id: String): SessionExerciseEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putSessionExercises(rows: List<SessionExerciseEntity>)
    @Query("DELETE FROM session_exercise WHERE id = :id") abstract suspend fun deleteSessionExerciseRow(id: String)
    @Query("SELECT s.* FROM set_log s JOIN session_exercise e ON e.id = s.sessionExerciseId WHERE e.sessionId = :id ORDER BY e.pos, s.pos") abstract fun setsOfSession(id: String): Flow<List<SetEntity>>
    @Query("SELECT s.* FROM set_log s JOIN session_exercise e ON e.id = s.sessionExerciseId WHERE e.sessionId = :id ORDER BY e.pos, s.pos") abstract suspend fun setsOfSessionNow(id: String): List<SetEntity>
    @Query("SELECT * FROM set_log WHERE sessionExerciseId = :id ORDER BY pos") abstract suspend fun setsOf(id: String): List<SetEntity>
    @Query("SELECT * FROM set_log WHERE id = :id") abstract suspend fun set(id: String): SetEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putSets(rows: List<SetEntity>)
    @Query("DELETE FROM set_log WHERE id = :id") abstract suspend fun deleteSet(id: String)
    @Query("DELETE FROM set_log WHERE sessionExerciseId = :id") abstract suspend fun deleteSetsOf(id: String)

    @Query(
        "SELECT s.sessionId, x.date, x.startMs, x.title, s.exerciseId, s.name AS exerciseName, l.kind, l.grams, l.reps, l.seconds, l.rpe10, l.pos FROM set_log l " +
            "JOIN session_exercise s ON s.id = l.sessionExerciseId JOIN session x ON x.id = s.sessionId WHERE x.state = 'Finished' AND l.done = 1 ORDER BY x.date, x.startMs, s.pos, l.pos",
    )
    abstract fun history(): Flow<List<HistoryRow>>

    @Query(
        "SELECT s.sessionId, x.date, x.startMs, x.title, s.exerciseId, s.name AS exerciseName, l.kind, l.grams, l.reps, l.seconds, l.rpe10, l.pos FROM set_log l " +
            "JOIN session_exercise s ON s.id = l.sessionExerciseId JOIN session x ON x.id = s.sessionId WHERE x.state = 'Finished' AND l.done = 1 AND s.exerciseId = :id ORDER BY x.date, x.startMs, s.pos, l.pos",
    )
    abstract suspend fun historyOf(id: String): List<HistoryRow>

    @Query(
        "SELECT s.sessionId AS sessionId, COUNT(l.id) AS sets, COALESCE(SUM(l.reps), 0) AS reps, COALESCE(SUM(CAST(COALESCE(l.grams, 0) AS INTEGER) * COALESCE(l.reps, 0)), 0) AS volume " +
            "FROM set_log l JOIN session_exercise s ON s.id = l.sessionExerciseId WHERE l.done = 1 AND l.kind != 'Warmup' GROUP BY s.sessionId",
    )
    abstract fun totals(): Flow<List<SessionTotals>>

    // ---- import
    @Query("SELECT * FROM import_batch ORDER BY atMs DESC") abstract fun batches(): Flow<List<ImportBatchEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putBatch(row: ImportBatchEntity)
    @Query("DELETE FROM import_batch WHERE id = :id") abstract suspend fun deleteBatchRow(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putRefs(rows: List<ImportRefEntity>)
    @Query("SELECT externalId FROM import_ref WHERE source = :source") abstract suspend fun knownExternal(source: String): List<String>
    @Query("SELECT * FROM import_ref WHERE batchId = :id") abstract suspend fun refsOf(id: String): List<ImportRefEntity>
    @Query("DELETE FROM import_ref WHERE batchId = :id") abstract suspend fun deleteRefsOf(id: String)
    @Query("DELETE FROM import_ref WHERE sessionId = :id") abstract suspend fun deleteRefOfSession(id: String)

    // ---- everything, for the backup file
    @Query("SELECT * FROM program") abstract suspend fun allPrograms(): List<ProgramEntity>
    @Query("SELECT * FROM program_revision") abstract suspend fun allRevisions(): List<RevisionEntity>
    @Query("SELECT * FROM enrollment") abstract suspend fun allEnrollments(): List<EnrollmentEntity>
    @Query("SELECT * FROM planned_session") abstract fun allPlannedFlow(): Flow<List<PlannedEntity>>
    @Query("SELECT * FROM planned_session") abstract suspend fun allPlanned(): List<PlannedEntity>
    @Query("SELECT * FROM session") abstract suspend fun allSessions(): List<SessionEntity>
    @Query("SELECT * FROM session_exercise") abstract suspend fun allSessionExercises(): List<SessionExerciseEntity>
    @Query("SELECT * FROM set_log") abstract suspend fun allSets(): List<SetEntity>
    @Query("SELECT * FROM import_batch") abstract suspend fun allBatches(): List<ImportBatchEntity>
    @Query("SELECT * FROM import_ref") abstract suspend fun allRefs(): List<ImportRefEntity>
    @Insert abstract suspend fun addExercises(rows: List<ExerciseEntity>)
    @Insert abstract suspend fun addPrograms(rows: List<ProgramEntity>)
    @Insert abstract suspend fun addRevisions(rows: List<RevisionEntity>)
    @Insert abstract suspend fun addEnrollments(rows: List<EnrollmentEntity>)
    @Insert abstract suspend fun addBatches(rows: List<ImportBatchEntity>)

    @Query("DELETE FROM exercise") abstract suspend fun wipeExercises()
    @Query("DELETE FROM exercise_alias") abstract suspend fun wipeAliases()
    @Query("DELETE FROM program") abstract suspend fun wipePrograms()
    @Query("DELETE FROM program_revision") abstract suspend fun wipeRevisions()
    @Query("DELETE FROM enrollment") abstract suspend fun wipeEnrollments()
    @Query("DELETE FROM planned_session") abstract suspend fun wipePlanned()
    @Query("DELETE FROM session") abstract suspend fun wipeSessions()
    @Query("DELETE FROM session_exercise") abstract suspend fun wipeSessionExercises()
    @Query("DELETE FROM set_log") abstract suspend fun wipeSets()
    @Query("DELETE FROM import_batch") abstract suspend fun wipeBatches()
    @Query("DELETE FROM import_ref") abstract suspend fun wipeRefs()
    @Query("DELETE FROM schedule_undo") abstract suspend fun wipeUndo()

    @Transaction
    open suspend fun wipe() {
        wipeSets(); wipeSessionExercises(); wipeSessions(); wipeRefs(); wipeBatches(); wipePlanned(); wipeUndo()
        wipeEnrollments(); wipeRevisions(); wipePrograms(); wipeAliases(); wipeLinks(); wipeExercises()
    }

    /** Puts a whole backup in place of everything. All or nothing. */
    @Transaction
    open suspend fun replaceAll(b: BackupFile) {
        wipe()
        addExercises(b.exercises); putAliases(b.aliases); addPrograms(b.programs); addRevisions(b.revisions); addEnrollments(b.enrollments)
        putPlanned(b.planned); putSessions(b.sessions); putSessionExercises(b.sessionExercises); putSets(b.sets); addBatches(b.batches); putRefs(b.refs); putLinks(b.links)
    }

    @Transaction
    open suspend fun deleteSession(id: String) {
        sessionExercisesNow(id).forEach { deleteSetsOf(it.id); deleteSessionExerciseRow(it.id) }
        deleteRefOfSession(id)
        deleteSessionRow(id)
    }

    /** Saves a whole import, or nothing. */
    @Transaction
    open suspend fun saveImport(
        batch: ImportBatchEntity, newExercises: List<ExerciseEntity>, aliases: List<AliasEntity>,
        sessions: List<SessionEntity>, exercises: List<SessionExerciseEntity>, sets: List<SetEntity>, refs: List<ImportRefEntity>,
    ) {
        putBatch(batch); addMissingExercises(newExercises); putAliases(aliases)
        putSessions(sessions); putSessionExercises(exercises); putSets(sets); putRefs(refs)
    }

    @Transaction
    open suspend fun undoImport(batchId: String) {
        refsOf(batchId).forEach { deleteSession(it.sessionId) }
        deleteRefsOf(batchId)
        deleteBatchRow(batchId)
    }

    @Transaction
    open suspend fun startPlan(enrollment: EnrollmentEntity, planned: List<PlannedEntity>, nowMs: Long) {
        activeEnrollmentNow()?.let {
            putEnrollment(it.copy(state = EnrollState.Stopped, endedAtMs = nowMs))
            deleteOpenPlanned(it.id)
            dropUndoOf(it.id)
        }
        putEnrollment(enrollment)
        putPlanned(planned)
    }

    @Transaction
    open suspend fun saveSchedule(undo: UndoEntity, rows: List<PlannedEntity>) {
        putUndo(undo)
        putPlanned(rows)
    }

    @Transaction
    open suspend fun addWorkout(session: SessionEntity, exercises: List<SessionExerciseEntity>, sets: List<SetEntity>) {
        putSession(session); putSessionExercises(exercises); putSets(sets)
    }

    /** A new version of a plan; whoever follows it goes on with the new one. */
    @Transaction
    open suspend fun saveRevision(rev: RevisionEntity, enrollment: EnrollmentEntity?, open: List<PlannedEntity>?) {
        addRevision(rev)
        if (enrollment != null && open != null) {
            putEnrollment(enrollment.copy(revisionId = rev.id))
            deleteOpenPlanned(enrollment.id)
            putPlanned(open)
        }
    }
}

@Database(
    entities = [
        ExerciseEntity::class, AliasEntity::class, ProgramEntity::class, RevisionEntity::class, EnrollmentEntity::class, PlannedEntity::class,
        SessionEntity::class, SessionExerciseEntity::class, SetEntity::class, ImportBatchEntity::class, ImportRefEntity::class, UndoEntity::class,
        EdbEntity::class, EdbLinkEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // Each step only adds. Older layouts are in app/schemas; a device test opens version 1 and moves it up.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class TrainingDatabase : RoomDatabase() {
    abstract fun dao(): TrainingDao

    companion object {
        /** No destructive fallback here, ever: a missing migration must fail loudly in testing, not erase someone's training. */
        fun create(context: Context): TrainingDatabase =
            Room.databaseBuilder(context.applicationContext, TrainingDatabase::class.java, "pulse-training.db").build()
    }
}

/** Everything, as one file. Catalogue exercises are kept too: their favourites, rest and steps may have been changed. */
@Serializable
data class BackupFile(
    val format: Int = FORMAT,
    val savedAtMs: Long,
    val exercises: List<ExerciseEntity> = emptyList(),
    val aliases: List<AliasEntity> = emptyList(),
    val programs: List<ProgramEntity> = emptyList(),
    val revisions: List<RevisionEntity> = emptyList(),
    val enrollments: List<EnrollmentEntity> = emptyList(),
    val planned: List<PlannedEntity> = emptyList(),
    val sessions: List<SessionEntity> = emptyList(),
    val sessionExercises: List<SessionExerciseEntity> = emptyList(),
    val sets: List<SetEntity> = emptyList(),
    val batches: List<ImportBatchEntity> = emptyList(),
    val refs: List<ImportRefEntity> = emptyList(),
    /** Ties to ExerciseDB. Missing in older files: Pulse ties the same names again by itself. */
    val links: List<EdbLinkEntity> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
) {
    companion object { const val FORMAT = 1; const val MARK = "pulse-training" }
}
