package com.fitbittrack.pulse.chat

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import java.io.File

/**
 * Removes what the old Ask Pulse AI (Google's Gemma, tried in Nov 2026 and taken out) may have left on a phone:
 * its 2.6 GB file or half-finished download, its download job, its "Wi-Fi only" setting and its words inside saved chats.
 *
 * Runs at every app start and does nothing when nothing is left. Health data, training data, settings in use and
 * the questions and answers of saved chats are never touched.
 */
object OldAiCleanup {
    /** The folder, inside the app's no-backup folder, that held the AI file. Nothing else in Pulse uses it. */
    const val MODELS_DIR = "models"
    private const val DOWNLOAD_WORK = "pulse-ai-model"
    private const val TAG = "OldAiCleanup"

    /** Deletes the AI folder in [noBackupDir]. True if there was one and it is gone now. */
    fun deleteFiles(noBackupDir: File): Boolean {
        val dir = File(noBackupDir, MODELS_DIR)
        return dir.exists() && dir.deleteRecursively()
    }

    suspend fun run(context: Context) {
        val app = context.applicationContext
        val dir = app.noBackupFilesDir
        // Stop a download first, so nothing writes into the folder while it is deleted.
        WorkManager.getInstance(app).cancelUniqueWork(DOWNLOAD_WORK)
        if (deleteFiles(dir)) Log.i(TAG, "Deleted the old AI files")
        UiPrefsStore(app).forgetOldAiSetting()
        if (ChatStore(File(dir, CHAT_FILE)).dropAiText()) Log.i(TAG, "Removed old AI words from saved chats")
    }
}
