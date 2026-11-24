package com.fitbittrack.pulse.ui.widget.art

import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.widget.art.Art.Companion.pt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Steps, distance, calories, floors and zone minutes. */
internal object ArtMove {

    private fun left(d: ArtData): String = d.steps?.let { (d.stepGoal - it).coerceAtLeast(0) }.let {
        when (it) { null -> "No steps yet"; 0L -> "Goal reached"; else -> "${Fmt.count(it)} to go" }
    }

    fun trail(a: Art, d: ArtData) {
        a.ground(0f to "#0B2B38", 0.55f to "#1C5C66", 1f to "#E9B16B")
        val points = listOf(40f to 358f, 150f to 322f, 240f to 340f, 340f to 272f, 430f to 286f, 520f to 208f, 610f to 222f, 690f to 152f, 770f to 88f)
        val p = d.part(GoalMetric.Steps)
        val (i, end) = ArtLogic.along(points, p)
        fun line(l: List<Pair<Float, Float>>) = "M" + l.joinToString("L") { pt(it.first, it.second) }
        a.scene {
            a.circle(640f, 120f, 66f, "#FFD98F", 0.28f)
            a.circle(640f, 120f, 46f, "#FFD98F")
            a.fill("M0 320L120 270L210 300L330 220L430 290L560 180L650 250L760 190L880 260V420H0Z", "#2F6E72")
            a.fill("M0 372L150 322L240 340L340 272L430 286L520 208L610 222L690 152L770 88L880 190V420H0Z", "#0E3A44")
            a.fill("M770 88L722 126L748 138L764 128L790 146L812 128L880 190Z", "#F4F8F7")
            a.fill("M0 420C200 360 460 400 620 378S820 360 880 372V420Z", "#082630")
            if (p < 1f) a.stroke(line(listOf(end) + points.drop(i + 1)), "#FFFFFF", 6f, dash = floatArrayOf(2f, 16f))
            if (p > 0f) a.stroke(line(points.take(i + 1) + end), "#FFC24A", 10f)
            a.stroke("M770 88V34", "#FFFFFF", 6f)
            a.fill("M770 34L818 49L770 64Z", "#FF5C74")
            a.circle(end.first, end.second, 30f, "#FFC24A", 0.3f)
            a.circle(end.first, end.second, 21f, "#FFC24A")
            a.circle(end.first, end.second, 13f, "#FFFFFF")
        }
        a.text(Fmt.count(d.steps), 52f, 38f, 92f, "#FFFFFF", 104f, 700, max = 400f, spacing = -3f)
        a.text("of ${Fmt.count(d.stepGoal)} steps", 52f, 144f, 28f, "#FFFFFF", 34f, 500)
        a.at(a.ex, a.ey) {
            val words = when { d.steps == null -> "No steps yet"; p >= 1f -> "You made the summit"; else -> "${Fmt.count(d.stepGoal - (d.steps ?: 0))} to the summit" }
            a.text(words, 828f, 362f, 28f, "#FFFFFF", 34f, 600, Align.End)
        }
    }

    fun bigFill(a: Art, d: ArtData) {
        a.ground("#F5A524")
        val number = Fmt.count(d.steps)
        val part = d.part(GoalMetric.Steps)
        a.text("Steps today", 52f, 42f, 30f, "#2A1800")
        a.right { a.text(d.percent(GoalMetric.Steps), 828f, 42f, 30f, "#2A1800", align = Align.End) }
        a.mid {
            val width = a.textWidth(number, 254f, 800, -10f).coerceAtMost(776f)
            a.text(number, 48f, 82f, 254f, "#A05C00", 254f, 800, max = 776f, spacing = -10f)
            a.clip("M0 0H${48 + width * part}V420H0Z") { a.text(number, 48f, 82f, 254f, "#2A1800", 254f, 800, max = 776f, spacing = -10f) }
        }
        a.bottom { a.text(left(d), 52f, 348f, 30f, "#2A1800") }
        a.at(a.ex, a.ey) { a.text("Goal ${Fmt.count(d.stepGoal)}", 828f, 348f, 30f, "#2A1800", align = Align.End) }
    }

