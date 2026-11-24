package com.fitbittrack.pulse.ui.widget.art

import android.content.Context
import com.fitbittrack.pulse.domain.DaySummary
import com.fitbittrack.pulse.domain.HealthRepository
import com.fitbittrack.pulse.domain.SleepSession
import com.fitbittrack.pulse.ui.DataLayerBridge
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.DayGoals
import com.fitbittrack.pulse.ui.goals.GoalCalendar
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.HeartPoint
import com.fitbittrack.pulse.ui.model.Mappers
import com.fitbittrack.pulse.ui.model.Range
import com.fitbittrack.pulse.ui.model.Stage
import com.fitbittrack.pulse.ui.model.StageBlock
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.prefs.UiPrefsStore
import com.fitbittrack.pulse.ui.sample.SampleHealthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** One body signal next to the user's own usual range. [position]: 0 = well below, 1 = well above. Null = no value or range yet. */
data class Signal(val label: String, val value: String, val position: Float?, val low: Float, val high: Float) {
    /** -1 below the usual range, 0 inside, 1 above, null unknown. */
    val side: Int? get() = position?.let { if (it < low) -1 else if (it > high) 1 else 0 }
}

/** Everything any drawing shows. Every number may be missing. */
data class ArtData(
    val signedIn: Boolean,
    val now: LocalDateTime,
    val units: Units = Units.Metric,
    val goals: DayGoals,
    val hourly: List<Long?> = emptyList(),
    val yesterdayHourly: List<Long?> = emptyList(),
    val zoneWeek: Long? = null,
    val zoneWeekGoal: Int = 150,
    val restingHr: Int? = null,
    val resting7: List<Int?> = emptyList(),
    val heart: List<HeartPoint> = emptyList(),
    val hrv: Double? = null,
    val hrvRange: ClosedFloatingPointRange<Double>? = null,
    val sleepMinutes: Int? = null,
    val sleepStart: LocalTime? = null,
    val sleepEnd: LocalTime? = null,
    val blocks: List<StageBlock> = emptyList(),
    /** Seven nights, oldest first, with the date they ended. */
    val nights7: List<Pair<LocalDate, Int?>> = emptyList(),
    val signals: List<Signal> = emptyList(),
    val streak: Int = 0,
    /** The 13 days before today, oldest first: complete or not. */
    val before13: List<Boolean> = emptyList(),
    val todayComplete: Boolean = false,
    /** This month up to today: 0 = no goal reached, 1 = some, 2 = all. */
    val monthGrowth: List<Int> = emptyList(),
    val monthLength: Int = 30,
    val battery: Int? = null,
    val batteryWord: String? = null,
    val syncedAgo: String = "never",
    val bedtime: LocalTime = LocalTime.of(22, 30),
) {
    val steps: Long? get() = goals[GoalMetric.Steps].value?.toLong()
    val stepGoal: Int get() = goals[GoalMetric.Steps].target.toInt()
    val sleepGoal: Int get() = goals[GoalMetric.Sleep].target.toInt()
    /** 0..1, 0 when there is no value. */
    fun part(m: GoalMetric): Float = (goals[m].fraction ?: 0f).coerceIn(0f, 1f)
    fun percent(m: GoalMetric): String = goals[m].percent?.let { "$it%" } ?: Fmt.NONE
}

object ArtDataLoader {

    /** What the home-screen widgets show: saved data only, never online. */
    suspend fun load(context: Context): ArtData = try {
        withTimeout(6_000) {
            val repo = DataLayerBridge.savedRepository(context)
            if (repo.syncStatus.first().lastSuccess == null) signedOut() else read(context, repo, LocalDateTime.now())
        }
    } catch (_: TimeoutCancellationException) {
        signedOut()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        signedOut()
    }

    /** What the gallery inside the app shows: the user's data, else sample data. */
    suspend fun preview(context: Context): ArtData = load(context).takeIf { it.signedIn } ?: sample(context)

