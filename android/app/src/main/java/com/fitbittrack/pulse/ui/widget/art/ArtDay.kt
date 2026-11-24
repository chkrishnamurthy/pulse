package com.fitbittrack.pulse.ui.widget.art

import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.widget.art.Art.Companion.hex
import com.fitbittrack.pulse.ui.widget.art.Art.Companion.pt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The whole day: smart sky, goals, streak, month, body signals and Pip. */
internal object ArtDay {

    private class SkyLook(val sky: List<Pair<Float, String>>, val ink: String, val sun: String, val h1: String, val h2: String, val h3: String)

    private val SKIES = mapOf(
        ArtLogic.SkyTime.Morning to SkyLook(listOf(0f to "#FCE6C4", 1f to "#FF9B85"), "#3A1730", "#FFF6DC", "#E86F6A", "#A93F63", "#6E2A56"),
        ArtLogic.SkyTime.Day to SkyLook(listOf(0f to "#86CDF7", 1f to "#E6F6FF"), "#0B2B45", "#FFC02E", "#6FB98A", "#24735C", "#14584A"),
        ArtLogic.SkyTime.Evening to SkyLook(listOf(0f to "#241F5C", 0.58f to "#6A2F74", 1f to "#F0764F"), "#FFFFFF", "#FFB25A", "#4A1D55", "#33133F", "#1F0B2C"),
        ArtLogic.SkyTime.Night to SkyLook(listOf(0f to "#050A1C", 1f to "#172A66"), "#FFFFFF", "#F4ECD2", "#12204F", "#0B153A", "#060B22"),
    )

    fun sky(a: Art, d: ArtData) {
        val s = ArtLogic.sky(d)
        val l = SKIES.getValue(s.time)
        val night = s.time == ArtLogic.SkyTime.Night
        a.ground(*l.sky.toTypedArray())
        val stars = if (night) 0.7f else if (s.time == ArtLogic.SkyTime.Evening) 0.28f else 0f
        if (stars > 0) {
            val all = (0 until 46).map { ((it * 197 + 31) % 880f) to ((it * 83 + 17) % 250f) }
            a.dots(all.filterIndexed { i, _ -> i % 6 != 0 }, "#FFFFFF", 5f, stars)
            a.dots(all.filterIndexed { i, _ -> i % 6 == 0 }, "#FFFFFF", 9f, stars)
        }
        // At night the moon rests at the top of the arc; otherwise the sun climbs with your progress.
        val p = if (night) 0.5f else s.progress
        val t = PI.toFloat() * (1 - p)
        val (sx, sy) = 630 + 205 * cos(t) to 300 - 205 * sin(t)
        a.at(a.ex, a.ey) {
            a.stroke("M425 300A205 205 0 0 1 835 300", l.ink, 4f, 0.55f, floatArrayOf(3f, 14f))
            if (!night && p > 0.01f) a.stroke("M425 300A205 205 0 0 1 ${pt(sx, sy)}", l.sun, 9f)
            a.circle(sx, sy, 86f, l.sun, 0.18f)
            a.circle(sx, sy, 62f, l.sun, 0.28f)
            a.circle(sx, sy, 42f, l.sun)
            if (night) listOf(Triple(-14f, -8f, 9f), Triple(12f, 14f, 12f), Triple(16f, -18f, 6f)).forEach { (x, y, r) -> a.circle(sx + x, sy + y, r, "#D8CDA6") }
        }
        a.scene {
            a.fill("M0 320C160 250 300 330 460 290S720 240 880 300V421H0Z", l.h1)
            a.fill("M0 350C200 300 360 370 560 330S800 310 880 340V421H0Z", l.h2)
            a.fill("M0 378C220 336 420 396 640 366S840 360 880 368V421H0Z", l.h3)
        }
        a.text(s.label, 52f, 40f, 28f, l.ink, 34f, 600)
        val h = a.lines(s.headline, 52f, 82f, 56f, l.ink, 62f, 390f, 700, -1.5f)
        a.text(s.detail, 52f, 92 + h, 28f, l.ink, 34f, 500, max = 390f)
        a.at(a.ex, a.ey) { a.text(s.foot, 828f, 376f, 26f, "#FFFFFF", 32f, 500, Align.End) }
    }

