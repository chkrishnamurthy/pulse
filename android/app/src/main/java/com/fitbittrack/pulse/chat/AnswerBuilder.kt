package com.fitbittrack.pulse.chat

import com.fitbittrack.pulse.domain.DatedGoals
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.domain.SleepStageType
import com.fitbittrack.pulse.domain.Workout
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.Tone
import com.fitbittrack.pulse.ui.model.Units
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything an answer may be built from: the last [HISTORY_DAYS] days, from the phone's own database. */
data class ChatData(
    val today: LocalDate,
    val days: List<DaySummary>,
    val nights: List<SleepSession>,
    val workouts: List<Workout>,
    val goals: List<DatedGoals>,
    val units: Units,
    val locale: Locale = Locale.getDefault(),
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    companion object { const val HISTORY_DAYS = 400L }
}

data class Bar(val label: String, val value: Double?, val reached: Boolean)

/** One answer. Every number in it was worked out here, never by the AI. */
data class Answer(
    val topic: Topic,
    val tone: Tone,
    val title: String,
    val fact: String?,
    val big: String,
    val unit: String?,
    val bars: List<Bar>,
    val barNote: String?,
    /** The direct answer, in full sentences. */
    val direct: String,
    /** "What was used". */
    val used: List<Pair<String, String>>,
    /** Tips the AI may choose from. */
    val ideas: List<String>,
    val followUps: List<String>,
    val hasData: Boolean,
    /** Bars start at zero (amounts) or near the lowest value (measurements). */
    val fromZero: Boolean = true,
)

/** Looks up and calculates. Plain code, tested. */
object AnswerBuilder {

    /** One number over time. */
    private class Series(
        val kind: MetricKind?,
        val tone: Tone,
        val name: String,
        val values: Map<LocalDate, Double>,
        val text: (Double) -> String,
        val goal: ((LocalDate) -> Double)?,
        /** Adds up over a day, so an unfinished today is left out of averages. */
        val summable: Boolean,
        val per: String,
        val used: String,
    )

    fun build(route: Route.Data, d: ChatData): Answer = when (route.topic) {
        Topic.Workouts -> workouts(route, d)
        Topic.Overview -> overview(d)
        else -> metric(route, d, series(route.topic, d))
    }

    // ---------------- series ----------------

    private fun wakeDate(s: SleepSession, zone: ZoneId) = s.wakeDate ?: s.end.atZone(zone).toLocalDate()

    private fun nights(d: ChatData): Map<LocalDate, SleepSession> =
        d.nights.filter { it.isMainSleep }.groupBy { wakeDate(it, d.zone) }.mapValues { (_, l) -> l.maxBy { it.minutesAsleep } }

    private fun goalOf(d: ChatData, m: GoalMetric): (LocalDate) -> Double =
        { date -> GoalCalendar.day(date, null, null, GoalCalendar.goalsOn(d.goals, date))[m].target }

    private fun series(topic: Topic, d: ChatData): Series {
        fun of(kind: MetricKind, per: String = "a day"): Series = Series(
            kind, kind.tone, kind.label,
            d.days.mapNotNull { s -> kind.read(s)?.let { s.date to it } }.toMap(),
            { kind.text(it, d.units) }, kind.goal?.let { goalOf(d, it) }, kind.summable, per, kind.label,
        )
        return when (topic) {
            Topic.Sleep -> Series(
                null, Tone.Sleep, "Sleep", nights(d).mapValues { it.value.minutesAsleep.toDouble() },
                { Fmt.minutes(it.roundToInt()) }, goalOf(d, GoalMetric.Sleep), false, "a night", "Sleep length",
            )
            Topic.DeepSleep -> Series(
                null, Tone.Sleep, "Deep sleep",
                nights(d).filterValues { it.stages.isNotEmpty() }.mapValues { it.value.minutesIn(SleepStageType.DEEP).toDouble() },
                { Fmt.minutes(it.roundToInt()) }, null, false, "a night", "Deep sleep",
            )
            Topic.Steps -> of(MetricKind.Steps)
            Topic.Calories -> of(MetricKind.Calories)
            Topic.Distance -> of(MetricKind.Distance)
            Topic.Floors -> of(MetricKind.Floors)
            Topic.Zone -> of(MetricKind.Zone)
            Topic.Fitness -> of(MetricKind.Vo2Max)
            Topic.RestingHeart -> of(MetricKind.RestingHeart)
            Topic.Hrv -> of(MetricKind.Hrv, "a night")
            Topic.Spo2 -> of(MetricKind.Spo2, "a night")
            Topic.Breathing -> of(MetricKind.Breathing, "a night")
            Topic.SkinTemp -> of(MetricKind.SkinTemp, "a night")
            Topic.Workouts, Topic.Overview -> error("not a single series")
        }
    }

