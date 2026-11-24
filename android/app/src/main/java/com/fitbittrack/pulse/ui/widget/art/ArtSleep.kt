package com.fitbittrack.pulse.ui.widget.art

import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.model.Stage
import com.fitbittrack.pulse.ui.widget.art.Art.Companion.pt
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Last night, the week's nights, and bedtime. */
internal object ArtSleep {

    /** The lit part of a moon, [f] 0..1 full. */
    fun lit(cx: Float, cy: Float, r: Float, f: Float): String {
        val rx = max(abs(2 * f - 1) * r, 0.1f)
        return "M$cx ${cy - r}A$r $r 0 0 1 $cx ${cy + r}A$rx $r 0 0 ${if (f >= 0.5f) 1 else 0} $cx ${cy - r}Z"
    }

    fun deepDive(a: Art, d: ArtData) {
        a.ground("#F7D9C4")
        a.scene {
            a.circle(800f, 132f, 58f, "#FF8C6B")
            fun wave(y: Int, color: String) = a.fill("M0 ${y}C110 ${y - 14} 220 ${y + 14} 330 ${y}S550 ${y - 14} 660 ${y}S800 ${y + 14} 880 ${y}V421H0Z", color)
            wave(136, "#1E7FA0"); wave(192, "#176A93"); wave(254, "#114C7E"); wave(324, "#092A58")
            val blocks = d.blocks
            val total = blocks.lastOrNull()?.endMinute ?: 0
            if (total > 0) {
                fun y(s: Stage) = when (s) { Stage.Awake -> 160f; Stage.Rem -> 222f; Stage.Light -> 290f; Stage.Deep -> 372f }
                fun x(min: Int) = 52 + 660f * min / total
                val line = StringBuilder("M${pt(52f, y(blocks[0].stage))}")
                val bubbles = mutableListOf<Pair<Float, Float>>()
                blocks.forEachIndexed { i, b ->
                    val (xs, xe, yy) = Triple(x(b.startMinute), x(b.endMinute), y(b.stage))
                    // Short stages get a short turn, so the line never runs backwards.
                    val turn = minOf(12f, (xe - xs) / 2)
                    if (i > 0) line.append("C${pt(xs, y(blocks[i - 1].stage))} ${pt(xs, yy)} ${pt(xs + turn, yy)}")
                    line.append("L${pt(max(xe - turn, xs + turn), yy)}")
                    if (b.stage == Stage.Deep && b.endMinute - b.startMinute >= 15) { val m = (xs + xe) / 2; bubbles += listOf(m to yy - 42, m + 18 to yy - 70, m - 10 to yy - 104) }
                }
                val endX = x(total) + 14
                line.append("C${pt(endX - 8, y(blocks.last().stage))} ${pt(endX - 8, 160f)} ${pt(endX, 160f)}")
                a.stroke(line.toString(), "#9BE8FF", 26f, 0.16f)
                a.stroke(line.toString(), "#FFFFFF", 8f)
                a.dots(bubbles, "#9BE8FF", 10f, 0.6f)
                a.circle(endX, 160f, 16f, "#FFFFFF")
                a.circle(endX, 160f, 11f, "#FFB25A")
            }
        }
        a.at(a.ex, a.ey) {
            listOf("Awake" to 148f, "REM" to 208f, "Light" to 274f, "Deep" to 352f).forEach { (s, y) -> a.text(s, 836f, y, 26f, "#FFFFFF", 32f, 600, Align.End) }
        }
        val w = a.text(if (d.sleepMinutes == null) "No sleep yet" else Fmt.minutes(d.sleepMinutes), 52f, 30f, 72f, "#0B2440", 84f, 700, spacing = -2f)
        ArtLogic.deepMinutes(d.blocks)?.let { a.text("asleep, ${ArtLogic.short(it)} deep", 52 + w + 20, 62f, 28f, "#0B2440", 34f, 600) }
    }

    private val MOON_STARS = listOf(48f to 62f, 92f to 300f, 372f to 58f, 346f to 120f, 60f to 190f, 378f to 250f, 330f to 284f, 36f to 262f, 110f to 40f, 304f to 36f)

    fun moon(a: Art, d: ArtData) {
        a.ground(0f to "#0B0F2E", 1f to "#2A2A70")
        val f = d.part(GoalMetric.Sleep)
        a.mid {
            a.dots(MOON_STARS, "#FFFFFF", 6f, 0.75f)
            a.circle(210f, 158f, 146f, "#FFF3CF", 0.06f)
            a.circle(210f, 158f, 128f, "#FFF3CF", 0.09f)
            a.circle(210f, 158f, 108f, "#3B3C86")
            if (f > 0.01f) {
                val shape = lit(210f, 158f, 108f, f)
                a.fill(shape, "#FBEFCB")
                a.clip(shape) {
                    listOf(Triple(175f, 118f, 24f), Triple(248f, 102f, 13f), Triple(256f, 176f, 32f), Triple(186f, 206f, 16f), Triple(146f, 172f, 10f), Triple(222f, 244f, 12f))
                        .forEach { (x, y, r) -> a.circle(x, y, r, "#E6D4A0") }
                }
            }
            a.text(if (d.sleepMinutes == null) "No sleep yet" else Fmt.minutes(d.sleepMinutes), 210f, 284f, 56f, "#FFFFFF", 64f, 700, Align.Center, 370f, -1.5f)
            a.text(if (d.sleepMinutes == null) "Sync your band" else "${d.percent(GoalMetric.Sleep)} of your goal", 210f, 350f, 28f, "#FFFFFF", 34f, 500, Align.Center)
        }
    }

