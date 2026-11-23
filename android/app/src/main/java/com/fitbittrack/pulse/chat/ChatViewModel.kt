package com.fitbittrack.pulse.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface ChatItem {
    data class Question(val text: String) : ChatItem
    data class Reply(val answer: Answer) : ChatItem
    data class Notice(val kind: Kind, val title: String, val text: String) : ChatItem {
        enum class Kind { Emergency, Plain }
    }
    /** From an earlier chat. */
    data class Old(val answer: String) : ChatItem
}

data class ChatPrefs(val keep: Boolean = false, val areas: Set<Area> = Area.entries.toSet())

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
)

/** The chat: a question is sorted by plain rules and answered from the phone's own database. Nothing leaves the phone. */
class ChatViewModel(
    private val data: suspend (LocalDate) -> ChatData,
    private val prefs: suspend () -> ChatPrefs,
    private val store: ChatStore,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var job: Job? = null
    private var started = false
    private val turns = mutableListOf<SavedTurn>()

    /** The chat came on screen. */
    fun open() {
        if (!started) {
            started = true
            viewModelScope.launch {
                if (prefs().keep && _state.value.items.isEmpty()) {
                    turns += store.load()
                    _state.update { s -> if (s.items.isEmpty()) s.copy(items = turns.flatMap { listOf(ChatItem.Question(it.question), ChatItem.Old(it.answer)) }) else s }
                }
            }
        }
    }

    fun ask(text: String) {
        val q = text.trim().take(MAX_QUESTION)
        if (q.isEmpty() || _state.value.busy) return
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, items = it.items + ChatItem.Question(q)) }
            try {
                reply(q)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                add(ChatItem.Notice(ChatItem.Notice.Kind.Plain, "That didn't work", "Pulse could not read your data just now. Please try again."))
            } finally {
                _state.update { it.copy(busy = false) }
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

    private fun answer(q: String, a: Answer, p: ChatPrefs) {
        add(ChatItem.Reply(a))
        remember(SavedTurn(q, a.direct), p)
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

    private companion object {
        const val MAX_QUESTION = 300
    }
}