    // ---------------- one metric ----------------

    private fun metric(r: Route.Data, d: ChatData, s: Series): Answer {
        val p = r.period
        val inPeriod = (0 until p.days).map { p.from.plusDays(it) }.mapNotNull { date -> s.values[date]?.let { date to it } }
        if (inPeriod.isEmpty()) return noData(r, s, d)

        val comparing = r.want == Want.Compare
        val lastFull = if (s.summable) d.today.minusDays(1) else d.today
        val thisWeek = (0L..6L).mapNotNull { s.values[lastFull.minusDays(it)] }
        val finished = if (s.summable) inPeriod.filter { it.first != d.today }.ifEmpty { inPeriod } else inPeriod
        val avg = if (comparing && thisWeek.isNotEmpty()) thisWeek.average() else finished.map { it.second }.average()
        val direct = when {
            comparing -> compare(s, d)
            p.kind == Period.Kind.Day -> oneDay(s, d, p.from, inPeriod.first().second, r.topic)
            r.want == Want.Best -> extreme(s, d, finished.maxBy { it.second }, "highest", "longest", p)
            r.want == Want.Worst -> extreme(s, d, finished.minBy { it.second }, "lowest", "shortest", p)
            r.want == Want.Goal && s.goal != null -> goalCount(s, inPeriod, p)
            else -> average(s, d, avg, inPeriod, p)
        }
        val day = p.kind == Period.Kind.Day && !comparing
        // A single day is shown with the six days before it, so it has something to compare with.
        val barDays = when {
            comparing -> (6L downTo 0L).map { lastFull.minusDays(it) }
            day -> (6L downTo 0L).map { p.from.minusDays(it) }
            else -> (0 until p.days).map { p.from.plusDays(it) }
        }
        val bars = barDays.map { date ->
            val v = s.values[date]
            Bar(label(date, barDays.size, d.locale), v, v != null && (s.goal == null || v >= s.goal.invoke(date)))
        }
        val reached = barDays.count { date -> val v = s.values[date]; v != null && s.goal != null && v >= s.goal.invoke(date) }
        val withData = barDays.count { s.values[it] != null }
        val shown = if (day) inPeriod.first().second else avg
        val (big, unit) = split(s, d, shown)
        val span = if (s.per == "a night") "nights" else if (comparing && s.summable) "full days" else "days"
        return Answer(
            topic = r.topic, tone = s.tone,
            title = if (day) "${s.name}, ${dayName(p.from, d, r.topic)}" else "${s.name}, last ${barDays.size} $span",
            fact = s.goal?.let { "Goal ${s.text(it(p.to))}" } ?: normal(s, d)?.let { "Your normal ${s.text(it)}" },
            big = big, unit = if (day) unit else listOfNotNull(unit, "average").joinToString(" "),
            bars = bars,
            barNote = if (s.goal != null) "Bright bars reached your goal: $reached of $withData." else "Compared with your own last 30 days.",
            direct = direct,
            used = listOfNotNull(
                s.used to "$withData ${if (s.per == "a night") "nights" else "days"}",
                s.goal?.let { "Your goal" to s.text(it(p.to)) },
                if (s.goal == null) normal(s, d)?.let { "Your 30-day normal" to s.text(it) } else null,
            ),
            ideas = if (doingWell(s, d, if (day) p.from else d.today, shown)) Ideas.keep(r.topic) else Ideas.forTopic(r.topic),
            followUps = Suggestions.after(r.topic), hasData = true,
            fromZero = s.goal != null || s.summable,
        )
    }

    private fun split(s: Series, d: ChatData, v: Double): Pair<String, String?> = s.kind?.format(v, d.units) ?: (s.text(v) to null)

    /** The difference of two numbers as they are shown, so "11,373 and 8,247" never differ by 3,127. */
    private fun diff(s: Series, a: Double, b: Double): String {
        val x = Math.rint(a); val y = Math.rint(b)
        val wholeNumbers = s.text(x) == s.text(a) && s.text(y) == s.text(b)
        return s.text(abs(if (wholeNumbers) x - y else a - b))
    }