    fun sleepClock(a: Art, d: ArtData) {
        a.ground("#111722")
        a.mid {
            fun p(min: Float, r: Float): String { val t = min / 720 * 2 * PI.toFloat() - PI.toFloat() / 2; return pt(210 + r * cos(t), 210 + r * sin(t)) }
            a.ring(210f, 210f, 164f, "#1E2735", 40f)
            val start = d.sleepStart?.let { it.hour * 60 + it.minute }
            if (start != null) {
                val colors = mapOf(Stage.Awake to "#FFA23E", Stage.Rem to "#E35FB5", Stage.Light to "#4FC3F7", Stage.Deep to "#6257E8")
                d.blocks.filter { it.startMinute < 715 }.forEach { b ->
                    val (f, t) = (start + b.startMinute).toFloat() to (start + minOf(b.endMinute, 715) - 0.8f)
                    if (t > f) a.stroke("M${p(f, 164f)}A164 164 0 ${if (t - f > 360) 1 else 0} 1 ${p(t, 164f)}", colors.getValue(b.stage), 40f, round = false)
                }
            }
            for (h in 1 until 12) a.stroke("M${p(h * 60f, 130f)}L${p(h * 60f, if (h % 3 == 0) 120f else 126f)}", "#66738A", 5f)
            a.text("12", 210f, 90f, 26f, "#B4BDCC", 32f, 600, Align.Center)
            a.text(if (d.sleepMinutes == null) "No sleep" else Fmt.minutes(d.sleepMinutes), 210f, 168f, 52f, "#FFFFFF", 60f, 700, Align.Center, 220f, -2f)
            a.text(d.sleepEnd?.let { "up ${Fmt.clock(it)}" } ?: "yet", 210f, 230f, 27f, "#B4BDCC", 33f, 500, Align.Center)
        }
    }

    fun hourglass(a: Art, d: ArtData) {
        a.ground("#D9D6FF")
        val left = ArtLogic.minutesToBed(d.now.toLocalTime(), d.bedtime)
        // The top holds the waking day, about 16 hours.
        val full = (left / 960f).coerceIn(0f, 1f)
        val glass = "M232 62H384C384 150 330 168 322 210C330 252 384 270 384 358H232C232 270 286 252 294 210C286 168 232 150 232 62Z"
        a.mid {
            a.fill(glass, "#F1F0FF")
            a.clip(glass) {
                if (left > 0) {
                    val top = 206 - 132 * full
                    a.fill("M220 ${top}C270 ${top - 8} 346 ${top + 8} 396 ${top - 2}V210H220Z", "#5462E0")
                    a.fill("M305 198h6v150h-6Z", "#5462E0")
                }
                val rise = 352 - 92 * (1 - full)
                a.fill("M220 360V${352 - 30 * (1 - full)}C260 ${rise + 10} 290 $rise 308 ${rise - 8}C326 $rise 356 ${rise + 10} 396 ${352 - 30 * (1 - full)}V360Z", "#5462E0")
            }
            a.stroke(glass, "#211A5C", 8f)
            a.stroke("M220 56H396M220 364H396", "#211A5C", 20f)
            if (left == 0) {
                a.text("Time", 36f, 84f, 72f, "#211A5C", 78f, 700, max = 186f, spacing = -3f)
                a.text("for bed", 36f, 164f, 44f, "#211A5C", 54f, 700, max = 186f)
            } else {
                a.text("Bed in", 36f, 44f, 28f, "#211A5C", 34f, 600)
                val big = if (left >= 60) listOf("${left / 60}h", "${left % 60}m") else listOf("$left", "min")
                a.text(big[0], 36f, 84f, 80f, "#211A5C", 78f, 700, max = 180f, spacing = -3f)
                a.text(big[1], 36f, 162f, 80f, "#211A5C", 78f, 700, max = 180f, spacing = -3f)
            }
            a.text("at ${Fmt.clock(d.bedtime)}", 36f, 340f, 28f, "#211A5C", 34f, 600, max = 176f)
        }
    }

    fun sevenMoons(a: Art, d: ArtData) {
        a.ground("#171A4A")
        val letter = DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())
        a.at(0f, a.ey / 2) {
            d.nights7.forEachIndexed { i, (date, min) ->
                val cx = 324 + a.ex * 0.5f + i * (82 + a.ex / 14)
                a.circle(cx, 78f, 33f, "#34387F")
                val f = ((min ?: 0) / d.sleepGoal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                if (f > 0.01f) a.fill(lit(cx, 78f, 33f, f), "#FBEFCB")
                val last = i == d.nights7.lastIndex
                if (last) a.ring(cx, 78f, 44f, "#FFC24A", 4f)
                a.text(date.format(letter), cx, 132f, 27f, if (last) "#FFC24A" else "#FFFFFF", 33f, 600, Align.Center)
            }
            val avg = d.nights7.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            a.text(Fmt.minutes(avg), 52f, 36f, 56f, "#FFFFFF", 66f, 700, max = 220f, spacing = -2f)
            a.text("week average", 52f, 102f, 27f, "#FFFFFF", 33f, 500)
        }
    }
}