    suspend fun sample(context: Context): ArtData = read(context, SampleHealthRepository(), LocalDate.now().atTime(LocalTime.of(15, 40))).copy(battery = 72)

    private fun signedOut(): ArtData = LocalDateTime.now().let { ArtData(signedIn = false, now = it, goals = GoalCalendar.day(it.toLocalDate(), null, null, com.fitbittrack.pulse.domain.Goals())) }

    private suspend fun read(context: Context, repo: HealthRepository, now: LocalDateTime): ArtData {
        val prefs = UiPrefsStore(context)
        val today = now.toLocalDate()
        val from = today.minusDays(400)
        val days = repo.daySummaries(from, today).first()
        val sleeps = repo.sleep(from, today).first()
        val sync = repo.syncStatus.first()
        return build(
            now = now, days = days, sleeps = sleeps,
            history = repo.goalHistory.first(),
            core = prefs.personal.first().calendarGoals,
            hourly = repo.savedHourlySteps(today).first(),
            yesterdayHourly = repo.savedHourlySteps(today.minusDays(1)).first(),
            heart = repo.savedHeartRate(today).first(),
            units = prefs.prefs.first().units,
            bedtime = prefs.reminders.first().bedtime,
        ).copy(
            battery = sync.battery?.level, batteryWord = sync.battery?.status?.lowercase()?.replace('_', ' '),
            syncedAgo = Fmt.ago(sync.lastSuccess, Instant.now()),
        )
    }

    /** Pure: easy to test. */
    fun build(
        now: LocalDateTime,
        days: List<DaySummary>,
        sleeps: List<SleepSession>,
        history: List<com.fitbittrack.pulse.domain.DatedGoals>,
        core: Set<GoalMetric>,
        hourly: List<Long?>,
        yesterdayHourly: List<Long?>,
        heart: List<com.fitbittrack.pulse.domain.HeartRateSample>,
        units: Units,
        bedtime: LocalTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ArtData {
        val today = now.toLocalDate()
        val goals = GoalCalendar.goalsOn(history, today)
        val byDate = days.associateBy { it.date }
        val all = GoalCalendar.days(minOf(days.firstOrNull()?.date ?: today, today), today, days, sleeps, history, zone, core)
        val last30 = days.filter { it.date > today.minusDays(31) }
        val heartUi = Mappers.heart(Range.Day, heart.filter { it.time.atZone(zone).toLocalDateTime() <= now }, last30, today, goals)
        fun wakeDay(s: SleepSession) = s.wakeDate ?: s.end.atZone(zone).toLocalDate()
        val main = sleeps.filter { it.isMainSleep }
        val night = main.lastOrNull { wakeDay(it) == today }
        val sleepUi = Mappers.sleep(listOfNotNull(night), days, goals)
        val nights = main.groupBy(::wakeDay).mapValues { (_, l) -> l.maxOf { it.minutesAsleep }.toInt() }
        val doneByDate = all.associate { it.date to it.met(null) }
        val todayGoals = all.lastOrNull { it.date == today } ?: GoalCalendar.day(today, byDate[today], night?.minutesAsleep?.toInt(), goals, core)
        return ArtData(
            signedIn = true, now = now, units = units, goals = todayGoals,
            hourly = hourly, yesterdayHourly = yesterdayHourly,
            zoneWeek = days.filter { it.date > today.minusDays(7) && it.date <= today }.mapNotNull { it.activeZoneMinutes }.takeIf { it.isNotEmpty() }?.sum(),
            zoneWeekGoal = goals.weeklyActiveZoneMinutes,
            restingHr = byDate[today]?.restingHeartRate ?: days.lastOrNull { it.restingHeartRate != null && it.date > today.minusDays(3) }?.restingHeartRate,
            resting7 = (6L downTo 0L).map { byDate[today.minusDays(it)]?.restingHeartRate },
            heart = heartUi.samples,
            hrv = byDate[today]?.hrvMs, hrvRange = heartUi.hrvNormalRange,
            sleepMinutes = night?.minutesAsleep?.toInt(), sleepStart = sleepUi.start, sleepEnd = sleepUi.end, blocks = sleepUi.blocks,
            nights7 = (6L downTo 0L).map { today.minusDays(it) }.map { it to nights[it] },
            signals = signals(byDate[today], last30.filter { it.date != today }),
            streak = GoalCalendar.currentStreak(all, null, today),
            before13 = (13L downTo 1L).map { doneByDate[today.minusDays(it)] == true },
            todayComplete = todayGoals.met(null),
            monthGrowth = (1..today.dayOfMonth).map { d ->
                val day = all.firstOrNull { it.date == today.withDayOfMonth(d) }
                when {
                    day == null || day.coreMet == 0 -> 0
                    day.met(null) -> 2
                    else -> 1
                }
            },
            monthLength = today.lengthOfMonth(),
            bedtime = bedtime,
        )
    }

    fun signals(day: DaySummary?, before: List<DaySummary>): List<Signal> {
        fun one(label: String, text: String, value: Double?, past: List<Double>, minSpread: Double): Signal {
            if (value == null || past.size < 7) return Signal(label, if (value == null) Fmt.NONE else text, null, 0.3f, 0.7f)
            val mean = past.average()
            val sd = kotlin.math.sqrt(past.sumOf { (it - mean) * (it - mean) } / past.size).coerceAtLeast(minSpread)
            // The track shows mean ± 3 spreads; the usual range is ± 1.5.
            return Signal(label, text, (0.5 + (value - mean) / (6 * sd)).toFloat().coerceIn(0f, 1f), 0.25f, 0.75f)
        }
        return listOf(
            one("Heart", Fmt.count(day?.restingHeartRate), day?.restingHeartRate?.toDouble(), before.mapNotNull { it.restingHeartRate?.toDouble() }, 1.0),
            one("HRV", Fmt.whole(day?.hrvMs), day?.hrvMs, before.mapNotNull { it.hrvMs }, 2.0),
            one("Oxygen", Fmt.whole(day?.spo2Percent) + "%", day?.spo2Percent, before.mapNotNull { it.spo2Percent }, 0.7),
            one("Breathing", Fmt.oneDecimal(day?.respiratoryRate), day?.respiratoryRate, before.mapNotNull { it.respiratoryRate }, 0.4),
            one("Skin temp", Fmt.signedTemp(day?.skinTempDeltaC), day?.skinTempDeltaC, before.mapNotNull { it.skinTempDeltaC }, 0.15),
        )
    }
}

/** The small decisions behind the drawings. Pure functions: easy to test. */
object ArtLogic {

