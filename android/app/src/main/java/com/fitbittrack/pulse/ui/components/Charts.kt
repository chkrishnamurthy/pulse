package com.fitbittrack.pulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.NightBar
import com.fitbittrack.pulse.ui.theme.BodyFamily
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Small hand-drawn charts matching the design canvas. All colours come from the theme,
 * so every chart works in light and dark mode. Missing values (null) are left as gaps.
 */

private val axisStyle = TextStyle(fontFamily = BodyFamily, fontSize = 11.sp, fontFeatureSettings = "tnum")

/** Vertical bars with an optional dashed goal line. Bars at or over goal use [color], others [mutedColor]. */
@Composable
fun BarChart(
    values: List<Float?>,
    labels: List<String>,
    color: Color,
    mutedColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    goal: Float? = null,
    height: Dp = 150.dp,
) {
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val goalColor = MaterialTheme.colorScheme.onSurface
    val track = PulseTheme.colors.track
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val labelH = 20.dp.toPx()
        val chartH = size.height - labelH
        val max = (values.filterNotNull() + listOfNotNull(goal)).maxOrNull()?.takeIf { it > 0 } ?: 1f
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = (slot * 0.62f).coerceAtMost(34.dp.toPx())
        val radius = CornerRadius(minOf(barW / 2, 8.dp.toPx()))
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barW) / 2
            if (v == null) {
                drawRoundRect(track, Offset(x, chartH - 4.dp.toPx()), Size(barW, 4.dp.toPx()), radius)
            } else {
                val h = (v / max * (chartH - 6.dp.toPx())).coerceAtLeast(3.dp.toPx())
                val c = if (goal == null || v >= goal) color else mutedColor
                drawRoundRect(c, Offset(x, chartH - h), Size(barW, h), radius)
            }
            labels.getOrNull(i)?.let { drawLabel(measurer, it, i * slot + slot / 2, size.height - labelH / 2, axis) }
        }
        if (goal != null) {
            val y = chartH - goal / max * (chartH - 6.dp.toPx())
            // Drawn over the bars in the text colour, so it shows on both bright and pale bars.
            drawLine(goalColor.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
    }
}

