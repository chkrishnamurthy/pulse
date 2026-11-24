package com.fitbittrack.pulse.ui.widget.art

import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.goals.GoalMetric

/** Small and quick widgets. */
internal object ArtSmall {

    /** Shown on the Ask Pulse widget. Taps send the number, so only these questions can be asked from outside the app. */
    val QUESTIONS = listOf("How did I sleep?", "Am I on track today?", "How is my heart this week?")
    private val SHORT = listOf("How did I sleep?", "Am I on track?", "My heart this week")

    /** Where the three question buttons begin and end, as shares of the width. The tap areas use the same. */
    val ASK_SPLIT = listOf(0f, 0.335f, 0.625f, 1f)

    fun askBar(a: Art) {
        a.ground("#141B26")
        val mid = a.h * 0.51f
        a.rect(20f, 18f, a.w - 40, mid - 18, "#0B1018", (mid - 18) / 2)
        val y = 18 + (mid - 18) / 2
        a.stroke("M46 ${y + 2}H58L66 ${y - 15}L76 ${y + 15}L84 ${y + 2}H96", "#FF5C74", 6f)
        a.text("Ask Pulse about your health", 122f, y - 18, 30f, "#C2C9DA", 36f, 500, max = a.w - 170)
        val top = mid + 12
        val bh = a.h - 18 - top
        val inner = a.w - 40
        listOf("#2A2F63" to "#DDE1FF", "#4A3410" to "#FFDFA3", "#4A1823" to "#FFC9D1").forEachIndexed { i, (bg, ink) ->
            val x0 = 20 + inner * ASK_SPLIT[i] + if (i == 0) 0f else 7f
            val x1 = 20 + inner * ASK_SPLIT[i + 1] - if (i == 2) 0f else 7f
            a.rect(x0, top, x1 - x0, bh, bg, bh / 2)
            a.text(SHORT[i], (x0 + x1) / 2, top + bh / 2 - 16, 27f, ink, 32f, 600, Align.Center, x1 - x0 - 32)
        }
    }

    fun band(a: Art, d: ArtData) {
        a.ground("#DDF5E8")
        a.mid {
            a.fill("M${-a.ex} 34H${420 + a.ex}V96H${-a.ex}Z", "#17392A")
            a.dots(listOf(22f, 54f, 86f, 334f, 366f, 398f).map { it to 65f }, "#0A2418", 13f)
            a.rect(112f, 20f, 196f, 90f, "#081C13", 45f)
            a.rect(124f, 32f, 172f, 66f, "#1E5A3D", 33f)
            val level = d.battery
            val words = level?.let { "$it%" } ?: d.batteryWord?.replaceFirstChar { it.uppercase() } ?: Fmt.NONE
            fun label(ink: String) = a.text(words, 210f, 44f, 36f, ink, 42f, 800, Align.Center, 150f)
            // Light on the empty part, dark on the charge.
            label("#FFFFFF")
            if (level != null) a.clip("M157 32H263A33 33 0 0 1 263 98H157A33 33 0 0 1 157 32Z") {
                val x = 124 + 172 * (level / 100f).coerceIn(0f, 1f)
                a.clip("M100 0H${x}V190H100Z") {
                    a.rect(124f, 32f, 172f, 66f, if (level <= 20) "#FF8A6B" else "#5FE0A0")
                    label("#062615")
                }
            }
            a.text("Synced ${d.syncedAgo}", 210f, 130f, 28f, "#0C3B22", 34f, 600, Align.Center, 380f)
        }
    }

    fun tinySteps(a: Art, d: ArtData) {
        a.ground("#FFD27A")
        val y = a.h - a.h * d.part(GoalMetric.Steps)
        if (y < a.h) a.fill("M0 ${y}C40 ${y - 12} 70 ${y + 12} 110 ${y}S160 ${y - 8} ${a.w} ${y}V${a.h}H0Z", "#F5A524")
        a.mid {
            a.text(ArtLogic.thousands(d.steps), 95f, 54f, 54f, "#2A1800", 60f, 700, Align.Center, 160f, -2f)
            a.text("steps", 95f, 116f, 27f, "#2A1800", 33f, 600, Align.Center)
        }
    }

    fun tinyHeart(a: Art, d: ArtData) {
        a.ground("#3A0B17")
        a.mid {
            a.fill("M95 168C40 128 22 98 22 70C22 44 42 28 62 28C78 28 89 38 95 50C101 38 112 28 128 28C148 28 168 44 168 70C168 98 150 128 95 168Z", "#D92645")
            a.text(Fmt.count(d.restingHr), 95f, 52f, 60f, "#FFFFFF", 68f, 700, Align.Center, 110f, -2f)
        }
    }

    fun tinyMoon(a: Art, d: ArtData) {
        a.ground("#1B1E57")
        a.mid {
            a.circle(95f, 68f, 40f, "#34387F")
            val f = d.part(GoalMetric.Sleep)
            if (f > 0.01f) a.fill(ArtSleep.lit(95f, 68f, 40f, f), "#FBEFCB")
            a.dots(listOf(36f to 46f, 152f to 96f, 148f to 34f), "#FFFFFF", 7f)
            a.text(if (d.sleepMinutes == null) Fmt.NONE else Fmt.minutes(d.sleepMinutes), 95f, 118f, 38f, "#FFFFFF", 46f, 700, Align.Center, 160f, -1f)
        }
    }

    fun tinySync(a: Art, d: ArtData) {
        a.ground("#0F2A21")
        a.mid {
            a.ring(95f, 70f, 36f, "#24513F", 10f)
            a.stroke("M95 34A36 36 0 1 1 63 53", "#5FE0A0", 10f)
            a.stroke("M80 70L91 82L112 58", "#5FE0A0", 9f)
            a.text(d.syncedAgo, 95f, 122f, 27f, "#FFFFFF", 33f, 600, Align.Center, 168f)
        }
    }

    fun tinyStrip(a: Art, d: ArtData) {
        val side = minOf(190f, a.h)
        val gap = (a.w - side * 4) / 3
        listOf(::tinySteps, ::tinyHeart, ::tinyMoon, ::tinySync).forEachIndexed { i, draw ->
            a.at(i * (side + gap), (a.h - side) / 2) {
                a.c.clipPath(android.graphics.Path().apply { addRoundRect(0f, 0f, side, side, 60f, 60f, android.graphics.Path.Direction.CW) })
                draw(a.part(190f, 190f, WidgetSize.Tiny), d)
            }
        }
    }
}
