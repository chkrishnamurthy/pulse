package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.BarChart
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.LineChart
import com.fitbittrack.pulse.ui.components.ListRow
import com.fitbittrack.pulse.ui.components.MetricTile
import com.fitbittrack.pulse.ui.components.ProgressBar
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.components.Segmented
import com.fitbittrack.pulse.ui.components.TileRow
import com.fitbittrack.pulse.ui.components.fill
import com.fitbittrack.pulse.ui.components.label
import com.fitbittrack.pulse.ui.model.MetricKind
import com.fitbittrack.pulse.ui.model.MetricUi
import com.fitbittrack.pulse.ui.model.ReportRange
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val MetricKind.icon
    get() = when (this) {
        MetricKind.Steps -> PulseIcons.Steps
        MetricKind.Calories -> PulseIcons.Flame
        MetricKind.Zone -> PulseIcons.Bolt
        MetricKind.Distance -> PulseIcons.Distance
        MetricKind.Floors -> PulseIcons.Floors
        MetricKind.RestingHeart, MetricKind.Hrv -> PulseIcons.Heart
        MetricKind.Spo2 -> PulseIcons.Drop
        MetricKind.Breathing -> PulseIcons.Wind
        MetricKind.SkinTemp -> PulseIcons.Thermo
        MetricKind.Vo2Max -> PulseIcons.Gauge
    }

/** The same page for every number: how much now, how it moved, and what's normal. */
@Composable
fun MetricScreen(
    kind: MetricKind,
    ui: MetricUi?,
    range: ReportRange,
    units: Units,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRange: (ReportRange) -> Unit,
    onDay: (LocalDate) -> Unit,
    onAsk: (String) -> Unit,
) {
    val c = PulseTheme.colors
    val tint = c.fill(kind.tone)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = LocalConfiguration.current.locales[0]
    DetailPage(kind.label, contentPadding, onBack) {
        Segmented(ReportRange.entries, range, { it.label }, onRange)
        if (ui == null || ui.range != range) return@DetailPage

        // --- Now ---
        val latest = ui.latest
        PulseCard {
            Text(latest?.let { whenLabel(it.first, ui.today, locale) } ?: "No data yet", style = MaterialTheme.typography.bodyMedium, color = muted)
            val (v, u) = kind.format(latest?.second, units)
            BigValue(v, u.takeIf { latest != null }, 40)
            if (ui.target != null && latest != null) {
                ProgressBar((latest.second / ui.target).toFloat(), tint, height = 10)
                val rest = ui.target - latest.second
                CardHeaderless("Goal ${kind.text(ui.target, units)}", if (rest <= 0) "Reached" else "${kind.format(rest, units).first} to go", rest <= 0)
            }
        }

        // --- Over time ---
        PulseCard {
            CardHeader(
                when (range) { ReportRange.Week -> "Last 7 days"; ReportRange.Month -> "Last 30 days"; ReportRange.Year -> "Last 12 months" },
                ui.average?.let { "Average ${kind.text(it, units)}" },
            )
            if (ui.daysWithData == 0) {
                Text("No data in this time. It appears after your band syncs.", style = MaterialTheme.typography.bodyMedium, color = muted)
            } else if (kind.bars) {
                BarChart(
                    values = ui.bars.map { it.second?.toFloat() },
                    labels = ui.bars.map { (d, _) -> axisLabel(d, range, locale) },
                    color = tint, mutedColor = if (ui.target == null) tint else tint.copy(alpha = 0.42f),
                    description = "${kind.label}, ${range.label.lowercase()}, average ${kind.text(ui.average, units)}",
                    goal = ui.target?.toFloat(), height = 180.dp,
                )
                if (ui.target != null) Text(
                    if (range == ReportRange.Year) "Bars are monthly averages. Dashed line: goal." else "Dashed line: goal. Bright bars reached it.",
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
            } else {
                LineChart(
                    points = ui.bars.mapIndexed { i, (_, v) -> i / (ui.bars.size - 1f).coerceAtLeast(1f) to v?.toFloat() },
                    color = tint,
                    description = "${kind.label}, from ${kind.text(ui.lowest?.second, units)} to ${kind.text(ui.best?.second, units)}",
                    xLabels = ui.bars.takeIf { it.size > 1 }?.let { listOf(0f to it.first().first.format(short(locale)), 1f to it.last().first.format(short(locale))) } ?: emptyList(),
                    yLabel = { kind.format(it.toDouble(), units).first },
                )
            }
        }

        // --- Facts ---
        if (ui.daysWithData > 0) {
            TileRow {
                Fact("Highest", ui.best, kind, units, locale, Modifier.weight(1f), onDay)
                Fact("Lowest", ui.lowest, kind, units, locale, Modifier.weight(1f), onDay)
            }
            TileRow {
                val label = c.label(kind.tone)
                if (ui.total != null) kind.format(ui.total, units).let { (v, u) -> MetricTile("Total", label, v, u, Modifier.weight(1f), size = 24) }
                else kind.format(ui.average, units).let { (v, u) -> MetricTile("Average", label, v, u, Modifier.weight(1f), size = 24) }
                if (ui.target != null) MetricTile("Goal reached", label, "${ui.daysMet}", "of ${ui.daysWithData} days", Modifier.weight(1f), size = 24)
                else MetricTile("Days with data", label, "${ui.daysWithData}", "of ${range.days}", Modifier.weight(1f), size = 24)
            }
        }

        if (kind == MetricKind.Steps && ui.hourly.any { (it ?: 0) > 0 }) {
            PulseCard {
                CardHeader("Today by hour")
                BarChart(ui.hourly.map { it?.toFloat() }, List(24) { if (it % 6 == 0) hourLabel(it) else "" }, tint, tint, "Steps in each hour today", height = 120.dp)
            }
        }

        if (range == ReportRange.Week && ui.daysWithData > 0) {
            SectionTitle("Day by day")
            RowGroup(ui.days.reversed().map { (d, v) ->
                { ListRow(kind.icon, c.label(kind.tone), whenLabel(d, ui.today, locale), value = kind.text(v, units), onClick = { onDay(d) }) }
            })
        }
        AskChips("Ask Pulse", listOf("How is my ${kind.label.lowercase()} this week?", "Compare my ${kind.label.lowercase()} with last week"), onAsk)
        Text(kind.about, style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

@Composable
private fun CardHeaderless(left: String, right: String, reached: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(left, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(right, style = MaterialTheme.typography.bodyMedium, color = if (reached) PulseTheme.colors.good else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Fact(title: String, at: Pair<LocalDate, Double>?, kind: MetricKind, units: Units, locale: Locale, modifier: Modifier, onDay: (LocalDate) -> Unit) {
    val (v, u) = kind.format(at?.second, units)
    MetricTile(title, PulseTheme.colors.label(kind.tone), v, u, modifier, at?.let { { onDay(it.first) } }, size = 24) {
        Text(at?.first?.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

internal fun short(locale: Locale): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", locale)

internal fun whenLabel(d: LocalDate, today: LocalDate, locale: Locale): String = when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
}

internal fun axisLabel(d: LocalDate, range: ReportRange, locale: Locale): String = when (range) {
    ReportRange.Week -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    ReportRange.Month -> if (d.dayOfMonth % 7 == 1) "${d.dayOfMonth}" else ""
    ReportRange.Year -> d.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
}