    /** Goal reached, or a heart value on the usual good side: then "keep going" fits better than "do more" or "rest". */
    private fun doingWell(s: Series, d: ChatData, date: LocalDate, v: Double): Boolean {
        s.goal?.let { return v >= it(date) }
        val past = (1L..30L).mapNotNull { s.values[date.minusDays(it)] }
        if (past.size < 7) return false
        val mean = past.average()
        return when (s.kind) {
            MetricKind.RestingHeart -> v.roundToInt() <= mean.roundToInt()
            MetricKind.Hrv -> v >= mean - sqrt(past.sumOf { (it - mean) * (it - mean) } / past.size)
            else -> false
        }
    }

    private fun oneDay(s: Series, d: ChatData, date: LocalDate, v: Double, topic: Topic): String {
        val isToday = date == d.today
        val goal = s.goal?.invoke(date)
        val night = s.per == "a night"
        val start = when {
            isToday && night -> "Last night: ${s.text(v)}."
            isToday -> if (s.summable) "Today so far: ${s.text(v)}." else "Today: ${s.text(v)}."
            else -> "${dayName(date, d, topic).replaceFirstChar { it.uppercase() }}: ${s.text(v)}."
        }
        val rest = when {
            goal != null && v >= goal -> " You reached your goal of ${s.text(goal)}."
            goal != null && isToday && s.summable -> " ${diff(s, goal, v)} to go. You are at ${(v * 100 / goal).toInt()}% of your goal of ${s.text(goal)}."
            goal != null -> " That is ${diff(s, goal, v)} under your goal of ${s.text(goal)}."
            else -> againstNormal(s, d, date, v)
        }
        return start + rest
    }

    /** Your own average of the 30 days before today. Needs a week of data to mean anything. */
    private fun normal(s: Series, d: ChatData): Double? =
        (1L..30L).mapNotNull { s.values[d.today.minusDays(it)] }.takeIf { it.size >= 7 }?.average()

    private fun againstNormal(s: Series, d: ChatData, date: LocalDate, v: Double): String {
        val past = (1L..30L).mapNotNull { s.values[date.minusDays(it)] }
        if (past.size < 7) return " There are not enough days yet to know your own normal."
        val mean = past.average()
        if (s.kind == MetricKind.Hrv) {
            val sd = sqrt(past.sumOf { (it - mean) * (it - mean) } / past.size)
            val lo = (mean - sd).roundToInt(); val hi = (mean + sd).roundToInt(); val x = v.roundToInt()
            return " Your own normal range is $lo to $hi ms. " + when {
                x < lo -> "This is below it."
                x > hi -> "This is above it."
                else -> "This is inside it."
            }
        }
        // Compare what is shown, so "58 and 58" is never called different.
        if (s.text(v) == s.text(mean)) return " That is the same as your own 30-day normal."
        return " Your own 30-day normal is ${s.text(mean)}, so this is ${if (v < mean) "below" else "above"} it" +
            (if (s.kind == MetricKind.RestingHeart) " by ${abs(v.roundToInt() - mean.roundToInt())} bpm." else ".")
    }

    private fun average(s: Series, d: ChatData, avg: Double, inPeriod: List<Pair<LocalDate, Double>>, p: Period): String {
        val goal = s.goal?.invoke(p.to)
        val first = "You averaged ${s.text(avg)} ${s.per} over the last ${p.days} days."
        val second = when {
            goal == null -> normal(s, d)?.let { " Your own 30-day normal is ${s.text(it)}." } ?: ""
            s.text(avg) == s.text(goal) -> " That is right at your goal."
            avg >= goal -> " That is ${diff(s, avg, goal)} above your goal of ${s.text(goal)}."
            else -> " That is ${diff(s, goal, avg)} under your goal of ${s.text(goal)}."
        }
        val third = if (s.goal == null) "" else " ${goalCount(s, inPeriod, p)}"
        return first + second + third
    }

    private fun goalCount(s: Series, inPeriod: List<Pair<LocalDate, Double>>, p: Period): String {
        val goal = s.goal ?: return ""
        val n = inPeriod.count { it.second >= goal(it.first) }
        return "You reached your ${s.name.lowercase()} goal on $n of ${inPeriod.size} ${if (s.per == "a night") "nights" else "days"}" +
            (if (inPeriod.size < p.days) " with data." else ".")
    }

    private fun extreme(s: Series, d: ChatData, at: Pair<LocalDate, Double>, word: String, sleepWord: String, p: Period): String {
        val w = if (s.tone == Tone.Sleep) sleepWord else word
        return "Your $w ${if (s.per == "a night") "night" else "day"} of the last ${p.days} was ${at.first.format(DateTimeFormatter.ofPattern("EEEE d MMMM", d.locale))}, with ${s.text(at.second)}."
    }

