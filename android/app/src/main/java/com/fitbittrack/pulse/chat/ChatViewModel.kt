package com.fitbittrack.pulse.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

sealed interface ChatItem {
    data class Question(val text: String) : ChatItem
    data class Reply(val answer: Answer, val ai: String = "", val writing: Boolean = false) : ChatItem
    data class Notice(val kind: Kind, val title: String, val text: String) : ChatItem {
        enum class Kind { Emergency, Plain }
    }
    /** From an earlier chat. */
    data class Old(val answer: String, val ai: String) : ChatItem
}

data class ChatPrefs(val keep: Boolean = false, val areas: Set<Area> = Area.entries.toSet(), val wifiOnly: Boolean = true)

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    /** The AI is being loaded into memory. */
    val loading: Boolean = false,
    /** Set when the AI could not be loaded. Pulse's own answers still work. */
    val aiProblem: String? = null,
)

/**
 * The chat. Pulse finds the numbers and writes the direct answer at once; then, if the AI is on this phone,
 * it adds an explanation in words. If the AI is missing, fails or writes something not allowed, the direct
 * answer still stands.
 */
class ChatViewModel(
    private val data: suspend (LocalDate) -> ChatData,
    private val prefs: suspend () -> ChatPrefs,
    private val store: ChatStore,
    private val model: LocalModel,
    private val modelFile: () -> File?,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var job: Job? = null
    private var unload: Job? = null
    private var started = false
    private val turns = mutableListOf<SavedTurn>()

    /** The chat came on screen. */
    fun open() {
        unload?.cancel()
        if (!started) {
            started = true
            viewModelScope.launch {
                if (prefs().keep && _state.value.items.isEmpty()) {
                    turns += store.load()
                    _state.update { s -> if (s.items.isEmpty()) s.copy(items = turns.flatMap { listOf(ChatItem.Question(it.question), ChatItem.Old(it.answer, it.ai)) }) else s }
                }
            }
        }
        viewModelScope.launch { ready() }
    }

    /** The chat left the screen. The AI uses about 800 MB, so it isn't kept for long. */
    fun leave() {
        unload?.cancel()
        unload = viewModelScope.launch {
            delay(KEEP_LOADED_MS)
            model.close()
        }
    }

    /** True if the AI can be used now. */
    private suspend fun ready(): Boolean {
        val file = modelFile() ?: return false
        _state.update { it.copy(loading = true) }
        return try {
            model.load(file)
            _state.update { it.copy(aiProblem = null) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) { // also native "library not found" and out-of-memory errors
            _state.update { it.copy(aiProblem = "The AI could not start. Pulse's own answers still work.") }
            false
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    fun ask(text: String) {
        val q = text.trim().take(MAX_QUESTION)
        if (q.isEmpty() || _state.value.busy) return
        unload?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, items = it.items + ChatItem.Question(q)) }
            try {
                reply(q)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                add(ChatItem.Notice(ChatItem.Notice.Kind.Plain, "That didn't work", "Pulse could not read your data just now. Please try again."))
            } finally {
                _state.update { s -> s.copy(busy = false, items = s.items.map { if (it is ChatItem.Reply && it.writing) it.copy(writing = false) else it }) }
            }
        }
    }

    private suspend fun reply(q: String) {
        val day = today()
        val p = prefs()
        when (val route = QuestionRouter.route(q, day)) {
            is Route.Emergency -> notice(
                q, ChatItem.Notice.Kind.Emergency,
                if (route.crisis) "You don't have to be alone with this" else "Please get medical help",
                if (route.crisis) "Please call your local emergency number or a crisis line now, or go to someone you trust. Pulse can't help with this, but people can."
                else "This can be serious. Call your local emergency number or see a doctor now. Pulse and your band can't tell you if you are OK.",
            )
            Route.Medical -> notice(
                q, ChatItem.Notice.Kind.Plain, "That's one for a doctor",
                "I can't help with illness, pain, symptoms or medicine. Please ask a doctor. I can show you your sleep, heart and activity numbers to talk about with them.",
            )
            Route.OffTopic -> notice(
                q, ChatItem.Notice.Kind.Plain, "I can help with your own data",
                "I only know the health and fitness data in Pulse. Try asking about sleep, steps, calories, distance, heart rate or workouts.",
            )
            is Route.Data ->
                if (route.topic.area !in p.areas) notice(
                    q, ChatItem.Notice.Kind.Plain, "Switched off",
                    "Chat is not allowed to read this data: ${route.topic.area.label}. You can change that in You, then Ask Pulse.",
                ) else answer(q, AnswerBuilder.build(route, limit(data(day), p.areas)), p)
        }
    }

    /** "Your week" touches several areas, so anything switched off is removed before it is read. */
    private fun limit(d: ChatData, areas: Set<Area>): ChatData = d.copy(
        nights = if (Area.Sleep in areas) d.nights else emptyList(),
        workouts = if (Area.Activity in areas) d.workouts else emptyList(),
        days = d.days.map { s ->
            var x = s
            if (Area.Activity !in areas) x = x.copy(steps = null, distanceMeters = null, floors = null, caloriesKcal = null, activeZoneMinutes = null, activeMinutes = null, vo2Max = null)
            if (Area.Heart !in areas) x = x.copy(restingHeartRate = null, hrvMs = null)
            if (Area.Body !in areas) x = x.copy(spo2Percent = null, respiratoryRate = null, skinTempDeltaC = null)
            x
        },
    )

    private suspend fun answer(q: String, a: Answer, p: ChatPrefs) {
        add(ChatItem.Reply(a, writing = a.hasData && modelFile() != null))
        val index = _state.value.items.lastIndex
        var ai = ""
        try {
            if (a.hasData && ready()) {
                val guard = AnswerGuard.Stream()
                try {
                    model.answer(Prompt.SYSTEM, Prompt.user(q, a)).takeWhile { piece ->
                        guard.add(piece)
                        show(index, guard.text)
                        !guard.full
                    }.collect { }
                    guard.finish()
                } catch (e: CancellationException) {
                    ai = guard.text
                    throw e
                } catch (_: Exception) {
                    // The direct answer stands on its own.
                }
                ai = guard.text
                show(index, ai)
            }
        } finally {
            remember(SavedTurn(q, a.direct, ai), p)
        }
    }

    private fun show(index: Int, text: String) = _state.update { s ->
        s.copy(items = s.items.mapIndexed { i, it -> if (i == index && it is ChatItem.Reply) it.copy(ai = text) else it })
    }

    private suspend fun notice(q: String, kind: ChatItem.Notice.Kind, title: String, text: String) {
        add(ChatItem.Notice(kind, title, text))
        // A worry like this is never written to storage.
        if (kind != ChatItem.Notice.Kind.Emergency) remember(SavedTurn(q, "$title. $text"), prefs())
    }

    private fun add(item: ChatItem) = _state.update { it.copy(items = it.items + item) }

    private fun remember(turn: SavedTurn, p: ChatPrefs) {
        if (!p.keep) return
        turns += turn
        val copy = turns.toList()
        viewModelScope.launch { runCatching { store.save(copy) } }
    }

    fun stop() { job?.cancel() }

    /** Starts over on screen. Kept chats stay until they are deleted. */
    fun newChat() {
        job?.cancel()
        _state.update { it.copy(items = emptyList(), busy = false) }
    }

    /** Settings: "Delete all chats", and sign-out. */
    fun deleteChats() {
        newChat()
        turns.clear()
        viewModelScope.launch { store.clear() }
    }

    /** The AI file is about to be deleted. */
    fun releaseModel(then: () -> Unit) = viewModelScope.launch {
        job?.cancel()
        model.close()
        then()
    }

    private companion object {
        const val KEEP_LOADED_MS = 60_000L
        const val MAX_QUESTION = 300
    }
}
