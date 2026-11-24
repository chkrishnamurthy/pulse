package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitbittrack.pulse.air.AirLevel
import com.fitbittrack.pulse.air.AirProblem
import com.fitbittrack.pulse.air.AirReading
import com.fitbittrack.pulse.air.AirSource
import com.fitbittrack.pulse.air.AirState
import com.fitbittrack.pulse.ui.components.CardShape
import com.fitbittrack.pulse.ui.components.FitText
import com.fitbittrack.pulse.ui.components.MetricLabel
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.PulsePage
import com.fitbittrack.pulse.ui.components.SampleChip
import com.fitbittrack.pulse.ui.components.Space
import com.fitbittrack.pulse.ui.components.StatusPill
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.components.fill
import com.fitbittrack.pulse.ui.components.label
import com.fitbittrack.pulse.ui.components.tone
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.DayGoals
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.goals.GoalProgress
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.SyncUi
import com.fitbittrack.pulse.ui.model.TodayCard
import com.fitbittrack.pulse.ui.model.TodayUi
import com.fitbittrack.pulse.ui.model.UiPrefs
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home: the whole day on one screen. Which cards show is chosen in You, Today screen. */
@Composable
fun TodayScreen(
    today: TodayUi,
    prefs: UiPrefs,
    sync: SyncUi,
    isSample: Boolean,
    contentPadding: PaddingValues,
    week: List<DayGoals>,
    lowBatteryAt: Int,
    /** Null until the setting has been read. */
    airOn: Boolean?,
    air: AirState,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onOpenSync: () -> Unit,
    onCalendar: () -> Unit,
    onDay: (LocalDate) -> Unit,
    onMetric: (MetricKind) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenHeart: () -> Unit,
    onOpenBand: () -> Unit,
    onOpenAir: () -> Unit,
    onAsk: (String?) -> Unit,
) {
    val shown = prefs.todayCards.filter { it.second }.map { it.first }
    val goals = week.lastOrNull { it.date == today.date }
    // Without the air card no location is used, so the circle stays plain.
    val level = air.reading?.level.takeIf { airOn == true && TodayCard.Air in shown }
    PullToRefreshBox(isRefreshing = sync.running, onRefresh = onRefresh) {
        PulsePage(contentPadding) {
            // Closer together than other pages, so the whole day fits on one screen.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeHeader(today.date, sync, isSample, onConnect, onOpenSync) { onAsk(null) }
                if (sync.firstSyncPending || sync.historyLabel != null) FirstSyncCard(sync)

                homeRows(shown).forEach { row ->
                    when {
                        TodayCard.Band in row || TodayCard.Air in row -> TileRow {
                            row.forEach { card ->
                                if (card == TodayCard.Band) BandTile(bandLook(sync, lowBatteryAt, isSample), sync.batteryPercent, Modifier.weight(1f), onOpenBand)
                                else AirTile(airOn, air, prefs.units, Modifier.weight(1f), onOpenAir)
                            }
                        }
                        TodayCard.Ring in row -> if (goals != null) {
                            RingCard(
                                circle = goals.items.filter { it.metric in prefs.ringGoals },
                                lines = homeGoals(goals.items, prefs.ringGoals),
                                bpm = today.latestBpm, bpmAt = today.latestBpmAt, level = level, units = prefs.units,
                                onRing = { onDay(today.date) }, onHeart = onOpenHeart,
                            ) { m -> if (m == GoalMetric.Sleep) onOpenSleep() else onMetric(MetricKind.entries.first { it.goal == m }) }
                        }
                        TodayCard.Week in row -> if (goals != null) {
                            WeekCard(week, goals, today.date, onCalendar, onDay)
                        }
                        TodayCard.Tip in row -> today.insight?.let { i ->
                            TipCard(i.title, i.body, i.positive) { onAsk(if ("heart" in i.title.lowercase()) "Is my resting heart rate normal for me?" else "How did I sleep this week?") }
                        }
                        else -> BodyCard(today, prefs.units, onMetric)
                    }
                }
            }
        }
    }
}

/** One row per card, in the user's order. The two small tiles share a row when they follow each other. */
internal fun homeRows(cards: List<TodayCard>): List<List<TodayCard>> {
    val small = setOf(TodayCard.Band, TodayCard.Air)
    val rows = mutableListOf<List<TodayCard>>()
    cards.forEach { card ->
        val last = rows.lastOrNull()
        if (card in small && last?.size == 1 && last[0] in small) rows[rows.lastIndex] = last + card else rows += listOf(card)
    }
    return rows
}