    fun stepCity(a: Art, d: ArtData) {
        a.ground(0f to "#131A3E", 0.7f to "#3A2C6B", 1f to "#A8577E")
        val hour = d.now.hour
        val hours = List(24) { d.hourly.getOrNull(it) }
        val most = hours.filterNotNull().maxOrNull()?.takeIf { it > 0 }
        a.right {
            a.circle(740f, 96f, 40f, "#FBEFCB")
            a.circle(758f, 84f, 36f, "#1E2350")
        }
        a.scene {
            for (i in 0 until 24) {
                val b = 30f + (i * 53) % 70
                a.fill("M${52 + i * 32.6f - 10} 350V${350 - b}h40V350Z", "#33296A", 0.7f)
            }
            for (i in 0 until 24) {
                val x = 52 + i * 32.6f
                if (i > hour) {
                    if (i < 23) a.stroke("M$x 350V${350 - 26 - (i * 37) % 40}h28V350", "#FFFFFF", 3f, 0.5f, floatArrayOf(4f, 7f), round = false)
                    continue
                }
                val v = hours[i] ?: 0
                if (v == 0L || most == null) continue
                val now = i == hour
                val bh = (v * 165f / most).coerceAtLeast(14f)
                val y = 350 - bh
                a.fill("M$x 350V${y}h28V350Z", if (now) "#FFB02E" else "#0A0E24")
                if (v == most) a.fill("M${x + 11} ${y}v-16h6v16Z", if (now) "#FFB02E" else "#0A0E24")
                var r = 0
                while (y + 26 + r * 20 < 346) {
                    for (c in 0..1) if ((i * 7 + r * 5 + c * 3) % 4 != 0) a.fill("M${x + 5 + c * 11} ${y + 12 + r * 20}h7v10h-7Z", if (now) "#7A3E00" else "#FFCF6E")
                    r++
                }
            }
            a.rect(0f, 350f, 880f, 71f, "#0A0E24")
        }
        a.text(Fmt.count(d.steps), 52f, 36f, 80f, "#FFFFFF", 90f, 700, spacing = -2.5f)
        val best = hours.indexOfFirst { it != null && it == most }.takeIf { it >= 0 }
        a.text(if (best == null) "steps today" else "steps, busiest at ${hourName(best)}", 52f, 128f, 28f, "#FFFFFF", 34f, 500)
        a.bottom {
            listOf("12 AM", "6 AM", "Noon", "6 PM", "12 AM").forEachIndexed { i, s ->
                val x = 52 + (776 + a.ex) * i / 4f
                a.text(s, x, 366f, 26f, "#C9CDEB", 32f, 500, if (i == 0) Align.Start else if (i == 4) Align.End else Align.Center)
            }
        }
    }

    private fun hourName(h: Int) = when { h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "noon"; else -> "${h - 12} PM" }

    fun stairs(a: Art, d: ArtData) {
        a.ground("#FF8069")
        val g = d.goals[GoalMetric.Floors]
        val climbed = (g.value ?: 0.0).toInt()
        // Ten steps stand for the whole goal, whatever the goal is.
        val n = if (g.value == null) 0 else (d.part(GoalMetric.Floors) * 10).toInt().coerceAtLeast(if (climbed > 0) 1 else 0)
        a.scene {
            for (i in 0 until 10) {
                val y = 396 - i * 26
                a.fill("M${i * 42} 421V${y}h42.5V421Z", if (i < n) "#3D0B06" else "#F0644C")
                if (i < n) a.stroke("M${i * 42 + 6} ${y}h30", "#FFE3A8", 8f)
            }
            if (n > 0) {
                val bx = minOf((n - 1) * 42 + 21f, 384f)
                val by = 396 - (n - 1) * 26 - 34f
                a.circle(bx, by, 26f, "#FFFFFF")
                if (n < 10) a.stroke("M${bx + 10} ${by - 32}Q${bx + 34} ${by - 66} ${bx + 48} ${by - 34}", "#FFFFFF", 5f, dash = floatArrayOf(2f, 12f))
            }
        }
        a.text(Fmt.whole(g.value), 40f, 28f, 116f, "#3D0B06", 122f, 800, max = 190f, spacing = -4f)
        a.text("of ${g.target.toInt()} floors", 40f, 150f, 28f, "#3D0B06", 34f, 600)
    }

    private const val FLAME = "M214 22C262 92 366 150 366 262C366 350 298 402 210 402C122 402 54 350 54 262C54 198 94 170 110 118C138 150 148 162 166 138C184 112 190 70 214 22Z"

    fun flame(a: Art, d: ArtData) {
        a.ground("#1C0E0A")
        val part = d.part(GoalMetric.Calories)
        val y = 402 - 380 * part
        val fire = "M0 ${y}C50 ${y - 24} 90 ${y + 20} 140 ${y}S230 ${y - 24} 280 ${y}S370 ${y + 20} 420 ${y - 4}V440H0Z"
        a.mid {
            a.fill(FLAME, "#43200F")
            if (part > 0) a.clip(FLAME) {
                a.fill(fire, a.vertical(402f, 110f, 0f to "#FFD257", 0.55f to "#FF9A3D", 1f to "#FF5A36"))
                a.fill("M0 ${y + 30}C60 ${y + 50} 100 ${y + 6} 160 ${y + 30}S270 ${y + 52} 320 ${y + 26}S390 ${y + 22} 420 ${y + 34}V440H0Z", "#FFD257", 0.35f)
            }
            a.dots(listOf(324f to 66f, 352f to 112f, 74f to 84f, 300f to 34f, 48f to 138f), "#FF9A3D", 10f)
            fun words(ink: String) {
                a.text(Fmt.whole(d.goals[GoalMetric.Calories].value), 210f, 218f, 84f, ink, 92f, 800, Align.Center, 270f, -3f)
                a.text("of ${Fmt.count(d.goals[GoalMetric.Calories].target.toInt())} kcal", 210f, 308f, 28f, ink, 34f, 600, Align.Center, 260f)
            }
            // Light words on the unlit flame, dark words on the fire.
            words("#FFE2C4")
            if (part > 0) a.clip(fire) { words("#2A0D02") }
        }
    }

