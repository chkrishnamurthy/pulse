package com.fitbittrack.pulse.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** In the app's no-backup folder. */
const val CHAT_FILE = "chats.json"

@Serializable
data class SavedTurn(val question: String, val answer: String, val ai: String = "")

/** Chats kept on this phone, only if the user switched that on. Private storage, never backed up. */
class ChatStore(private val file: File) {
    suspend fun load(): List<SavedTurn> = withContext(Dispatchers.IO) {
        runCatching { Json.decodeFromString<List<SavedTurn>>(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun save(turns: List<SavedTurn>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        // Written beside and swapped in, so a crash never leaves half a file.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.encodeToString(turns.takeLast(MAX)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete(); Unit }

    private companion object { const val MAX = 40 }
}