    /** The last seven finished days against the seven before them. */
    private fun compare(s: Series, d: ChatData): String {
        val end = if (s.summable) d.today.minusDays(1) else d.today
        val now = (0L..6L).mapNotNull { s.values[end.minusDays(it)] }
        val before = (7L..13L).mapNotNull { s.values[end.minusDays(it)] }
        if (now.isEmpty() || before.isEmpty()) return "There is not enough data for two weeks to compare."
        val a = now.average(); val b = before.average()
        val start = "You averaged ${s.text(a)} ${s.per} in the last seven days, and ${s.text(b)} in the seven before."
        return start + if (s.text(a) == s.text(b)) " That is the same." else " That is ${diff(s, a, b)} ${if (a > b) "more" else "less"}."
    }

    private fun noData(r: Route.Data, s: Series, d: ChatData): Answer {
        val where = when {
            r.period.kind != Period.Kind.Day -> "the last ${r.period.days} days"
            else -> dayName(r.period.from, d, r.topic)
        }
        return Answer(
            r.topic, s.tone, "No ${s.name.lowercase()} data", null, Fmt.NONE, null, emptyList(), null,
            "Pulse has no ${s.name.lowercase()} data saved for $where, so I won't guess.",
            emptyList(), emptyList(), Suggestions.after(r.topic), hasData = false,
        )
    }

    // ---------------- workouts and overview ----------------

    private fun workouts(r: Route.Data, d: ChatData): Answer {
        val list = d.workouts.filter { val day = it.start.atZone(d.zone).toLocalDate(); day >= r.period.from && day <= r.period.to }.sortedBy { it.start }
        if (list.isEmpty()) return Answer(
            Topic.Workouts, Tone.Active, "No workouts", null, Fmt.NONE, null, emptyList(), null,
            "Pulse has no workouts saved for ${if (r.period.kind == Period.Kind.Day) dayName(r.period.from, d, r.topic) else "the last ${r.period.days} days"}.",
            emptyList(), emptyList(), Suggestions.after(Topic.Workouts), hasData = false,
        )
        fun minutes(w: Workout) = ((w.activeSeconds ?: java.time.Duration.between(w.start, w.end).seconds) / 60).toInt()
        val total = list.sumOf { minutes(it) }
        val names = list.joinToString(", ") { "${it.name.lowercase()} ${minutes(it)} min" }
        return Answer(
            Topic.Workouts, Tone.Active, "Workouts, last ${r.period.days} days", null, "${list.size}", if (list.size == 1) "workout" else "workouts", emptyList(), null,
            "You did ${list.size} ${if (list.size == 1) "workout" else "workouts"}, ${Fmt.minutes(total)} in total: $names.",
            listOf("Workouts" to "${list.size}"), Ideas.forTopic(Topic.Workouts), Suggestions.after(Topic.Workouts), hasData = true,
        )
    }

    private fun overview(d: ChatData): Answer {
        val week = Period(Period.Kind.Week, d.today.minusDays(6), d.today)
        val parts = listOf(Topic.Sleep, Topic.Steps, Topic.Calories).mapNotNull { t ->
            val s = series(t, d)
            val vals = (0L..6L).map { d.today.minusDays(it) }.filter { !(s.summable && it == d.today) }.mapNotNull { s.values[it] }
            if (vals.isEmpty()) null else t to "${s.name}: ${s.text(vals.average())} ${s.per} on average, goal ${s.text(s.goal!!.invoke(d.today))}."
        }
        val rhr = series(Topic.RestingHeart, d)
        val heart = rhr.values[d.today]?.let { "Resting heart rate today: ${rhr.text(it)}." + againstNormal(rhr, d, d.today, it) }
        if (parts.isEmpty() && heart == null) return Answer(
            Topic.Overview, Tone.Move, "No data yet", null, Fmt.NONE, null, emptyList(), null,
            "Pulse has no data saved for the last 7 days yet.", emptyList(), emptyList(), Suggestions.start, hasData = false,
        )
        val steps = series(Topic.Steps, d)
        val days = (0 until week.days).map { week.from.plusDays(it) }
        return Answer(
            Topic.Overview, Tone.Move, "Your last 7 days", null,
            "${days.count { day -> steps.values[day]?.let { it >= steps.goal!!.invoke(day) } == true }}", "of 7 days with your step goal",
            days.map { day -> val v = steps.values[day]; Bar(label(day, 7, d.locale), v, v != null && v >= steps.goal!!.invoke(day)) },
            "Steps. Bright bars reached your goal.",
            (parts.map { it.second } + listOfNotNull(heart)).joinToString(" "),
            parts.map { it.first.label.replaceFirstChar { c -> c.uppercase() } to "7 days" } + listOfNotNull(heart?.let { "Resting heart rate" to "30 days" }),
            Ideas.forTopic(Topic.Overview), Suggestions.after(Topic.Overview), hasData = true,
        )
    }