/** The goals listed beside the circle: steps, sleep and calories, and anything else that is in the circle, so every arc has its number. */
internal fun homeGoals(items: List<GoalProgress>, circle: Set<GoalMetric>): List<GoalProgress> {
    val always = setOf(GoalMetric.Steps, GoalMetric.Sleep, GoalMetric.Calories)
    return items.filter { it.metric in always || it.metric in circle }
}

/** Average of the goals, each counted up to 100. Goals without data are left out, so missing data never lowers the day. */
internal fun dayPercent(goals: List<GoalProgress>): Int? =
    goals.mapNotNull { it.fraction }.takeIf { it.isNotEmpty() }?.let { f -> f.sumOf { (it.coerceIn(0f, 1f) * 100).toInt() } / f.size }

/** "Steps and calories", "Steps, sleep and calories". */
internal fun joinWords(words: List<String>): String {
    val w = words.mapIndexed { i, s -> if (i == 0) s else s.lowercase() }
    return if (w.size < 2) w.joinToString() else w.dropLast(1).joinToString() + " and " + w.last()
}

/** The phone's text size is well above normal, so things that sit side by side need more room. */
@Composable
private fun largeText(): Boolean = LocalDensity.current.fontScale > 1.3f

@Composable
private fun HomeHeader(date: LocalDate, sync: SyncUi, isSample: Boolean, onConnect: () -> Unit, onOpenSync: () -> Unit, onAsk: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Today", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            val pattern = if (largeText()) "EEE d MMM" else "EEEE d MMMM"
            FitLine(date.format(DateTimeFormatter.ofPattern(pattern, locale)), MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isSample) SampleChip(onConnect) else SyncChip(sync, onOpenSync)
        IconButton(onClick = onAsk) { Icon(PulseIcons.Chat, contentDescription = "Ask Pulse") }
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

// ---------- ring, air circle and goals ----------

@Composable
private fun RingCard(
    circle: List<GoalProgress>, lines: List<GoalProgress>, bpm: Int?, bpmAt: LocalTime?, level: AirLevel?, units: Units,
    onRing: () -> Unit, onHeart: () -> Unit, onGoal: (GoalMetric) -> Unit,
) {
    val rows: @Composable () -> Unit = {
        lines.forEach { g -> GoalLine(g, units, Modifier.fillMaxWidth()) { onGoal(g.metric) } }
        HeartLine(bpm, bpmAt, Modifier.fillMaxWidth(), onHeart)
    }
    val large = largeText()
    Box(Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = Space.gap, vertical = 8.dp)) {
        BoxWithConstraints {
            val room = maxWidth
            if (large || room < 290.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.gap)) {
                    AirRing(circle, level, 196.dp, onRing)
                    rows()
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AirRing(circle, level, minOf(196.dp, room * 0.54f), onRing)
                    Column(Modifier.weight(1f)) { rows() }
                }
            }
        }
    }
}