    fun road(a: Art, d: ArtData) {
        a.ground("#C9EBDD")
        val g = d.goals[GoalMetric.Distance]
        val f = d.part(GoalMetric.Distance)
        fun p(u: Float) = 240 + 600 * u to 104 + 42 * sin(u * PI.toFloat() * 3.2f + 0.4f)
        fun run(to: Float) = (0..60).joinToString("L", "M") { val q = p(to * it / 60); pt(q.first, q.second) }
        a.scene {
            a.fill("M250 190C330 120 420 170 520 130S760 140 880 100V191H250Z", "#B3DECB")
            a.stroke(run(1f), "#173F3A", 46f)
            a.stroke(run(1f), "#FFFFFF", 4f, 0.6f, floatArrayOf(16f, 16f), round = false)
            if (f > 0) a.stroke(run(f), "#FFC24A", 18f)
            listOf(330 to 40, 372 to 30, 470 to 182, 520 to 176, 610 to 40, 662 to 52, 770 to 178).forEach { a.fill("M${it.first - 15} ${it.second}l15 -32l15 32Z", "#2E8B6A") }
            val (x, y) = p(f)
            a.fill("M${x} ${y}c-8 -16 -24 -28 -24 -48a24 24 0 0 1 48 0c0 20 -16 32 -24 48Z", "#E03A57")
            a.circle(x, y - 48, 10f, "#FFFFFF")
            a.stroke("M846 66V22", "#0B3B33", 5f)
            a.fill("M846 22l-34 11l34 11Z", "#0E9E8E")
        }
        a.mid {
            val (value, unit) = Fmt.distance(g.value, d.units)
            a.text(value, 52 - a.ex / 2, 34f, 72f, "#0B3B33", 80f, 700, max = 170f, spacing = -2f)
            a.text("of ${Fmt.distance(g.target, d.units).first.removeSuffix(".0")} $unit", 52 - a.ex / 2, 116f, 28f, "#0B3B33", 34f, 600)
        }
    }

    fun race(a: Art, d: ArtData) {
        a.ground("#B8402E")
        val before = ArtLogic.yesterdayByNow(d.yesterdayHourly, d.now.toLocalTime())
        val steps = d.steps
        val goal = maxOf(d.stepGoal.toLong(), steps ?: 0, before ?: 0).coerceAtLeast(1)
        fun x(v: Long) = 322 + 440f * v / goal
        val lanes = a.h / 190f
        a.scene {
            a.rect(262f, -a.ey, 620f, 191 + a.ey, "#C64D38")
            a.at(0f, -a.ey) { a.pulled(1f, lanes) { laneArt(a) } }
            // Each runner keeps to the middle of its lane.
            val top = 52 * lanes - a.ey
            val low = 138 * lanes - a.ey
            if (steps != null) {
                val sx = x(steps)
                if (sx > 420) a.stroke("M300 ${top}H${sx - 40}M330 ${top - 18}H${sx - 60}M330 ${top + 18}H${sx - 60}", "#FFD257", 7f)
                a.circle(sx, top, 32f, "#FFD257")
            }
            if (before != null) {
                val bx = x(before)
                if (bx > 380) a.stroke("M310 ${low}H${bx - 44}", "#FFFFFF", 6f, 0.85f, floatArrayOf(2f, 16f))
                a.circle(bx, low, 33f, "#FFFFFF")
                a.circle(bx, low, 28f, "#7A2317")
            }
        }
        a.mid {
            val diff = if (steps != null && before != null) steps - before else null
            val x = 44 - a.ex / 2
            a.text(diff?.let { (if (it >= 0) "+" else "−") + Fmt.count(kotlin.math.abs(it)) } ?: Fmt.count(steps), x, 26f, 60f, "#FFFFFF", 68f, 700, max = 205f, spacing = -2f)
            a.text(if (diff == null) "steps today" else if (diff >= 0) "steps ahead of" else "steps behind", x, 96f, 27f, "#FFFFFF", 32f, 600)
            if (diff != null) a.text("yesterday", x, 128f, 27f, "#FFFFFF", 32f, 600)
        }
    }

