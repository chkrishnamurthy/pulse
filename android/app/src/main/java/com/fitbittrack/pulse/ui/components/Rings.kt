package com.fitbittrack.pulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.goals.GoalProgress
import com.fitbittrack.pulse.ui.model.Tone
import com.fitbittrack.pulse.ui.theme.PulseColors
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** One goal as a closing ring. */
@Composable
fun ProgressRing(fraction: Float?, color: Color, modifier: Modifier = Modifier, stroke: Dp = 5.dp) {
    val track = PulseTheme.colors.track
    Canvas(modifier) {
        val w = stroke.toPx()
        val box = Size(size.minDimension - w, size.minDimension - w)
        val at = Offset((size.width - box.width) / 2, (size.height - box.height) / 2)
        drawArc(track, 0f, 360f, false, at, box, style = Stroke(w))
        val f = (fraction ?: 0f).coerceIn(0f, 1f)
        if (f > 0.01f) drawArc(color, -90f, 360f * f, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Several goals in one ring: one arc each, filling on its own. A reached goal is bright, one on the way is dimmer. */
@Composable
fun GoalRing(goals: List<GoalProgress>, modifier: Modifier = Modifier, stroke: Dp = 5.dp) {
    val c = PulseTheme.colors
    Canvas(modifier) {
        val w = stroke.toPx()
        val box = Size(size.minDimension - w, size.minDimension - w)
        val at = Offset((size.width - box.width) / 2, (size.height - box.height) / 2)
        val slot = 360f / goals.size.coerceAtLeast(1)
        // Room for the round ends, so neighbours never touch.
        val gap = Math.toDegrees((w * 1.7 / (box.width / 2))).toFloat()
        goals.forEachIndexed { i, g ->
            val start = -90f + i * slot + gap / 2
            val sweep = slot - gap
            drawArc(c.track, start, sweep, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
            val f = (g.fraction ?: 0f).coerceIn(0f, 1f)
            if (f > 0.02f) drawArc(c.fill(g.metric.tone).copy(alpha = if (g.met) 1f else 0.42f), start, sweep * f, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

val GoalMetric.tone: Tone
    get() = when (this) {
        GoalMetric.Steps -> Tone.Move
        GoalMetric.Sleep -> Tone.Sleep
        GoalMetric.Calories -> Tone.Energy
        GoalMetric.Distance -> Tone.Body
        GoalMetric.Zone -> Tone.Active
        GoalMetric.Floors -> Tone.Body
    }

val GoalMetric.icon
    get() = when (this) {
        GoalMetric.Steps -> PulseIcons.Steps
        GoalMetric.Sleep -> PulseIcons.Sleep
        GoalMetric.Calories -> PulseIcons.Flame
        GoalMetric.Distance -> PulseIcons.Distance
        GoalMetric.Zone -> PulseIcons.Bolt
        GoalMetric.Floors -> PulseIcons.Floors
    }

/** For bars, rings and icons. */
fun PulseColors.fill(t: Tone): Color = when (t) {
    Tone.Move -> move
    Tone.Energy -> energy
    Tone.Active -> active
    Tone.Heart -> heart
    Tone.Sleep -> sleep
    Tone.Body -> body
}

/** For small text. */
fun PulseColors.label(t: Tone): Color = when (t) {
    Tone.Move -> moveLabel
    Tone.Energy -> energyLabel
    Tone.Active -> activeLabel
    Tone.Heart -> heartLabel
    Tone.Sleep -> sleepLabel
    Tone.Body -> bodyLabel
}
