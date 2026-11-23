package com.fitbittrack.pulse.chat

import java.time.DayOfWeek
import java.time.LocalDate

/** What the user may switch off for chat. */
enum class Area(val label: String) {
    Activity("Activity and goals"),
    Sleep("Sleep"),
    Heart("Heart"),
    Body("Body (oxygen, breathing, skin temperature)"),
}

enum class Topic(val area: Area, val label: String) {
    Sleep(Area.Sleep, "sleep"),
    DeepSleep(Area.Sleep, "deep sleep"),
    Steps(Area.Activity, "steps"),
    Calories(Area.Activity, "calories"),
    Distance(Area.Activity, "distance"),
    Floors(Area.Activity, "floors"),
    Zone(Area.Activity, "zone minutes"),
    Workouts(Area.Activity, "workouts"),
    Fitness(Area.Activity, "cardio fitness"),
    RestingHeart(Area.Heart, "resting heart rate"),
    Hrv(Area.Heart, "heart rate variability"),
    Spo2(Area.Body, "blood oxygen"),
    Breathing(Area.Body, "breathing rate"),
    SkinTemp(Area.Body, "skin temperature"),
    Overview(Area.Activity, "week"),
}

enum class Want { Summary, Left, Goal, Best, Worst, Compare }

/** Which days a question is about. [from]..[to], both included. */
data class Period(val kind: Kind, val from: LocalDate, val to: LocalDate) {
    enum class Kind { Day, Week, Month }
    val days: Long get() = to.toEpochDay() - from.toEpochDay() + 1
}

sealed interface Route {
    /** May be an emergency: a fixed message. [crisis] = the person may be in danger from themselves. */
    data class Emergency(val crisis: Boolean) : Route
    /** Illness, medicine, symptoms: not for an app. */
    data object Medical : Route
    data object OffTopic : Route
    data class Data(val topic: Topic, val period: Period, val want: Want) : Route
}

/** Turns a typed question into what to look up. Plain rules: the same question always gives the same result. */
object QuestionRouter {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val crisis = rx("suicid|kill (my ?self|me)|self[ -]?harm|hurt myself|end my life|want to die|don'?t want to live|khudkushi|marna chahta|marna chahti")
    private val emergency = rx("chest (pain|tight|pressure)|pain in (my )?chest|heart attack|can'?t breath|cannot breath|can not breath|(struggling|hard|trouble|difficult\\w*) (to breath|breath)|short(ness)? of breath|breathless|faint(ed|ing)|passed out|pass out|unconscious|collaps|stroke|seizure|overdos|bleeding|numb (arm|face)|seene me(i?n)? dard|chhati me(i?n)? dard")
    private val medical = rx("\\bpills?\\b|medicin|medicat|\\btablets?\\b|\\bdos(e|age)\\b|\\bdrugs?\\b|melatonin|antibiotic|insulin|steroid|apno?ea|insomnia|diabet|cancer|tumou?r|disease|disorder|diagnos|arrhythm|\\bafib\\b|fibrillat|hypertens|blood pressure|\\bbp\\b|cholesterol|depress|asthma|thyroid|an[ae]{1,2}mi[ac]|covid|infect|\\bfever\\b|\\bflu\\b|pregnan|symptom|\\bpains?\\b|\\bhurts?\\b|\\baches?\\b|migraine|dizz|nausea|vomit|palpitation|irregular|\\bsick\\b|\\bill\\b|illness|\\bdoctor\\b|\\bcure\\b|treatment|\\bdard\\b|dawai|bimar")

    // First match wins, so the more exact topics come first.
    private val topics = listOf(
        Topic.Hrv to rx("\\bhrv\\b|variability"),
        Topic.Spo2 to rx("oxygen|spo ?2|\\bo2\\b"),
        Topic.Breathing to rx("breath|respirat|\\bsaans\\b"),
        Topic.SkinTemp to rx("temperature|\\btemp\\b|skin"),
        Topic.Fitness to rx("vo ?2|cardio fit|how fit|fitness (level|score)|cardio fitness"),
        Topic.Workouts to rx("workout|training|\\bgym\\b|\\bruns?\\b|\\brunning\\b|cycl|\\bbike"),
        Topic.DeepSleep to rx("deep sleep|\\bdeep\\b"),
        Topic.Sleep to rx("sleep|slept|asleep|\\bnights?\\b|bedtime|\\bbed\\b|\\brem\\b|\\bnaps?\\b|wake|woke|\\bneend\\b|\\bnind\\b|soya|soyi"),
        Topic.Calories to rx("calor|kcal|\\bburn|energy (used|spent)"),
        Topic.Distance to rx("distance|kilomet|\\bkms?\\b|\\bmiles?\\b|how far|\\bfar\\b"),
        Topic.Floors to rx("floors?\\b|stairs|climb"),
        Topic.Zone to rx("zone|exercis|active minutes|\\bactive\\b|intens"),
        Topic.Steps to rx("\\bsteps?\\b|walk|kadam|\\bchala\\b|\\bchali\\b|\\blazy\\b"),
        Topic.RestingHeart to rx("heart|pulse|\\bbpm\\b|\\bdil\\b"),
        Topic.Overview to rx("tired|energy|how am i|how('?s| is) my (week|day|health|progress)|progress|summary|summari|overview|my data|\\bgoals?\\b|this week|last week|\\bweek\\b|\\bhealth\\b|\\bfitness\\b|recover|\\bthak"),
    )

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val dayMonth = rx("\\b(\\d{1,2})(?:st|nd|rd|th)?(?: of)? (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(?:,? (20\\d\\d))?\\b")
    private val monthDay = rx("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* (\\d{1,2})(?:st|nd|rd|th)?(?:,? (20\\d\\d))?\\b")
    private val weekday = rx("\\b(?:on |last )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")

