package com.fitbittrack.pulse.chat

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A text model that runs on this phone. Everything is stateless: one question, one answer. */
interface LocalModel {
    /** Loads the model if it isn't loaded. Throws if it can't. Takes 5 to 11 seconds on a Galaxy S24 Ultra. */
    suspend fun load(file: File)
    /** Pieces of the answer as they are written. Cancelling the collector stops the model. */
    fun answer(system: String, user: String): Flow<String>
    suspend fun close()
}

/** Gemma through Google's LiteRT-LM. GPU first; CPU if the GPU can't be used. */
class LiteRtModel(private val cacheDir: File) : LocalModel {
    private val lock = Mutex()
    private var engine: Engine? = null

    override suspend fun load(file: File) = withContext(Dispatchers.Default) {
        lock.withLock {
            if (engine != null) return@withLock
            engine = try {
                start(file, Backend.GPU())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "GPU not usable, trying CPU", e)
                start(file, Backend.CPU())
            }
        }
    }

    private fun start(file: File, backend: Backend): Engine {
        val e = Engine(EngineConfig(modelPath = file.path, backend = backend, maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path))
        try {
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw t
        }
        return e
    }

    override fun answer(system: String, user: String): Flow<String> = flow {
        // One answer at a time; close() waits for it.
        lock.withLock {
            val e = engine ?: error("The AI is not loaded.")
            val c = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                )
            )
            try {
                c.sendMessageAsync(user).collect { emit(it.toString()) }
            } finally {
                runCatching { c.cancelProcess() }
                runCatching { c.close() }
            }
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun close() = withContext(Dispatchers.Default) {
        lock.withLock {
            runCatching { engine?.close() }
            engine = null
        }
    }

    private companion object {
        const val TAG = "PulseChat"
        const val MAX_TOKENS = 2048
    }
}

/** Can this phone run the AI? */
object DeviceCheck {
    private const val MIN_MEMORY = 5_500_000_000L // phones sold as 6 GB report a little less

    fun problem(context: Context): String? {
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return when {
            Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" || it == "x86_64" } -> "This phone's processor can't run the AI."
            mem.totalMem < MIN_MEMORY -> "This phone needs at least 6 GB of memory for the AI."
            else -> null
        }
    }
}
