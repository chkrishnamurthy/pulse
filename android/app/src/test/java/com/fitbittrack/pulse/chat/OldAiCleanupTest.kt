package com.fitbittrack.pulse.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OldAiCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the AI folder is deleted, everything beside it stays`() {
        val noBackup = tmp.newFolder("no_backup")
        val models = File(noBackup, OldAiCleanup.MODELS_DIR).apply { mkdirs() }
        File(models, "gemma-4-E2B-it.litertlm").writeText("big")
        File(models, "gemma-4-E2B-it.litertlm.part").writeText("half")
        val chats = File(noBackup, CHAT_FILE).apply { writeText("[]") }
        val work = File(noBackup, "androidx.work.workdb").apply { writeText("db") }

        assertTrue(OldAiCleanup.deleteFiles(noBackup))
        assertFalse(models.exists())
        assertTrue(chats.exists() && work.exists())
        // Second start: nothing left to do.
        assertFalse(OldAiCleanup.deleteFiles(noBackup))
    }

    @Test fun `AI words leave saved chats, questions and answers stay`() = runBlocking {
        val file = tmp.newFile(CHAT_FILE)
        file.writeText("""[{"question":"q1","answer":"a1","ai":"made up"},{"question":"q2","answer":"a2"}]""")
        val store = ChatStore(file)

        assertTrue(store.dropAiText())
        assertFalse(file.readText().contains("made up"))
        assertEquals(listOf(SavedTurn("q1", "a1"), SavedTurn("q2", "a2")), store.load())
        assertFalse(store.dropAiText())
    }

    @Test fun `chats without AI words, broken files and no file are left alone`() = runBlocking {
        val file = File(tmp.root, CHAT_FILE)
        val store = ChatStore(file)
        assertFalse(store.dropAiText())
        assertFalse(file.exists())

        // A question that only mentions "ai" is not AI text.
        val clean = """[{"question":"is \"ai\": here?","answer":"a"}]"""
        file.writeText(clean)
        assertFalse(store.dropAiText())
        assertEquals(clean, file.readText())

        file.writeText("not json, with \"ai\" inside")
        assertFalse(store.dropAiText())
        assertEquals("not json, with \"ai\" inside", file.readText())
    }
}