    // ---------------- words ----------------

    private fun dayName(date: LocalDate, d: ChatData, topic: Topic): String {
        val night = topic.area == Area.Sleep || topic.area == Area.Body || topic == Topic.Hrv
        return when (date) {
            d.today -> if (night) "last night" else "today"
            d.today.minusDays(1) -> if (night) "the night before last" else "yesterday"
            else -> date.format(DateTimeFormatter.ofPattern(if (date.year == d.today.year) "EEE d MMM" else "EEE d MMM yyyy", d.locale))
        }
    }

    private fun label(date: LocalDate, n: Int, locale: Locale): String = when {
        n <= 7 -> date.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
        date.dayOfMonth % 7 == 1 -> "${date.dayOfMonth}"
        else -> ""
    }
}

/** Safe, everyday tips. The AI picks one and rewords it; without the AI the first one is shown. */
object Ideas {
    private val sleep = listOf("Start winding down earlier in the evening", "Keep the same wake-up time every day, also at weekends", "Put the phone away and dim the lights before bed", "Get daylight soon after waking")
    private val move = listOf("Take a short walk after a meal", "Split what is left into two or three small walks", "Use the stairs and stand up once an hour", "Walk while you take phone calls")
    private val heart = listOf("Take an easier day and see how tomorrow looks", "Go to bed a little earlier tonight", "Drink water through the day", "Try a few minutes of slow breathing")
    private val body = listOf("Look at the trend over several nights, not one night", "Keep the bedroom cool and the band snug", "Take an easier day if you feel like it")
    private val fit = listOf("Add an easy recovery day after hard days", "Keep a mix of walking, cycling and strength", "Plan the next session on a day you usually have time")

    private val keepSleep = listOf("Keep the same bedtime and wake-up time", "Keep your evening routine as it is")
    private val keepMove = listOf("Keep the same routine, it is working", "Keep spreading your movement through the day")
    private val keepHeart = listOf("Keep your usual sleep and movement routine", "Look at the trend over weeks, not one day")

    /** For when the goal is reached or the value is on the usual good side. */
    fun keep(t: Topic): List<String> = when (t) {
        Topic.Sleep, Topic.DeepSleep -> keepSleep
        Topic.RestingHeart, Topic.Hrv -> keepHeart
        else -> keepMove
    }

    fun forTopic(t: Topic): List<String> = when (t) {
        Topic.Sleep, Topic.DeepSleep -> sleep
        Topic.Steps, Topic.Calories, Topic.Distance, Topic.Floors -> move
        Topic.RestingHeart, Topic.Hrv -> heart
        Topic.Spo2, Topic.Breathing, Topic.SkinTemp -> body
        Topic.Zone, Topic.Workouts, Topic.Fitness -> fit
        Topic.Overview -> listOf(sleep[0], move[0], heart[0])
    }
}

object Suggestions {
    val start = listOf(
        "How did I sleep this week?",
        "How close am I to my calorie goal?",
        "Is my resting heart rate normal for me?",
        "Compare my steps with last week",
    )

    fun after(t: Topic): List<String> = when (t) {
        Topic.Sleep -> listOf("What was my longest night this month?", "Did I get enough deep sleep?")
        Topic.DeepSleep -> listOf("How did I sleep this week?", "How is my HRV?")
        Topic.Calories -> listOf("How often did I reach my calorie goal this week?", "How far must I still walk today?")
        Topic.Distance -> listOf("How close am I to my calorie goal?", "Compare my steps with last week")
        Topic.Steps -> listOf("What was my best step day this month?", "How close am I to my calorie goal?")
        Topic.RestingHeart -> listOf("How is my HRV?", "How did I sleep this week?")
        Topic.Hrv -> listOf("Is my resting heart rate normal for me?", "How did I sleep this week?")
        Topic.Workouts, Topic.Zone, Topic.Fitness -> listOf("Did I reach my zone minutes goal this week?", "How fit am I?")
        Topic.Spo2, Topic.Breathing, Topic.SkinTemp -> listOf("How did I sleep this week?", "How is my HRV?")
        Topic.Floors, Topic.Overview -> start.take(2)
    }
}