    enum class SkyTime { Morning, Day, Evening, Night }
    data class SkyState(val time: SkyTime, val label: String, val headline: String, val detail: String, val foot: String, val progress: Float)

    fun sky(d: ArtData): SkyState {
        val hour = d.now.hour
        val steps = d.steps
        val left = steps?.let { (d.stepGoal - it).coerceAtLeast(0) }
        val part = d.part(GoalMetric.Steps)
        val synced = "Synced ${d.syncedAgo}"
        val walk = when {
            steps == null -> "No steps yet" to "Waiting for your band to sync"
            left == 0L -> "Step goal reached" to "${Fmt.count(steps)} steps so far"
            else -> "${Fmt.count(left)} steps to go" to "About ${((left ?: 0) / 100).coerceAtLeast(1)} min of walking"
        }
        return when {
            hour in 4..10 && d.sleepMinutes != null -> SkyState(
                SkyTime.Morning, "Last night", "${Fmt.minutes(d.sleepMinutes)} of sleep",
                if (d.sleepMinutes >= d.sleepGoal) "Sleep goal reached" else "${short(d.sleepGoal - d.sleepMinutes)} under your goal",
                d.sleepEnd?.let { "Woke ${Fmt.clock(it)}" } ?: synced, d.part(GoalMetric.Sleep),
            )
            hour in 4..10 -> SkyState(SkyTime.Morning, "This morning", walk.first, walk.second, synced, part)
            hour in 11..16 -> SkyState(SkyTime.Day, "Today", walk.first, walk.second, synced, part)
            hour in 17..20 -> SkyState(SkyTime.Evening, "This evening", walk.first, walk.second, synced, part)
            else -> SkyState(
                SkyTime.Night, if (left == 0L) "Step goal done" else "Tonight", "Time to slow down", "Bed by ${Fmt.clock(d.bedtime)}",
                if (steps == null) synced else "${Fmt.count(steps)} steps today", part,
            )
        }
    }