    fun route(question: String, today: LocalDate): Route {
        val q = question.trim()
        if (crisis.containsMatchIn(q)) return Route.Emergency(crisis = true)
        if (emergency.containsMatchIn(q)) return Route.Emergency(crisis = false)
        if (medical.containsMatchIn(q)) return Route.Medical
        val topic = topics.firstOrNull { it.second.containsMatchIn(q) }?.first ?: return Route.OffTopic
        val want = want(q)
        return Route.Data(topic, period(q, today, topic, want), want)
    }

    private fun want(q: String): Want = when {
        rx("compare|\\bvs\\b|versus|than (last|the) |last week|week before|up or down|trend|chang|improv|better than").containsMatchIn(q) -> Want.Compare
        rx("\\bleft\\b|\\bmore\\b|\\bstill\\b|to go|remain|how close").containsMatchIn(q) -> Want.Left
        rx("\\bbest\\b|\\bmost\\b|longest|highest|\\bmax").containsMatchIn(q) -> Want.Best
        rx("worst|least|shortest|lowest|\\bmin\\b").containsMatchIn(q) -> Want.Worst
        rx("\\bgoals?\\b|target|reach|achiev|how often").containsMatchIn(q) -> Want.Goal
        else -> Want.Summary
    }

    private fun period(q: String, today: LocalDate, topic: Topic, want: Want): Period {
        fun day(d: LocalDate) = Period(Period.Kind.Day, d, d)
        fun back(n: Long, kind: Period.Kind) = Period(kind, today.minusDays(n - 1), today)
        explicitDate(q, today)?.let { return day(it) }
        return when {
            rx("yesterday|\\bkal\\b").containsMatchIn(q) -> day(today.minusDays(1))
            rx("last night|\\btoday\\b|tonight|this morning|right now|\\baaj\\b").containsMatchIn(q) -> day(today)
            rx("month|30 days|four weeks").containsMatchIn(q) -> back(30, Period.Kind.Month)
            rx("week|7 days|seven days|lately|recently|hafte").containsMatchIn(q) -> back(7, Period.Kind.Week)
            want == Want.Left -> day(today)
            want == Want.Best || want == Want.Worst -> back(30, Period.Kind.Month)
            want == Want.Compare || want == Want.Goal -> back(7, Period.Kind.Week)
            topic == Topic.RestingHeart || topic.area == Area.Body || topic == Topic.Hrv || topic == Topic.Fitness || topic == Topic.DeepSleep -> day(today)
            rx("\\baverage|\\bavg\\b|usual").containsMatchIn(q) -> back(7, Period.Kind.Week)
            topic == Topic.Workouts || topic == Topic.Zone || topic == Topic.Overview -> back(7, Period.Kind.Week)
            topic == Topic.Sleep -> back(7, Period.Kind.Week)
            else -> day(today)
        }
    }

    /** "2 January", "Jan 2", "Friday": always the latest such day that is not in the future. */
    private fun explicitDate(q: String, today: LocalDate): LocalDate? {
        fun make(dayText: String, monthText: String, yearText: String): LocalDate? {
            val m = months.indexOf(monthText.lowercase().take(3)) + 1
            val d = dayText.toIntOrNull() ?: return null
            yearText.toIntOrNull()?.let { y -> return runCatching { LocalDate.of(y, m, d) }.getOrNull() }
            val thisYear = runCatching { LocalDate.of(today.year, m, d) }.getOrNull()
            val date = thisYear?.takeIf { it <= today } ?: runCatching { LocalDate.of(today.year - 1, m, d) }.getOrNull()
            return date
        }
        dayMonth.find(q)?.let { return make(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        monthDay.find(q)?.let { return make(it.groupValues[2], it.groupValues[1], it.groupValues[3]) }
        weekday.find(q)?.let { m ->
            val want = DayOfWeek.valueOf(m.groupValues[1].uppercase())
            return (0L..6L).map { today.minusDays(it) }.first { it.dayOfWeek == want }
        }
        return null
    }
}
