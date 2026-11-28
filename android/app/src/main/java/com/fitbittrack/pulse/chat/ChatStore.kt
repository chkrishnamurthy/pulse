package com.fitbittrack.pulse.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.io.File

/** In the app's no-backup folder. */
const val CHAT_FILE = "chats.json"

@Serializable
data class SavedTurn(val question: String, val answer: String)

/** Chats kept on this phone, only if the user switched that on. Private storage, never backed up. */
class ChatStore(private val file: File) {
    suspend fun load(): List<SavedTurn> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<List<SavedTurn>>(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun save(turns: List<SavedTurn>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        // Written beside and swapped in, so a crash never leaves half a file.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(turns.takeLast(MAX)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete(); Unit }

    /**
     * Chats saved while the AI was in Pulse can still hold its words (an "ai" field). Writes the file again without them.
     * Questions and Pulse's own answers stay. A file that can't be read is left as it is. True if the file changed.
     */
    suspend fun dropAiText(): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        val text = runCatching { file.readText() }.getOrNull() ?: return@withContext false
        val raw = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return@withContext false
        if (raw.none { it is JsonObject && "ai" in it }) return@withContext false
        val turns = runCatching { json.decodeFromString<List<SavedTurn>>(text) }.getOrNull() ?: return@withContext false
        save(turns)
        true
    }

    private companion object {
        const val MAX = 40
        val json = Json { ignoreUnknownKeys = true }
    }
}
