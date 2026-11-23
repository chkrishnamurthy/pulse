package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.MetricLabel
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.SampleBanner
import com.fitbittrack.pulse.ui.components.Sparkline
import com.fitbittrack.pulse.ui.components.StageBar
import com.fitbittrack.pulse.ui.components.StatusPill
import com.fitbittrack.pulse.ui.components.TabHeader
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.components.icon
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.DayGoals
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.SyncUi
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.model.TodayUi
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TodayScreen(
    today: TodayUi,
    prefs: UiPrefs,
    sync: SyncUi,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    week: List<DayGoals>,
    onOpenSync: () -> Unit,
    onCustomize: () -> Unit,
    onCalendar: () -> Unit,
    onDay: (LocalDate) -> Unit,
    onMetric: (MetricKind) -> Unit,
    onOpenSleep: () -> Unit,
    onAsk: (String?) -> Unit,
) {
    val c = PulseTheme.colors
    val shown = prefs.todayCards.filter { it.second }.map { it.first }
    PullToRefreshBox(isRefreshing = sync.running, onRefresh = onRefresh) {
        PulsePage(contentPadding) {
            val locale = LocalConfiguration.current.locales[0]
            TabHeader("Today", today.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))) {
                if (!isSample) SyncChip(sync, onOpenSync)
                IconButton(onClick = { onAsk(null) }) { Icon(PulseIcons.Chat, contentDescription = "Ask Pulse") }
            }
            if (isSample) SampleBanner(onConnect)
            if (sync.firstSyncPending || sync.historyLabel != null) FirstSyncCard(sync)

            if (TodayCard.Dial in shown) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(292.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Open steps") { onMetric(MetricKind.Steps) })
                    DayDial(
                        sleepStart = today.sleepStart,
                        sleepEnd = today.sleepEnd,
                        stepsPerHour = today.stepsPerHour,
                        now = today.now,
                        modifier = Modifier.size(292.dp).semantics {
                            contentDescription = "Day dial. ${Fmt.count(today.steps)} steps. Slept ${Fmt.clock(today.sleepStart)} to ${Fmt.clock(today.sleepEnd)}."
                        },
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MetricLabel("Steps", c.moveLabel)
                        Text(Fmt.count(today.steps), style = MaterialTheme.typography.displayLarge)
                        val pct = today.steps?.let { (it * 100 / today.stepGoal.coerceAtLeast(1)).toInt() }
                        Text("of ${Fmt.count(today.stepGoal)}${pct?.let { " · $it%" } ?: ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            val goalsToday = week.lastOrNull { it.date == today.date }
            if (goalsToday != null) {
                PulseCard {
                    CardHeader("Daily goals", "${goalsToday.todayGoals.count { it.met }} of ${goalsToday.todayGoals.size} reached")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        goalsToday.todayGoals.forEach { g ->
                            GoalTile(g, prefs.units, g.metric.icon, Modifier.weight(1f)) {
                                if (g.metric == GoalMetric.Sleep) onOpenSleep() else onMetric(MetricKind.entries.first { it.goal == g.metric })
                            }
                        }
                    }
                }
                PulseCard(onClick = onCalendar) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
                            Text("Steps and calories", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Calendar", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(PulseIcons.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                    }
                    WeekStrip(week, today.date, onDay)
                }
            }

            today.insight?.let { i ->
                InsightCard(i.title, i.body, i.positive) { onAsk(if ("heart" in i.title.lowercase()) "Is my resting heart rate normal for me?" else "How did I sleep this week?") }
            }

            val tiles = shown.filter { it != TodayCard.Dial }
            tiles.chunked(2).forEach { row ->
                TileRow {
                    row.forEach { card -> TodayTile(card, today, prefs.units, Modifier.weight(1f), onMetric, onOpenSleep) }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }

            TextButton(onClick = onCustomize, modifier = Modifier.fillMaxWidth()) { Text("Customize this screen") }
        }
    }
}

@Composable
private fun SyncChip(sync: SyncUi, onClick: () -> Unit) {
    val c = PulseTheme.colors
    Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        when {
            sync.needsSignIn || sync.error != null ->
                StatusPill(if (sync.needsSignIn) "Sign in again" else "Sync problem", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.surfaceContainerHigh)
            sync.running -> StatusPill(sync.progressLabel ?: "Syncing…", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
            else -> StatusPill(sync.lastSuccessLabel, c.good, c.goodContainer)
        }
    }
}

@Composable
private fun FirstSyncCard(sync: SyncUi) {
    PulseCard {
        val title = when {
            sync.historyLabel != null -> "Downloading your history"
            sync.running -> "Getting your latest data"
            else -> "Waiting for the first sync"
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        (sync.historyProgress ?: sync.progress)?.let { ProgressBar(it, PulseTheme.colors.body) }
        Text(
            sync.historyLabel ?: sync.progressLabel ?: sync.error ?: "This starts automatically when your phone is online. It can take a few minutes for a long history.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (sync.error != null && sync.historyLabel == null && sync.progressLabel == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InsightCard(title: String, body: String, positive: Boolean, onAsk: () -> Unit) {
    PulseCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).background(if (positive) PulseTheme.colors.good else PulseTheme.colors.heart, CircleShape).align(Alignment.Top))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onAsk, contentPadding = PaddingValues(0.dp)) { Text("Ask about this") }
            }
        }
    }
}