    private fun laneArt(a: Art) {
        a.stroke("M262 95H880", "#FFFFFF", 5f, 0.75f, round = false)
        a.stroke("M262 8H880M262 182H880", "#FFFFFF", 5f, 0.75f, round = false)
        for (i in 0 until 10) a.fill("M${if (i % 2 == 0) 800 else 820} ${i * 19}h20v19h-20Z", "#FFFFFF")
    }

    fun stepWave(a: Art, d: ArtData) {
        a.ground("#2B1D08")
        val f = d.part(GoalMetric.Steps)
        val x = 420 * f
        a.scene {
            a.at(0f, -a.ey) { a.pulled(1f, a.h / 190f) {
                if (f > 0) {
                    a.fill("M0 0H${x + 26}C${x + 50} 40 ${x + 6} 70 ${x + 30} 110S${x + 12} 160 ${x + 22} 190H0Z", "#8A5A0C")
                    a.fill("M0 0H${x}C${x + 26} 36 ${x - 14} 66 ${x + 8} 104S${x - 12} 156 ${x + 4} 190H0Z", "#F5A524")
                    if (f > 0.5f) a.dots(listOf(x - 60 to 40f, x - 40 to 140f, x - 74 to 96f), "#FFD27A", 12f)
                }
            } }
        }
        a.mid {
            val number = Fmt.count(d.steps)
            fun words(ink: String) {
                a.text(number, 40f, 36f, 72f, ink, 80f, 800, max = 240f, spacing = -3f)
                a.text("steps", 40f, 116f, 28f, ink, 34f, 600)
            }
            // Dark on the wave, light where it has not reached yet.
            words("#FFD27A")
            if (f > 0) a.clip("M0 -200H${x}V400H0Z") { words("#2A1800") }
            if (f < 0.78f) a.text(d.percent(GoalMetric.Steps), 392f, 78f, 30f, "#FFD27A", 36f, 700, Align.End)
        }
    }

    fun fuse(a: Art, d: ArtData) {
        a.ground("#14210A")
        val total = d.zoneWeek
        val f = ((total ?: 0) / d.zoneWeekGoal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        fun p(u: Float) = 330 + 452 * u to 112 + 30 * sin(u * 13)
        fun run(from: Float, to: Float) = (0..50).joinToString("L", "M") { val q = p(from + (to - from) * it / 50); pt(q.first, q.second) }
        val done = f >= 1f
        a.at(a.ex, a.ey / 2) {
            if (f > 0.02f) a.stroke(run(0f, minOf(f, 0.94f)), "#4E6134", 7f, dash = floatArrayOf(2f, 14f))
            if (!done) {
                a.stroke(run(minOf(f, 0.93f), 0.94f) + "L782 142", "#B4E070", 11f)
                val (sx, sy) = p(minOf(f, 0.93f))
                a.circle(sx, sy, 40f, "#FFC24A", 0.2f)
                for (k in 0 until 9) {
                    val t = k / 9f * 2 * PI.toFloat()
                    val r = if (k % 2 == 0) 26f else 34f
                    a.stroke("M${pt(sx + 18 * cos(t), sy + 18 * sin(t))}L${pt(sx + r * cos(t), sy + r * sin(t))}", "#FFC24A", 6f)
                }
                a.circle(sx, sy, 12f, "#FFFFFF")
                a.fill("M754 66h56v76h-56Z", "#E03A57")
                a.fill("M742 70L782 18L822 70Z", "#FFC24A")
                a.fill("M754 92h56v18h-56Z", "#FFFFFF")
                a.stroke("M838 34l10 -14M848 56l18 -4M726 34l-10 -14M716 56l-16 -4", "#4E6134", 6f)
            } else {
                // The firework went off.
                listOf(Triple(760f, 84f, "#FFC24A"), Triple(630f, 64f, "#FF5C74"), Triple(700f, 130f, "#8E9BFF")).forEachIndexed { n, (x, y, color) ->
                    for (k in 0 until 14) {
                        val t = k / 14f * 2 * PI.toFloat() + n
                        val r = if (k % 2 == 0) 54f else 38f
                        a.stroke("M${pt(x + 16 * cos(t), y + 16 * sin(t))}L${pt(x + r * cos(t), y + r * sin(t))}", color, 6f)
                    }
                    a.circle(x, y, 7f, "#FFFFFF")
                }
            }
        }
        a.mid {
            a.text(Fmt.count(total), 52 - a.ex / 2, 34f, 72f, "#FFFFFF", 80f, 700, max = 220f, spacing = -2f)
            a.text("of ${d.zoneWeekGoal} zone min", 52 - a.ex / 2, 116f, 28f, "#FFFFFF", 34f, 500, max = 260f)
        }
    }
}
