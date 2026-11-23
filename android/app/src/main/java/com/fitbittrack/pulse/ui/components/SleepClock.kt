package com.fitbittrack.pulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.SleepUi
import com.fitbittrack.pulse.ui.model.Stage
import com.fitbittrack.pulse.ui.model.StageBlock
import com.fitbittrack.pulse.ui.theme.PulseColors
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One coloured piece of the clock ring. Degrees as `drawArc` wants them: 0 is 3 o'clock, clockwise. */
data class ClockArc(val stage: Stage, val startDegrees: Float, val sweepDegrees: Float)

/** A normal 12-hour face. Longer nights would lap themselves, so they get a 24-hour face. */
fun clockFaceHours(totalMinutes: Int): Int = if (totalMinutes > 12 * 60) 24 else 12

/** Where each stage block sits on the clock face. */
fun clockArcs(start: LocalTime, blocks: List<StageBlock>, faceHours: Int): List<ClockArc> {
    val degreesPerMinute = 360f / (faceHours * 60)
    val startMinute = start.hour * 60 + start.minute
    // Back-to-back blocks of the same stage are one piece.
    val joined = blocks.filter { it.endMinute > it.startMinute }.fold(emptyList<StageBlock>()) { done, b ->
        val last = done.lastOrNull()
        if (last != null && last.stage == b.stage && last.endMinute == b.startMinute) done.dropLast(1) + last.copy(endMinute = b.endMinute) else done + b
    }
    return joined.map { b ->
        ClockArc(
            stage = b.stage,
            startDegrees = ((startMinute + b.startMinute) * degreesPerMinute) % 360f - 90f,
            sweepDegrees = (b.endMinute - b.startMinute) * degreesPerMinute,
        )
    }
}

/** The night on a clock face: one ring, one colour per stage, with the time asleep in the middle. */
@Composable
fun SleepClock(sleep: SleepUi, start: LocalTime, modifier: Modifier = Modifier) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val numberStyle = MaterialTheme.typography.labelMedium.copy(color = muted)
    val mainNumberStyle = MaterialTheme.typography.titleSmall.copy(color = scheme.onSurface)
    val hours = clockFaceHours(sleep.totalMinutesInBed)
    // Some nights have no stages: show them as one plain arc.
    val arcs = clockArcs(start, sleep.blocks.ifEmpty { listOf(StageBlock(Stage.Light, 0, sleep.totalMinutesInBed)) }, hours)
    val description = "Sleep clock. Asleep ${Fmt.minutes(sleep.minutesAsleep)}, from ${Fmt.clock(sleep.start)} to ${Fmt.clock(sleep.end)}."

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val dial = minOf(maxWidth - 44.dp, 340.dp)
        // The extra height leaves the corners free for the bed and wake times.
        Box(Modifier.widthIn(max = 384.dp).fillMaxWidth().height(dial + 96.dp)) {
            Box(Modifier.align(Alignment.Center).size(dial), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(dial).semantics { contentDescription = description }) {
                    val ring = size.minDimension * 0.12f
                    val box = Size(size.width - ring, size.height - ring)
                    val at = Offset(ring / 2, ring / 2)
                    drawArc(c.track, 0f, 360f, false, at, box, style = Stroke(ring))
                    arcs.forEach { a ->
                        // A thin gap between neighbours, but never so wide that a short stage disappears.
                        val gap = minOf(0.8f, a.sweepDegrees * 0.4f)
                        drawArc(stageColor(a.stage, c), a.startDegrees + gap / 2, a.sweepDegrees - gap, false, at, box, style = Stroke(ring))
                    }
                    val inner = size.minDimension / 2 - ring
                    fun point(radius: Float, twelfth: Int): Offset {
                        val angle = twelfth * PI / 6
                        return Offset(center.x + radius * sin(angle).toFloat(), center.y - radius * cos(angle).toFloat())
                    }
                    for (i in 0 until 12) {
                        drawLine(scheme.outline, point(inner - 7.dp.toPx(), i), point(inner - 4.dp.toPx(), i), 2.dp.toPx(), StrokeCap.Round)
                        val number = if (hours == 24) i * 2 else if (i == 0) 12 else i
                        val text = measurer.measure(number.toString(), if (i % 3 == 0) mainNumberStyle else numberStyle)
                        val p = point(inner - 11.dp.toPx() - maxOf(text.size.width, text.size.height) / 2f, i)
                        drawText(text, topLeft = Offset(p.x - text.size.width / 2, p.y - text.size.height / 2))
                    }
                }
                Column(Modifier.width(dial * 0.48f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FitText("You slept", MaterialTheme.typography.bodyMedium, muted)
                    FitText(Fmt.minutes(sleep.minutesAsleep), MaterialTheme.typography.displayLarge.copy(fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1).sp), scheme.onSurface)
                    FitText("Goal ${Fmt.minutes(sleep.sleepGoalMinutes)}", MaterialTheme.typography.bodyMedium, muted)
                }
            }
            ClockTime(PulseIcons.Sleep, c.sleep, Fmt.clock(sleep.start), "Went to bed", Modifier.align(Alignment.TopStart))
            ClockTime(PulseIcons.Sun, c.move, Fmt.clock(sleep.end), "Got up", Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun ClockTime(icon: ImageVector, tint: Color, time: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(time, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** How long one stage lasted, and its share of the night. */
@Composable
fun RowScope.StageTile(name: String, minutes: Int?, totalMinutes: Int, color: Color) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Space.card)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        BigValue(Fmt.minutes(minutes), null, 26)
        ProgressBar((minutes ?: 0).toFloat() / totalMinutes.coerceAtLeast(1), color)
    }
}

fun stageColor(stage: Stage, c: PulseColors): Color = when (stage) {
    Stage.Awake -> c.sleepAwake
    Stage.Rem -> c.sleepRem
    Stage.Light -> c.sleepLight
    Stage.Deep -> c.sleepDeep
}