    fun short(min: Int): String = if (min < 60) "$min min" else Fmt.minutes(min)

    /** Minutes until bed, 0 when it is bedtime or up to 6 hours after. */
    fun minutesToBed(now: LocalTime, bed: LocalTime): Int {
        val diff = Math.floorMod((bed.toSecondOfDay() - now.toSecondOfDay()) / 60, 1440)
        return if (diff > 1440 - 360) 0 else diff
    }

    /** Yesterday's steps up to this time of day. Null when yesterday has no hours saved. */
    fun yesterdayByNow(hourly: List<Long?>, now: LocalTime): Long? {
        if (hourly.all { it == null }) return null
        val full = hourly.take(now.hour).sumOf { it ?: 0 }
        return full + ((hourly.getOrNull(now.hour) ?: 0) * now.minute / 60.0).toLong()
    }

    enum class Mood(val say: String) { Asleep("Good night"), Tired("Short night"), Proud("Goal reached"), Happy("Feeling great"), Calm("Ready to move") }

    fun mood(d: ArtData): Mood = when {
        d.now.hour >= 22 || d.now.hour < 5 -> Mood.Asleep
        d.goals[GoalMetric.Steps].met -> Mood.Proud
        d.sleepMinutes != null && d.sleepMinutes < d.sleepGoal - 90 -> Mood.Tired
        d.part(GoalMetric.Steps) >= 0.4f -> Mood.Happy
        else -> Mood.Calm
    }

    fun hrvWord(hrv: Double?, range: ClosedFloatingPointRange<Double>?): String = when {
        hrv == null -> "No reading yet"
        range == null -> "Learning your usual"
        hrv < range.start -> "Below your usual"
        hrv > range.endInclusive -> "Above your usual"
        else -> "As usual"
    }

    /** Headline and, when something stands out, the first thing that does. */
    fun signalsText(s: List<Signal>): Pair<String, String?> {
        val known = s.filter { it.side != null }
        if (known.isEmpty()) return "Learning your usual" to null
        val out = known.firstOrNull { it.side != 0 }
        return "${known.count { it.side == 0 }} of ${known.size} as usual" to out?.let { "${it.label} ${if (it.side == 1) "higher" else "lower"}" }
    }

    /** Walks [part] of the way along a line. Returns the corner points passed and the end point. */
    fun along(points: List<Pair<Float, Float>>, part: Float): Pair<Int, Pair<Float, Float>> {
        val seg = points.zipWithNext { a, b -> kotlin.math.hypot(b.first - a.first, b.second - a.second) }
        var left = seg.sum() * part.coerceIn(0f, 1f)
        var i = 0
        while (i < seg.lastIndex && left > seg[i]) { left -= seg[i]; i++ }
        val f = (left / seg[i]).coerceIn(0f, 1f)
        val (a, b) = points[i] to points[i + 1]
        return i to (a.first + (b.first - a.first) * f to a.second + (b.second - a.second) * f)
    }

    /** Minutes in deep sleep, null without stages. */
    fun deepMinutes(blocks: List<StageBlock>): Int? = blocks.filter { it.stage == Stage.Deep }.sumOf { it.endMinute - it.startMinute }.takeIf { blocks.isNotEmpty() }

    fun thousands(v: Long?): String = when {
        v == null -> Fmt.NONE
        v < 1000 -> v.toString()
        v < 99_950 -> "%.1fk".format(v / 1000.0)
        else -> "${v / 1000}k"
    }
}
