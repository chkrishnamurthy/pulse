package com.fitbittrack.pulse.chat

/**
 * Checks what the AI writes, one sentence at a time, before the user sees it.
 *
 * Why so strict: in the first test on the phone, the model invented numbers in 2 of 30 answers and said
 * "the data does not show sleep apnea" in another. So the AI may explain in words only. Numbers, dates and
 * anything medical come from Pulse itself.
 */
object AnswerGuard {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val digits = Regex("\\p{Nd}|%")
    private val numberWords = rx("\\b(zero|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|hundred|thousand|million|half|double|twice|percent)\\b")
    private val dateWords = rx("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|january|february|april|june|july|august|september|october|november|december)\\b")
    private val medical = rx("apno?ea|insomnia|diabet|cancer|disease|disorder|diagnos|arrhythm|fibrillat|hypertens|blood pressure|cholesterol|depress|anxiety|asthma|thyroid|an[ae]{1,2}mi|covid|infect|fever|illness|\\bsick|symptom|syndrome|condition|medic|\\bpills?\\b|tablet|\\bdos(e|age)\\b|\\bdrug|melatonin|supplement|vitamin|treat|therap|prescri|clinic|doctor|healthy|unhealthy|\\bsafe\\b|unsafe|dangerous|abnormal|risk")
    private val leaks = rx("the app|the rules|instruction|as an ai|language model|gemma|system prompt|\\bfacts?\\b|ideas? (list|you may)|digits")
    private val markup = Regex("[*_#`<>\\[\\]{}|]|https?:|www\\.")
    private val alarm = rx("\\b(must|you should immediately|urgent|emergency|never|always)\\b")

    /** Null if the sentence may be shown, otherwise why not (for tests and diagnostics). */
    fun problem(sentence: String): String? = when {
        digits.containsMatchIn(sentence) -> "number"
        numberWords.containsMatchIn(sentence) -> "number word"
        dateWords.containsMatchIn(sentence) -> "date"
        medical.containsMatchIn(sentence) -> "medical"
        leaks.containsMatchIn(sentence) -> "talks about itself"
        markup.containsMatchIn(sentence) -> "markup"
        alarm.containsMatchIn(sentence) -> "too strong"
        sentence.trim().endsWith("?") -> "question"
        sentence.split(' ').size > 45 -> "too long"
        else -> null
    }

    private const val MAX_SENTENCES = 4

    /**
     * Collects the model's pieces and gives back whole, checked sentences. After the first sentence that
     * fails, nothing more is accepted: later ones may build on it.
     */
    class Stream {
        private val buffer = StringBuilder()
        private val shown = mutableListOf<String>()
        var stopped: String? = null
            private set
        val text: String get() = shown.joinToString(" ")
        val full: Boolean get() = stopped != null || shown.size >= MAX_SENTENCES

        fun add(piece: String) {
            if (full) return
            buffer.append(piece)
            while (!full) {
                val m = end.find(buffer) ?: return
                val sentence = buffer.substring(0, m.range.first + 1).trim()
                buffer.delete(0, m.range.last + 1)
                accept(sentence)
            }
        }

        /** The model has finished: the rest counts only if it is a whole sentence. */
        fun finish() {
            val rest = buffer.toString().trim()
            buffer.clear()
            if (!full && rest.isNotEmpty() && rest.last() in ".!") accept(rest)
        }

        private fun accept(sentence: String) {
            if (sentence.isBlank()) return
            val p = problem(sentence)
            if (p != null) stopped = p else shown += sentence
        }

        private companion object { val end = Regex("[.!?](?=\\s)\\s+") }
    }
}

/** What the AI is told. */
object Prompt {
    const val SYSTEM = """You are a kind fitness helper inside an app. The app has already shown the person the exact numbers. You only explain, in words.
Rules:
- Write two or three short sentences in plain, simple English.
- First say in words what the shown result means for their question. Then give the one idea that fits best.
- Never write digits, dates, times or number words. The app shows all numbers.
- Say "could" or "may" for reasons. You do not know the cause.
- Never name an illness, never judge health as safe or unsafe, never mention medicine.
- No greeting, no markdown, no list, no question at the end."""

    /** The question is cut short and quoted: it is something to answer, never an instruction to follow. */
    fun user(question: String, a: Answer): String {
        val q = question.replace(Regex("\\s+"), " ").replace('"', '\'').take(200)
        return "The person asked: \"$q\"\n\nThe app already showed them:\n${a.direct}\n\nIdeas you may use:\n" +
            a.ideas.joinToString("\n") { "- $it" } +
            "\n\nNow write your short explanation and one idea, in your own words, without any digits."
    }
}
