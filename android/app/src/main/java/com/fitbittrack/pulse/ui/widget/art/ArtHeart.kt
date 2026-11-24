package com.fitbittrack.pulse.ui.widget.art

import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.HeartPoint
import com.fitbittrack.pulse.ui.widget.art.Art.Companion.pt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Heart rate and HRV. */
internal object ArtHeart {

    /** Averages in steps of [minutes]. Only steps that have readings. */
    private fun steps(points: List<HeartPoint>, minutes: Int): List<Pair<Int, Float>> =
        points.groupBy { it.minuteOfDay / minutes }.toSortedMap().map { (k, v) -> k to v.map { it.bpm }.average().toFloat() }

    fun heatLine(a: Art, d: ArtData) {
        a.ground("#160811")
        val last = d.heart.lastOrNull()
        val rows = steps(d.heart, 20)
        val nowMin = d.now.hour * 60 + d.now.minute
        if (rows.size >= 2 && last != null) {
            val lo = rows.minOf { it.second } - 4
            val hi = max(rows.maxOf { it.second }, lo + 30)
            // The day so far takes most of the width; the rest of the day follows as dots.
            val end = max(nowMin, last.minuteOfDay).coerceAtLeast(60).toFloat()
            fun x(step: Int) = ((step * 20 + 10) / end * 700).coerceAtMost(700f)
            fun y(v: Float) = 372 - (v - lo) / (hi - lo) * 212
            val line = StringBuilder("M${pt(x(rows[0].first), y(rows[0].second))}")
            rows.zipWithNext { p, q ->
                val m = (x(p.first) + x(q.first)) / 2
                line.append("C${pt(m, y(p.second))} ${pt(m, y(q.second))} ${pt(x(q.first), y(q.second))}")
            }
            val (ex, ey) = x(rows.last().first) to y(rows.last().second)
            val heat = a.vertical(140f, 372f, 0f to "#FFD257", 0.35f to "#FF6A4D", 0.65f to "#F0338A", 1f to "#7B5CFF")
            a.scene {
                a.fill("${line}V421H${x(rows[0].first)}Z", a.vertical(140f, 420f, 0f to "#8CFF6A4D", 1f to "#0A7B5CFF"))
                a.stroke(line.toString(), "#FFFFFF", 34f, 0.18f, shader = heat)
                a.stroke(line.toString(), "#FFFFFF", 9f, shader = heat)
                a.stroke("M${ex + 24} ${ey}H880", "#7B5CFF", 4f, 0.8f, floatArrayOf(3f, 14f))
                a.circle(ex, ey, 28f, "#FFFFFF", 0.18f)
                a.circle(ex, ey, 13f, "#FFFFFF")
            }
        } else {
            a.scene { a.stroke("M0 330H880", "#7B5CFF", 4f, 0.8f, floatArrayOf(3f, 14f)) }
        }
        val w = a.text(last?.bpm?.toString() ?: Fmt.NONE, 52f, 30f, 108f, "#FFFFFF", 116f, 700, spacing = -4f)
        val ago = last?.let { nowMin - it.minuteOfDay }
        a.text(if (ago == null) "No heart rate yet" else "bpm, " + if (ago < 2) "just now" else if (ago < 60) "$ago min ago" else "${ago / 60} h ago", 52 + w + 18, 88f, 28f, "#E9C9D6", 34f, 500)
        if (last != null) a.right {
            a.text("High ${d.heart.maxOf { it.bpm }}", 828f, 42f, 28f, "#FFD257", 34f, 600, Align.End)
            a.text("Low ${d.heart.minOf { it.bpm }}", 828f, 82f, 28f, "#B4A3FF", 34f, 600, Align.End)
        }
    }

    fun heartDial(a: Art, d: ArtData) {
        a.ground("#FFE9EC")
        val rows = steps(d.heart, 20).toMap()
        val lo = rows.values.minOrNull() ?: 50f
        val hi = max(rows.values.maxOrNull() ?: 100f, lo + 40)
        a.mid {
            for (i in 0 until 72) {
                val t = i / 72f * 2 * PI.toFloat() - PI.toFloat() / 2
                val v = rows[i]
                val len = if (v == null) 8f else 12 + (v - lo) / (hi - lo) * 50
                val color = when { v == null -> "#EFC6CE"; v < lo + (hi - lo) * 0.2f -> "#5462E0"; v < lo + (hi - lo) * 0.6f -> "#E03A57"; else -> "#FF8A1E" }
                val (cx, cy) = cos(t) to sin(t)
                a.stroke("M${pt(210 + 128 * cx, 210 + 128 * cy)}L${pt(210 + (128 + len) * cx, 210 + (128 + len) * cy)}", color, 7f)
            }
            a.text(Fmt.count(d.restingHr), 210f, 142f, 92f, "#3A0A16", 98f, 700, Align.Center, spacing = -3f)
            a.text("resting", 210f, 240f, 28f, "#3A0A16", 34f, 600, Align.Center)
        }
    }

    fun stillWater(a: Art, d: ArtData) {
        a.ground(0f to "#0A4A4A", 1f to "#042526")
        val word = ArtLogic.hrvWord(d.hrv, d.hrvRange)
        // Calmer water (fewer, wider rings) the better HRV sits in or above the usual range.
        val low = d.hrv != null && d.hrvRange != null && d.hrv < d.hrvRange.start
        a.bottom {
            val rings = if (low) 8 else 5
            for (i in rings downTo 1) {
                val f = i / rings.toFloat()
                a.oval(250f, 300f, 28 + 272 * f, 11 + 127 * f, "#5FE3D3", 7f - 4 * f, 1f - 0.8f * f)
            }
            a.oval(250f, 300f, 28f, 11f, "#C9FFF6")
            if (d.hrv != null) {
                a.fill("M250 176c16 26 30 44 30 64a30 30 0 0 1 -60 0c0 -20 14 -38 30 -64Z", "#8FF3E5")
                a.stroke("M238 244a13 13 0 0 0 12 14", "#FFFFFF", 5f)
            }
        }
        val w = a.text(Fmt.whole(d.hrv), 40f, 26f, 92f, "#FFFFFF", 98f, 700, spacing = -3f)
        a.text("ms HRV", 40 + w + 12, 76f, 28f, "#FFFFFF", 34f, 500)
        a.text(word, 40f, 126f, 28f, "#8FF3E5", 34f, 600, max = 350f)
    }
}