@Composable
private fun TodayTile(
    card: TodayCard, t: TodayUi, units: com.fitbittrack.pulse.ui.model.Units, modifier: Modifier,
    onMetric: (MetricKind) -> Unit, onOpenSleep: () -> Unit,
) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (card) {
        TodayCard.RestingHeart -> MetricTile("Resting heart rate", c.heartLabel, Fmt.count(t.restingHeartRate), "bpm", modifier, { onMetric(MetricKind.RestingHeart) }) {
            Sparkline(t.restingHeartRateLast7.map { it?.toFloat() }, c.heart)
        }
        TodayCard.Sleep -> MetricTile("Sleep", c.sleepLabel, Fmt.minutes(t.sleepMinutes), null, modifier, onOpenSleep) {
            t.sleepShares?.let { StageBar(it) }
        }
        TodayCard.ZoneMinutes -> MetricTile("Zone minutes", c.activeLabel, Fmt.count(t.zoneMinutes), "of ${t.zoneMinutesDailyTarget}", modifier, { onMetric(MetricKind.Zone) }) {
            ProgressBar((t.zoneMinutes ?: 0).toFloat() / t.zoneMinutesDailyTarget.coerceAtLeast(1), c.active)
        }
        TodayCard.CaloriesDistance -> MetricTile("Calories", c.energyLabel, Fmt.whole(t.caloriesKcal), "kcal", modifier, { onMetric(MetricKind.Calories) }) {
            val (d, u) = Fmt.distance(t.distanceMeters, units)
            Text("$d $u", style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        TodayCard.Spo2 -> MetricTile("Blood oxygen", c.bodyLabel, Fmt.whole(t.spo2Percent), "%", modifier, { onMetric(MetricKind.Spo2) })
        TodayCard.Hrv -> MetricTile("Heart variability", c.heartLabel, Fmt.whole(t.hrvMs), "ms", modifier, { onMetric(MetricKind.Hrv) })
        TodayCard.Floors -> MetricTile("Floors", c.bodyLabel, Fmt.count(t.floors), "of ${t.floorsGoal}", modifier, { onMetric(MetricKind.Floors) }) {
            ProgressBar((t.floors ?: 0).toFloat() / t.floorsGoal.coerceAtLeast(1), c.body)
        }
        TodayCard.Dial -> Unit
    }
}

/**
 * The 24-hour dial from the design: midnight at the top, clockwise.
 * Blue arc = sleep, amber ticks = steps per hour, ring dot = now.
 */
@Composable
fun DayDial(
    sleepStart: LocalTime?,
    sleepEnd: LocalTime?,
    stepsPerHour: List<Long?>,
    now: LocalTime,
    modifier: Modifier = Modifier,
) {
    val c = PulseTheme.colors
    val nowColor = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.background
    fun hourOf(t: LocalTime) = t.hour + t.minute / 60f
    Canvas(modifier) {
        val ring = 15.dp.toPx()
        val radius = size.minDimension / 2 - 30.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        fun angle(hour: Float) = hour / 24f * 360f - 90f

        drawArc(c.track, 0f, 360f, false, topLeft, arcSize, style = Stroke(ring))
        if (sleepStart != null && sleepEnd != null) {
            val s = hourOf(sleepStart)
            val sweep = ((hourOf(sleepEnd) - s + 24f) % 24f) / 24f * 360f
            drawArc(c.sleep, angle(s), sweep, false, topLeft, arcSize, style = Stroke(ring, cap = StrokeCap.Round))
        }
        val r1 = radius + ring / 2 + 6.dp.toPx()
        stepsPerHour.take(24).forEachIndexed { h, steps ->
            val a = Math.toRadians(angle(h + 0.5f).toDouble())
            val len = if (steps == null || steps == 0L) 3.dp.toPx() else maxOf(3.dp.toPx(), steps / 1000f * 20.dp.toPx())
            val color = if (steps == null || steps == 0L) c.track else c.move
            val start = Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat())
            val end = Offset(center.x + (r1 + len) * cos(a).toFloat(), center.y + (r1 + len) * sin(a).toFloat())
            drawLine(color, start, end, strokeWidth = 4.5.dp.toPx(), cap = StrokeCap.Round)
        }
        val n = Math.toRadians(angle(hourOf(now)).toDouble())
        val dot = Offset(center.x + radius * cos(n).toFloat(), center.y + radius * sin(n).toFloat())
        drawCircle(bg, 8.dp.toPx(), dot)
        drawCircle(nowColor, 8.dp.toPx(), dot, style = Stroke(3.dp.toPx()))
    }
}