    private class BloomLook(val ground: Int, val ink: Int, val core: Int, val faint: Int, val c: List<Int>)

    fun bloom(a: Art, d: ArtData, config: WidgetConfig) {
        fun l(ground: String, ink: String, core: String, faint: String, vararg c: String) = BloomLook(hex(ground), hex(ink), hex(core), hex(faint), c.map(::hex))
        val look = when (config.look) {
            WidgetLook.Day -> l("#FFFFFF", "#111827", "#FFFFFF", "#E9EDF3", "#F0A01E", "#5462E0", "#E8622E", "#0E9E8E", "#6AA321", "#E03A57")
            WidgetLook.Bold -> l("#2546E8", "#FFFFFF", "#2546E8", "#4462F0", "#FFC83D", "#FFFFFF", "#FF8A5C", "#7BE8DA", "#C6F26B", "#FF9DB0")
            WidgetLook.Leaf -> a.accent.run { BloomLook(ground, ink, ground, faint, listOf(strong, medium, strong, medium, strong, medium)) }
            WidgetLook.Clear -> l("#6B0A141E", "#FFFFFF", "#1B2C38", "#29FFFFFF", "#FFD27A", "#C5CCFF", "#FFB08C", "#93F0E3", "#CDEF9A", "#FFB1BE")
            WidgetLook.BloomMono -> l("#0C0C0E", "#FFFFFF", "#0C0C0E", "#26262B", "#FFFFFF", "#CFCFD6", "#FFFFFF", "#CFCFD6", "#FFFFFF", "#CFCFD6")
            else -> l("#141B26", "#EEF2F8", "#141B26", "#222D3D", "#F5A524", "#8E9BFF", "#FF7A45", "#2FD3C0", "#9FD356", "#FF5C74")
        }
        a.ground(look.ground)
        val shown = GoalMetric.entries.filter { it in config.petals }
        val n = shown.size.coerceAtLeast(1)
        fun petal(k: Int, len: Float): String {
            val wide = min(92f, 520f / n)
            val t = k.toFloat() / n * 2 * PI.toFloat()
            fun r(x: Float, y: Float) = pt(210 + x * cos(t) - y * sin(t), 210 + x * sin(t) + y * cos(t))
            return "M${r(0f, 0f)}C${r(-wide, -len * 0.4f)} ${r(-wide * 0.72f, -len)} ${r(0f, -len)}C${r(wide * 0.72f, -len)} ${r(wide, -len * 0.4f)} ${r(0f, 0f)}Z"
        }
        a.mid {
            shown.forEachIndexed { k, _ -> a.fill(petal(k, 188f), look.faint) }
            shown.forEachIndexed { k, m -> if (d.goals[m].value != null) a.fill(petal(k, 62 + 126 * d.part(m)), look.c[m.ordinal]) }
            a.c.drawCircle(210f, 210f, 62f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = look.core })
            val known = shown.filter { d.goals[it].value != null }
            a.text(if (known.isEmpty()) Fmt.NONE else "${(known.map { d.part(it) }.average() * 100).roundToInt()}%", 210f, 182f, 44f, look.ink, 56f, 700, Align.Center, 112f, -1f)
        }
    }

    fun pip(a: Art, d: ArtData) {
        val mood = ArtLogic.mood(d)
        val sleepy = mood == ArtLogic.Mood.Asleep || mood == ArtLogic.Mood.Tired
        val ink = if (sleepy) "#FFFFFF" else "#2A2100"
        val body = if (sleepy) "#9AA5FF" else "#12A594"
        val face = if (sleepy) "#1C1E58" else "#06302B"
        a.ground(if (sleepy) "#2C2F78" else if (mood == ArtLogic.Mood.Proud) "#FFC4D3" else if (mood == ArtLogic.Mood.Calm) "#BFE6F5" else "#FFD84D")
        a.mid {
            a.oval(210f, 312f, 110f, 16f, "#000000", alpha = 0.14f)
            a.stroke("M172 288V308M248 288V308", body, 22f)
            val up = mood == ArtLogic.Mood.Happy || mood == ArtLogic.Mood.Proud
            a.stroke(if (up) "M112 206Q74 178 66 134M308 206Q346 178 354 134" else "M112 222Q84 250 92 280M308 222Q336 250 328 280", body, 22f)
            a.fill("M210 66C296 66 322 134 322 198C322 262 278 300 210 300C142 300 98 262 98 198C98 134 124 66 210 66Z", body)
            a.fill("M210 66C206 40 222 30 238 34C238 50 226 62 210 66Z", if (sleepy) "#7CE0A8" else "#FF6B57")
            a.oval(210f, 232f, 66f, 50f, if (sleepy) "#BCC4FF" else "#63D6C5")
            when (mood) {
                ArtLogic.Mood.Asleep -> a.stroke("M152 156q16 14 32 0M236 156q16 14 32 0", face, 8f)
                ArtLogic.Mood.Tired -> a.stroke("M152 152h32M236 152h32", face, 8f)
                ArtLogic.Mood.Proud -> a.stroke("M152 160q16 -18 32 0M236 160q16 -18 32 0", face, 8f)
                else -> a.stroke("M168 148v8M252 148v8", face, 20f)
            }
            a.stroke(
                when (mood) { ArtLogic.Mood.Asleep -> "M200 192h20"; ArtLogic.Mood.Tired -> "M192 196q18 -12 36 0"; ArtLogic.Mood.Calm -> "M194 188q16 12 32 0"; else -> "M184 186Q210 216 236 186" },
                face, 8f,
            )
            a.circle(140f, 186f, 15f, if (sleepy) "#FF9DB0" else "#FF8E7A")
            a.circle(280f, 186f, 15f, if (sleepy) "#FF9DB0" else "#FF8E7A")
            // Pip wears your band.
            a.stroke(if (up) "M346 166L368 156" else "M317 248L343 258", "#101827", 16f, round = false)
            when (mood) {
                ArtLogic.Mood.Asleep -> a.stroke("M326 64h30l-30 34h30M372 34h20l-20 24h20", ink, 7f)
                ArtLogic.Mood.Happy, ArtLogic.Mood.Proud -> a.stroke("M60 84l8 16M36 100l14 12M360 84l-8 16M384 100l-14 12", ink, 7f)
                else -> Unit
            }
            a.text(mood.say, 210f, 340f, 34f, ink, 42f, 700, Align.Center, 370f)
        }
    }

    fun garden(a: Art, d: ArtData) {
        a.ground(0f to "#CFF0F5", 1f to "#EFFBEA")
        a.right { a.circle(770f, 92f, 44f, "#FFC02E") }
        a.scene {
            a.fill("M0 330C220 296 420 344 640 320S840 310 880 318V421H0Z", "#A9DCA2")
            a.fill("M0 356C240 340 520 366 880 346V421H0Z", "#5C3A23")
            a.stroke("M-10 357C240 340 520 366 890 346", "#3F9A55", 14f)
            for (i in 0 until d.monthLength) {
                val x = 48 + i * (783f / (d.monthLength - 1))
                val y = 352f
                val g = d.monthGrowth.getOrNull(i)
                if (g == null) { a.circle(x, 384f, 5f, "#A8805F"); continue }
                if (g == 0) {
                    a.stroke("M$x ${y}v-14", "#2E7D46", 5f)
                    a.fill("M$x ${y - 14}q-14 -4 -14 -16q14 0 14 16Z", "#46A862")
                    continue
                }
                val h = if (g == 1) 46f else 84f + (i * 41) % 64
                a.stroke("M$x ${y}q${((i % 3) - 1) * 5} ${-h / 2} 0 ${-h}", "#2E7D46", 5f)
                a.fill("M$x ${y - h * 0.4f}q-18 -2 -20 -20q18 0 20 20ZM$x ${y - h * 0.55f}q18 -2 20 -20q-18 0 -20 20Z", "#46A862")
                if (g == 2) {
                    val color = listOf("#F0506E", "#F59A1E", "#5B66E8")[i % 3]
                    for (k in 0 until 6) { val t = k / 6f * 2 * PI.toFloat(); a.circle(x + 12 * cos(t), y - h + 12 * sin(t), 7.5f, color) }
                    a.circle(x, y - h, 6f, "#FFF6D0")
                }
            }
        }
        val flowers = d.monthGrowth.count { it == 2 }
        a.text(if (flowers == 1) "1 flower" else "$flowers flowers", 52f, 34f, 60f, "#12331F", 70f, 700, spacing = -2f)
        val days = d.monthGrowth.size
        val month = d.now.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.getDefault())
        a.text("in $days ${if (days == 1) "day" else "days"} of $month", 52f, 106f, 28f, "#12331F", 34f, 600, max = 600f)
    }

    /** Same colours as the rings in the app. */
    private val GOAL = listOf("#F5A524", "#8E9BFF", "#FF7A45", "#2FD3C0", "#9FD356", "#FF5C74")
    private val GOAL_TEXT = listOf("#F5B84A", "#AEB7FF", "#FF9A70", "#5FE3D3", "#B4E070", "#FF8A9B")

    fun orbit(a: Art, d: ArtData) {
        a.ground("#070A16")
        a.dots((0 until 70).map { ((it * 211 + 43) % 880f) to ((it * 127 + 61) % 680f) }, "#FFFFFF", 4f, 0.5f)
        val radius = listOf(148f, 196f, 244f, 292f, 340f, 384f)
        val sizes = listOf(20f, 28f, 18f, 24f, 30f, 20f)
        fun p(r: Float, u: Float): Pair<Float, Float> { val t = u * 2 * PI.toFloat() - PI.toFloat() / 2; return 440 + r * cos(t) to 400 + r * 0.52f * sin(t) }
        val known = GoalMetric.entries.filter { d.goals[it].value != null }
        a.mid {
            a.rotate(-18f, 440f, 400f) {
                radius.forEach { a.oval(440f, 400f, it, it * 0.52f, "#2A3150", 3f) }
                a.circle(440f, 400f, 130f, "#FFC24A", 0.1f)
                a.circle(440f, 400f, 84f, a.radial(440f, 400f, 84f, 0f to "#FFF3CF", 0.55f to "#FFC24A", 1f to "#FF8A1E"))
                known.forEach { m ->
                    val i = m.ordinal
                    val r = radius[i]
                    val v = d.part(m).coerceAtMost(0.999f)
                    val (ex, ey) = p(r, v)
                    val (sx, sy) = p(r, (v - 0.34f).coerceAtLeast(0f))
                    if (v > 0.01f) a.stroke("M${pt(sx, sy)}A$r ${r * 0.52f} 0 0 1 ${pt(ex, ey)}", GOAL[i], 9f, 0.75f)
                    a.circle(ex, ey, sizes[i], GOAL[i])
                    if (m == GoalMetric.Sleep) a.oval(ex, ey, 46f, 11f, "#D5DAFF", 5f)
                    if (d.goals[m].met) a.ring(ex, ey, sizes[i] + 12, GOAL[i], 3f, 0.5f)
                }
            }
            a.text(if (known.isEmpty()) Fmt.NONE else "${(known.map { d.part(it) }.average() * 100).roundToInt()}%", 440f, 367f, 56f, "#2A1500", 66f, 800, Align.Center, 150f, -2f)
        }
        a.text("Today", 56f, 44f, 46f, "#FFFFFF", 56f, 700, spacing = -1f)
        a.right { a.text("One full lap = goal done", 824f, 56f, 28f, "#C2C9DA", 34f, 500, Align.End) }
        a.midBottom {
            GoalMetric.entries.forEachIndexed { i, m ->
                val x = 56f + (i % 3) * 264
                val y = 662f + (i / 3) * 106
                a.text(value(d, m), x, y, 38f, "#FFFFFF", 46f, 700, max = 240f)
                a.text((if (m == GoalMetric.Zone) "Zone" else m.label) + if (d.goals[m].met) ", done" else "", x, y + 46, 27f, GOAL_TEXT[i], 33f, 600, max = 240f)
            }
        }
    }

    private fun value(d: ArtData, m: GoalMetric): String {
        val v = d.goals[m].value
        return when (m) {
            GoalMetric.Sleep -> Fmt.minutes(v?.toInt())
            GoalMetric.Distance -> Fmt.distance(v, d.units).let { if (v == null) it.first else "${it.first} ${it.second}" }
            GoalMetric.Zone -> if (v == null) Fmt.NONE else "${v.toInt()} min"
            else -> Fmt.whole(v)
        }
    }

    fun poster(a: Art, d: ArtData) {
        a.ground("#F2F4F8")
        // Four quarters, each stretched to its share of the widget.
        a.pulled(a.w / 880f, a.h / 880f) { posterShapes(a, d) }
    }

    private fun posterShapes(a: Art, d: ArtData) {
        a.rect(0f, 0f, 441f, 441f, "#FFB72E")
        a.fill("M0 441V0A440 440 0 0 1 440 441Z", "#F5A010")
        val r = 440 * d.part(GoalMetric.Steps)
        val quarter = "M0 441V${440 - r}A$r $r 0 0 1 $r 441Z"
        if (r > 4) a.fill(quarter, "#16245F")
        a.rect(440f, 0f, 441f, 441f, "#2546E8")
        a.circle(660f, 250f, 150f, "#1B35BC")
        val f = d.part(GoalMetric.Sleep)
        a.round(660f, 250f) {
            val moon = ArtSleep.lit(660f, 250f, 150f, f)
            fun words(ink: String) = a.text(if (d.sleepMinutes == null) Fmt.NONE else Fmt.minutes(d.sleepMinutes).removeSuffix("m"), 660f, 216f, 52f, ink, 66f, 800, Align.Center, 230f, -2f)
            words("#FFFFFF")
            if (f > 0.01f) { a.fill(moon, "#F2F4F8"); a.clip(moon) { words("#16245F") } }
        }
        a.rect(0f, 440f, 440f, 441f, "#FF6B7F")
        val rest = d.resting7.filterNotNull()
        if (rest.size >= 2) {
            val lo = rest.min()
            val span = (rest.max() - lo).coerceAtLeast(4)
            val pts = rest.mapIndexed { i, v -> 440f * i / (rest.size - 1) to 780 - 150f * (v - lo) / span }
            val line = pts.joinToString("L") { pt(it.first, it.second) }
            a.fill("M0 881L${line}L440 881Z", "#16245F")
            a.stroke("M$line", "#FFFFFF", 8f)
        } else a.rect(0f, 740f, 440f, 141f, "#16245F")
        for (i in 0 until 8) a.circle(526f + (i % 4) * 94, 580f + (i / 4) * 94, 36f, if (i < d.streak) "#16245F" else "#D3D9E6")
        a.text("Steps", 44f, 30f, 34f, "#16245F", 42f, 700)
        fun steps(ink: String) = a.text(Fmt.count(d.steps), 36f, 322f, 84f, ink, 96f, 800, max = 260f, spacing = -4f)
        steps("#16245F")
        if (r > 4) a.clip(quarter) { steps("#FFB72E") }
        a.text("Sleep", 484f, 30f, 34f, "#FFFFFF", 42f, 700)
        a.text("Resting heart", 44f, 466f, 34f, "#3A0712", 42f, 700)
        a.text(Fmt.count(d.restingHr), 44f, 766f, 84f, "#FFFFFF", 96f, 800, spacing = -4f)
        a.text("Streak", 484f, 466f, 34f, "#16245F", 42f, 700)
        a.text(if (d.streak == 1) "1 day" else "${d.streak} days", 484f, 766f, 60f, "#16245F", 96f, 800, max = 350f, spacing = -2f)
    }

    fun streakStars(a: Art, d: ArtData) {
        a.ground(0f to "#0A1030", 1f to "#123A52")
        a.dots((0 until 50).map { ((it * 173 + 20) % 880f) to ((it * 97 + 12) % 420f) }, "#FFFFFF", 4f, 0.35f)
        val p = listOf(
            66f to 316f, 118f to 362f, 176f to 300f, 228f to 344f, 286f to 276f, 334f to 352f, 396f to 322f, 452f to 368f, 506f to 296f, 556f to 220f, 618f to 262f, 672f to 176f, 740f to 206f, 816f to 92f,
        )
        val ok = d.before13.let { List(13 - it.size) { false } + it } + d.todayComplete
        // Where the current run begins.
        val runStart = run { var i = if (d.todayComplete) 13 else 12; while (i >= 0 && ok[i]) i--; i + 1 }
        fun star(x: Float, y: Float, r: Float) = (0 until 8).joinToString("L", "M", "Z") { k ->
            val t = k * PI.toFloat() / 4 - PI.toFloat() / 2
            val q = if (k % 2 == 1) r * 0.36f else r
            pt(x + q * cos(t), y + q * sin(t))
        }
        a.scene {
            for (i in 0 until 13) if (ok[i] && ok[i + 1]) a.stroke("M${pt(p[i].first, p[i].second)}L${pt(p[i + 1].first, p[i + 1].second)}", "#FFE3A1", if (i >= runStart) 5f else 4f, if (i >= runStart) 1f else 0.4f)
            if (!d.todayComplete) {
                a.stroke("M740 206L802 114", "#FFE3A1", 4f, dash = floatArrayOf(3f, 11f))
                a.ring(816f, 92f, 22f, "#FFE3A1", 5f, dash = floatArrayOf(8f, 8f))
            }
            for (i in 0..13) {
                val (x, y) = p[i]
                if (ok[i]) {
                    if (i >= runStart) a.circle(x, y, 26f, "#FFE3A1", 0.16f)
                    a.fill(star(x, y, if (i >= runStart) 27f else 18f), "#FFE3A1")
                } else if (i < 13) a.circle(x, y, 6.5f, "#7F90B5")
            }
        }
        val w = a.text("${d.streak}", 52f, 30f, 108f, "#FFFFFF", 116f, 700, spacing = -4f)
        a.text(if (d.streak == 1) "day in a row" else "days in a row", 52 + w + 16, 86f, 32f, "#FFFFFF", 40f, 600)
        a.text(if (d.todayComplete) "Today is done" else "Finish today to add a star", 52f, 148f, 28f, "#C9D6EA", 34f, 500)
    }

    fun signals(a: Art, d: ArtData) {
        a.ground("#0C2422")
        val (title, note) = ArtLogic.signalsText(d.signals)
        a.text(title, 52f, 34f, 38f, "#FFFFFF", 46f, 700, max = 440f, spacing = -1f)
        if (note != null) a.right { a.text(note, 828f, 42f, 28f, "#FFC58A", 34f, 600, Align.End, 330f) }
        a.scene {
            fun x(i: Int) = 122f + i * 159
            fun y(v: Float) = 286 - 146 * v
            d.signals.forEachIndexed { i, s ->
                a.stroke("M${x(i)} 140V286", "#17403C", 44f)
                if (s.position != null) a.stroke("M${x(i)} ${y(s.high)}V${y(s.low)}", "#2A7A70", 44f)
            }
            val known = d.signals.withIndex().filter { it.value.position != null }
            if (known.size > 1) a.stroke("M" + known.joinToString("L") { pt(x(it.index), y(it.value.position!!)) }, "#5FE3D3", 4f, dash = floatArrayOf(3f, 11f))
            known.forEach { a.circle(x(it.index), y(it.value.position!!), if (it.value.side == 0) 18f else 20f, if (it.value.side == 0) "#C9FFF6" else "#FFB25A") }
        }
        a.bottom {
            d.signals.forEachIndexed { i, s ->
                val x = (122f + i * 159) * a.w / 880f
                val out = s.side != null && s.side != 0
                a.text(s.value, x, 320f, 36f, if (out) "#FFC58A" else "#FFFFFF", 40f, 700, Align.Center, 150f)
                a.text(s.label, x, 360f, 26f, "#B9DBD6", 32f, 500, Align.Center, 152f)
            }
        }
    }
}