/** Line over time. x in 0..1, y in data units. Gaps (null) break the line. */
@Composable
fun LineChart(
    points: List<Pair<Float, Float?>>,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    yMin: Float? = null,
    yMax: Float? = null,
    referenceY: Float? = null,
    referenceLabel: String? = null,
    xLabels: List<Pair<Float, String>> = emptyList(),
    height: Dp = 180.dp,
    bands: List<Triple<Float, Float, Color>> = emptyList(),
    /** How a value on the scale at the right is written. */
    yLabel: (Float) -> String = { it.roundToInt().toString() },
) {
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val labelH = if (xLabels.isEmpty()) 0f else 20.dp.toPx()
        val chartH = size.height - labelH
        val ys = points.mapNotNull { it.second }
        // Some air above and below, so a flat line sits in the middle instead of on the edge.
        val span = ((ys.maxOrNull() ?: 1f) - (ys.minOrNull() ?: 0f)).let { if (it <= 0f) 1f else it }
        // Whole numbers with an even distance, so the middle mark is a whole number too.
        val lo = yMin ?: floor((ys.minOrNull() ?: 0f) - span * 0.15f)
        val hi = yMax?.let { if (it <= lo) lo + 1f else it }
            ?: ceil((ys.maxOrNull() ?: 1f) + span * 0.15f).let { if (it <= lo) lo + 2f else if ((it - lo).toInt() % 2 == 1) it + 1f else it }
        val marks = listOf(hi, (hi + lo) / 2, lo).map { measurer.measure(yLabel(it), axisStyle.copy(color = axis)) }
        val gutter = marks.maxOf { it.size.width } + 8.dp.toPx()
        val plotW = size.width - gutter
        fun py(v: Float) = chartH - (v - lo) / (hi - lo) * (chartH - 8.dp.toPx()) - 4.dp.toPx()
        fun px(x: Float) = 4.dp.toPx() + x * (plotW - 8.dp.toPx())

        bands.forEach { (from, to, c) ->
            val top = py(minOf(to, hi)); val bottom = py(maxOf(from, lo))
            if (bottom > top) drawRect(c, Offset(0f, top), Size(plotW, bottom - top))
        }
        for (k in 0..2) {
            val y = py(hi - (hi - lo) * k / 2f)
            drawLine(grid, Offset(0f, y), Offset(plotW, y), 1.dp.toPx())
            val t = marks[k]
            drawText(t, topLeft = Offset(size.width - t.size.width, (y - t.size.height / 2).coerceIn(0f, chartH - t.size.height)))
        }
        val path = Path()
        var open = false
        points.forEach { (x, y) ->
            if (y == null) { open = false; return@forEach }
            if (!open) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
            open = true
        }
        drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.lastOrNull { it.second != null }?.let { (x, y) -> drawCircle(color, 4.dp.toPx(), Offset(px(x), py(y!!))) }

        if (referenceY != null && referenceY in lo..hi) {
            val y = py(referenceY)
            drawLine(color.copy(alpha = 0.7f), Offset(0f, y), Offset(plotW, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            referenceLabel?.let {
                val layout = measurer.measure(it, axisStyle.copy(color = color))
                drawText(layout, topLeft = Offset(plotW - layout.size.width - 4.dp.toPx(), (y + 3.dp.toPx()).coerceAtMost(chartH - layout.size.height)))
            }
        }
        xLabels.forEach { (x, text) -> drawLabel(measurer, text, px(x).coerceIn(20.dp.toPx(), plotW - 20.dp.toPx()), size.height - labelH / 2, axis) }
    }
}

/** Tiny trend line for tiles. */
@Composable
fun Sparkline(values: List<Float?>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val ys = values.filterNotNull()
        if (ys.size < 2) return@Canvas
        val lo = ys.min(); val hi = ys.max().let { if (it == lo) lo + 1 else it }
        val path = Path()
        var open = false
        values.forEachIndexed { i, v ->
            if (v == null) { open = false; return@forEachIndexed }
            val x = i / (values.size - 1f) * size.width
            val y = size.height - (v - lo) / (hi - lo) * (size.height - 6.dp.toPx()) - 3.dp.toPx()
            if (!open) path.moveTo(x, y) else path.lineTo(x, y)
            open = true
        }
        drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** When you were asleep, one column per night. The scale fits the nights shown. */
@Composable
fun BedtimeChart(week: List<NightBar>, modifier: Modifier = Modifier) {
    val c = PulseTheme.colors
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    // Minutes are counted from 18:00. Round outwards to whole hours.
    val top = (week.mapNotNull { it.startMin }.minOrNull() ?: 240).floorDiv(60) * 60
    val bottom = -((-(week.mapNotNull { it.endMin }.maxOrNull() ?: 780)).floorDiv(60)) * 60
    fun clock(min: Int) = Fmt.clock(LocalTime.of(18, 0).plusMinutes(min.toLong())).replace(":00", "")
    Canvas(modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = "Sleep times of the last ${week.size} nights" }) {
        val labelH = 22.dp.toPx()
        val pad = 8.dp.toPx()
        val chartH = size.height - labelH - pad * 2
        val marks = listOf(top, (top + bottom) / 2, bottom).map { it to measurer.measure(clock(it), axisStyle.copy(color = axis)) }
        val left = marks.maxOf { it.second.size.width } + 10.dp.toPx()
        fun y(min: Int) = pad + (min - top).toFloat() / (bottom - top).coerceAtLeast(60) * chartH
        marks.forEach { (min, t) ->
            drawLine(c.track, Offset(left, y(min)), Offset(size.width, y(min)), 1.dp.toPx())
            drawText(t, topLeft = Offset(0f, y(min) - t.size.height / 2))
        }
        val slot = (size.width - left) / week.size.coerceAtLeast(1)
        val w = (slot * 0.5f).coerceAtMost(26.dp.toPx())
        week.forEachIndexed { i, n ->
            val x = left + i * slot + (slot - w) / 2
            if (n.startMin != null && n.endMin != null) {
                drawRoundRect(if (n.isLast) c.sleep else c.sleep.copy(alpha = 0.5f), Offset(x, y(n.startMin)), Size(w, (y(n.endMin) - y(n.startMin)).coerceAtLeast(4f)), CornerRadius(w / 2))
            }
            drawLabel(measurer, n.label, x + w / 2, size.height - labelH / 2, if (n.isLast) strong else axis)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer, text: String, centerX: Float, centerY: Float, color: Color,
) {
    val layout = measurer.measure(text, axisStyle.copy(color = color))
    // Never outside the chart.
    val x = (centerX - layout.size.width / 2).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(x, centerY - layout.size.height / 2))
}