/** One coloured arc per goal on a soft circle in the colour of the air. */
@Composable
private fun AirRing(goals: List<GoalProgress>, level: AirLevel?, size: Dp, onClick: () -> Unit) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val day = dayPercent(goals)
    val halo = level?.let { airColor(it).copy(alpha = 0.2f) } ?: scheme.surfaceContainerHigh
    val card = scheme.surfaceContainer
    val say = "Your day: ${day?.let { "$it percent" } ?: "no data yet"}. " + (level?.let { "Air outside: ${it.label}." } ?: "")
    Box(
        Modifier.size(size).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Open today", onClick = onClick).semantics { contentDescription = say },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = 10.dp.toPx()
            val r = this.size.minDimension / 2 * 0.735f
            drawCircle(halo)
            drawCircle(card, r)
            val at = Offset(center.x - r, center.y - r)
            val box = Size(r * 2, r * 2)
            val slot = 360f / goals.size.coerceAtLeast(1)
            // Room for the round ends, so neighbours never touch.
            val gap = Math.toDegrees((w * 1.7 / r)).toFloat()
            goals.forEachIndexed { i, g ->
                val start = -90f + i * slot + gap / 2
                val sweep = slot - gap
                drawArc(c.track, start, sweep, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
                val f = (g.fraction ?: 0f).coerceIn(0f, 1f)
                if (f > 0.02f) drawArc(c.fill(g.metric.tone), start, sweep * f, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Column(Modifier.width(size * 0.52f), horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(day?.let { "$it%" } ?: Fmt.NONE, MaterialTheme.typography.headlineMedium.copy(fontSize = 38.sp, lineHeight = 44.sp), scheme.onSurface)
            FitText("of your day", MaterialTheme.typography.bodyMedium, scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GoalLine(g: GoalProgress, units: Units, modifier: Modifier, onClick: () -> Unit) {
    ValueLine(
        g.metric.label, PulseTheme.colors.label(g.metric.tone),
        if (g.value == null) "No data" else "${goalValue(g.metric, g.value, units)} of ${goalValue(g.metric, g.target, units)}",
        if (g.met) "Done" else g.percent?.let { "$it%" } ?: Fmt.NONE, modifier, onClick,
    )
}

@Composable
private fun HeartLine(bpm: Int?, at: LocalTime?, modifier: Modifier, onClick: () -> Unit) {
    ValueLine(
        "Heart rate", PulseTheme.colors.heartLabel,
        if (bpm == null) "No data today" else "At ${Fmt.clock(at)}",
        bpm?.let { "$it bpm" } ?: Fmt.NONE, modifier, onClick,
    )
}

/** A name with a quiet line under it, and the number on the right. */
@Composable
private fun ValueLine(name: String, color: Color, note: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            FitLine(name, MaterialTheme.typography.labelLarge, color)
            FitLine(note, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

/** One line that shrinks instead of wrapping, from the left. */
@Composable
private fun FitLine(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier, style.copy(color = color), maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

// ---------- band and air ----------

/** What the battery tile says. */
internal data class BandLook(val value: String, val note: String, val low: Boolean)

internal fun bandLook(sync: SyncUi, lowAt: Int, isSample: Boolean): BandLook {
    val level = sync.batteryPercent
    val word = sync.batteryWord
    val low = if (level != null) level <= lowAt else word == "low" || word == "empty"
    val value = level?.let { "$it%" } ?: word?.replaceFirstChar { it.uppercase() } ?: Fmt.NONE
    val note = when {
        level == null && word == null -> if (isSample) "Connect to see it" else "Not reported yet"
        low -> "Charge soon"
        sync.lastTrackerLabel == "unknown" -> "At the last sync"
        else -> sync.lastTrackerLabel.replaceFirstChar { it.uppercase() }
    }
    return BandLook(value, note, low)
}

@Composable
private fun BandTile(look: BandLook, level: Int?, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (look.low) scheme.error else PulseTheme.colors.bodyLabel
    val ink = if (look.low) scheme.onErrorContainer else scheme.onSurface
    val quiet = if (look.low) scheme.onErrorContainer else scheme.onSurfaceVariant
    Row(
        modifier.fillMaxHeight().clip(CardShape).background(if (look.low) scheme.errorContainer else scheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = Space.gap),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(width = 30.dp, height = 70.dp)) {
            val w = 3.dp.toPx()
            val strap = size.width * 0.5f
            val left = (size.width - strap) / 2
            val top = size.height * 0.22f
            val body = Size(size.width - w, size.height * 0.56f)
            // Straps above and below, then the body with its charge.
            drawLine(tint, Offset(left, w / 2), Offset(left, top), w)
            drawLine(tint, Offset(left + strap, w / 2), Offset(left + strap, top), w)
            drawLine(tint, Offset(left, w / 2), Offset(left + strap, w / 2), w, cap = StrokeCap.Round)
            drawLine(tint, Offset(left, top + body.height), Offset(left, size.height - w / 2), w)
            drawLine(tint, Offset(left + strap, top + body.height), Offset(left + strap, size.height - w / 2), w)
            drawLine(tint, Offset(left, size.height - w / 2), Offset(left + strap, size.height - w / 2), w, cap = StrokeCap.Round)
            drawRoundRect(tint, Offset(w / 2, top), body, CornerRadius(9.dp.toPx()), style = Stroke(w))
            if (level != null) {
                val pad = 5.5.dp.toPx()
                val room = body.height - pad * 2
                val h = room * (level.coerceIn(0, 100) / 100f)
                if (h > 1f) drawRoundRect(tint, Offset(w / 2 + pad, top + pad + room - h), Size(body.width - pad * 2, h), CornerRadius(3.dp.toPx()))
            }
        }
        Column(Modifier.weight(1f)) {
            FitLine("Fitbit battery", MaterialTheme.typography.labelLarge, ink)
            FitLine(look.value, MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp), ink)
            FitLine(look.note, MaterialTheme.typography.bodyMedium, quiet)
        }
    }
}

/** "Live" only for a station that measured within the last hour. */
internal fun airTag(r: AirReading, now: Instant): String = when (r.source) {
    is AirSource.Station -> if (Duration.between(r.at, now) <= Duration.ofHours(1)) "Live" else "Measured"
    AirSource.Estimate -> "Estimate"
}

/** Where and when the reading is from, for a small tile. The air page says the rest. */
internal fun airFrom(r: AirReading, units: Units, now: Instant, zone: ZoneId = ZoneId.systemDefault(), short: Boolean = false): String = when (val s = r.source) {
    is AirSource.Station -> Fmt.distance(s.distanceMeters, units).let { (v, u) -> "About $v $u away, ${Fmt.ago(r.at, now)}" }
    // Short: without the tag beside the title, the word must be here.
    AirSource.Estimate -> (if (short) "Estimate, " else "For your area, ") + Fmt.clock(r.at.atZone(zone).toLocalTime())
}

private fun airWord(level: AirLevel) = if (level == AirLevel.Sensitive) "Poor" else level.label

@Composable
private fun AirTile(on: Boolean?, state: AirState, units: Units, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val r = state.reading.takeIf { on == true }
    Column(
        modifier.fillMaxHeight().clip(CardShape).background(scheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = Space.gap),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FitLine("Air outside", MaterialTheme.typography.labelLarge, scheme.onSurface, Modifier.weight(1f))
            if (r != null) {
                Box(Modifier.size(7.dp).background(airColor(r.level), CircleShape))
                if (!largeText()) Text(airTag(r, Instant.now()), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (r != null) {
            FitLine("${airWord(r.level)} ${Fmt.count(r.aqi)}", MaterialTheme.typography.titleLarge, scheme.onSurface)
            AirScale(r.aqi)
            FitLine(if (state.problem != null) "Showing the last reading" else airFrom(r, units, Instant.now(), short = largeText()), MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant)
        } else {
            val (value, note) = when {
                on == null -> "" to ""
                !on -> "Off" to "Tap to turn on"
                state.problem == AirProblem.NoPermission -> "Off" to "Tap to allow"
                state.problem == AirProblem.LocationOff -> Fmt.NONE to "Location is off"
                state.problem != null -> Fmt.NONE to "Tap to try again"
                else -> "…" to "Checking"
            }
            FitLine(value, MaterialTheme.typography.titleLarge, scheme.onSurface)
            FitLine(note, MaterialTheme.typography.bodyMedium, scheme.onSurfaceVariant)
        }
    }
}

// ---------- week, tip and body ----------

@Composable
private fun WeekCard(week: List<DayGoals>, goals: DayGoals, today: LocalDate, onCalendar: () -> Unit, onDay: (LocalDate) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onCalendar).padding(horizontal = Space.card).padding(top = 10.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Last 7 days", style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline().weight(1f))
            Text(joinWords(goals.core.map { it.metric.label }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
        }
        WeekStrip(week, today, onDay)
    }
}

@Composable
private fun TipCard(title: String, body: String, positive: Boolean, onAsk: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(CardShape).background(scheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = "Ask about this", onClick = onAsk).padding(horizontal = Space.card, vertical = Space.gap),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).background(if (positive) PulseTheme.colors.good else PulseTheme.colors.heart, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            // The first sentence: the numbers. The chat explains the rest.
            if (largeText()) Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            else FitLine(body.substringBefore(". ").removeSuffix("."), MaterialTheme.typography.bodyMedium, scheme.onSurfaceVariant)
        }
        Text("Ask", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun BodyCard(t: TodayUi, units: Units, onMetric: (MetricKind) -> Unit) {
    val c = PulseTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cells = listOf(
        Triple(MetricKind.RestingHeart, "Resting heart", t.restingHeartRate?.toDouble()),
        Triple(MetricKind.Hrv, "Variability", t.hrvMs),
        Triple(MetricKind.Spo2, "Blood oxygen", t.spo2Percent),
        Triple(MetricKind.Distance, "Distance", t.distanceMeters),
        Triple(MetricKind.Floors, "Floors", t.floors?.toDouble()),
        Triple(MetricKind.Breathing, "Breathing", t.respiratoryRate),
    )
    val columns = if (largeText()) 2 else 3
    Column(Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 4.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (kind, name, v) ->
                    Column(
                        Modifier.weight(1f).clickable(role = Role.Button) { onMetric(kind) }.heightIn(min = 56.dp).padding(start = Space.card, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MetricLabel(name, c.label(kind.tone))
                        val (value, unit) = kind.format(v, units)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.alignByBaseline())
                            if (v != null) {
                                Text(
                                    if (kind == MetricKind.Floors) "of ${t.floorsGoal}" else unit,
                                    style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, modifier = Modifier.alignByBaseline(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
