package com.fitbittrack.pulse.ui.training

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitbittrack.pulse.training.BoostcampImport
import com.fitbittrack.pulse.training.FileUnit
import com.fitbittrack.pulse.training.ImportBatchEntity
import com.fitbittrack.pulse.training.ImportNames
import com.fitbittrack.pulse.training.TrainingGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZoneId

/**
 * Training files: a backup copy saved or put back, and workouts brought in from another app.
 * Kept apart from [TrainingViewModel], which runs the plan and the workouts themselves.
 */
class TrainingFilesViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repo = TrainingGraph.repository(app)
    private val dao = repo.dao
    private val prefs = TrainingGraph.prefs(app)
    private val scope = TrainingGraph.scope(app)

    // ---------- backup ----------

    private val _note = MutableStateFlow<String?>(null)
    /** What came of saving or putting back a copy. */
    val note: StateFlow<String?> = _note.asStateFlow()
    fun clearNote() { _note.value = null }

    fun backupTo(uri: Uri) = viewModelScope.launch {
        _note.value = attempt("The copy could not be saved.") {
            val text = repo.backup()
            withContext(Dispatchers.IO) { (app.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")).use { it.write(text.toByteArray()) } }
            prefs.update { it.copy(lastBackupMs = System.currentTimeMillis()) }
            "Copy saved."
        }
    }

    fun restoreFrom(uri: Uri) = viewModelScope.launch {
        _note.value = attempt("This file could not be put back. Nothing was changed.") {
            if (dao.activeSessionNow() != null) return@attempt "Finish your open workout first."
            val file = withContext(Dispatchers.Default) { repo.readBackup(readText(uri)) }
            repo.restore(file)
            "Put back: ${words(file.sessions.size, "workout")} and ${words(file.programs.count { !it.archived }, "plan")}."
        }
    }

    fun deleteAll() = viewModelScope.launch { _note.value = attempt("Nothing was deleted.") { repo.deleteAll(); "All training was deleted." } }

    private suspend fun attempt(fallback: String, block: suspend () -> String): String = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        e.message ?: fallback
    } catch (_: Exception) {
        fallback
    }

    private suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = (app.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("The file can't be opened.")).use { it.readNBytesUpTo(BoostcampImport.MAX_BYTES + 1) }
        require(bytes.size <= BoostcampImport.MAX_BYTES) { "This file is too large." }
        bytes.decodeToString().trimStart(Char(0xFEFF))
    }

    private fun InputStream.readNBytesUpTo(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---------- import from another app ----------

    private val _import = MutableStateFlow<ImportStep>(ImportStep.Choose)
    val import: StateFlow<ImportStep> = _import.asStateFlow()
    val batches: StateFlow<List<ImportBatchEntity>> = dao.batches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun importReset() { _import.value = ImportStep.Choose }

    fun importFile(uri: Uri) = viewModelScope.launch {
        _import.value = ImportStep.Reading
        _import.value = try {
            val name = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
            } ?: "history.json"
            val text = readText(uri)
            ImportStep.Unit(withContext(Dispatchers.Default) { BoostcampImport.read(text) }, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            ImportStep.Failed(e.message ?: "This file can't be read.")
        } catch (_: Exception) {
            ImportStep.Failed("This file can't be read.")
        }
    }

    fun importNextSample() { (_import.value as? ImportStep.Unit)?.let { _import.value = it.copy(sample = it.sample + 1) } }

    fun importUnit(unit: FileUnit) = viewModelScope.launch {
        val s = _import.value as? ImportStep.Unit ?: return@launch
        val all = dao.allExercises().filter { !it.archived }
        val aliases = dao.aliases().associate { it.alias to it.exerciseId }
        _import.value = withContext(Dispatchers.Default) {
            val names = s.draft.names
            val sure = names.mapNotNull { n -> ImportNames.sure(n, all, aliases)?.let { n.key to it.id } }.toMap()
            val ask = names.filter { it.key !in sure }
            ImportStep.Names(
                s.draft,
                s.file,
                unit,
                sure,
                ask,
                ask.associate { it.key to null },
                ask.mapNotNull { n -> ImportNames.guess(n, all)?.let { n.key to it.id } }.toMap(),
            )
        }
    }

    /** Null = make it a new exercise of the user's own. */
    fun importChoose(
        key: String,
        exerciseId: String?,
    ) { (_import.value as? ImportStep.Names)?.let { _import.value = it.copy(chosen = it.chosen + (key to exerciseId)) } }

    fun importUseGuesses() { (_import.value as? ImportStep.Names)?.let { s -> _import.value = s.copy(chosen = s.chosen + s.guess.filterKeys { s.chosen[it] == null }) } }

    fun importCheck() = viewModelScope.launch {
        val s = _import.value as? ImportStep.Names ?: return@launch
        val chosen = s.sure + s.chosen.mapNotNull { (k, v) -> v?.let { k to it } }
        val known = dao.knownExternal(BoostcampImport.SOURCE).toSet()
        _import.value = withContext(Dispatchers.Default) {
            ImportStep.Check(
                s.draft,
                s.file,
                s.unit,
                chosen,
                BoostcampImport.rows(s.draft, s.unit, chosen, known, s.file, System.currentTimeMillis(), ZoneId.systemDefault()),
            )
        }
    }

    fun importSave() = viewModelScope.launch {
        val s = _import.value as? ImportStep.Check ?: return@launch
        _import.value = ImportStep.Reading
        _import.value = try {
            val r = s.rows
            if (r.sessions.isNotEmpty()) dao.saveImport(r.batch, r.newExercises, r.aliases, r.sessions, r.exercises, r.sets, r.refs)
            ImportStep.Saved(r.sessions.size, r.sets.size, r.already)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ImportStep.Failed("Nothing was saved. Please try again.")
        }
    }

    fun importBack() {
        _import.value = when (val s = _import.value) {
            is ImportStep.Names -> ImportStep.Unit(s.draft, s.file)
            else -> ImportStep.Choose
        }
    }

    fun undoImport(id: String) = scope.launch { dao.undoImport(id) }
}
