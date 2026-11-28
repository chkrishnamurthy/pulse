package com.fitbittrack.pulse.ui.widget.art

import com.fitbittrack.pulse.ui.goals.GoalMetric

/** Widget sizes. [w] × [h] is the drawing space every design of that size is drawn in. */
enum class WidgetSize(val w: Int, val h: Int, val cells: String) {
    Tiny(190, 190, "1 × 1"),
    Pill(420, 190, "2 × 1"),
    Square(420, 420, "2 × 2"),
    Strip(880, 190, "4 × 1"),
    Wide(880, 420, "4 × 2"),
    Large(880, 880, "4 × 4"),
}

enum class WidgetGroup(val label: String) { Day("Day"), Move("Move"), Heart("Heart"), Sleep("Sleep") }

/** Colour choices. Bloom has its own six; every other design has a finish laid over its own colours. */
enum class WidgetLook(val label: String) {
    Full("Full colour"), Soft("Soft"), Dim("Dim"), Mono("Mono"),
    Night("Night"), Day("Day"), Bold("Bold"), Leaf("Wallpaper"), Clear("Clear"), BloomMono("Mono"),
    ;

    companion object {
        val FINISHES = listOf(Full, Soft, Dim, Mono)
        val BLOOM = listOf(Night, Day, Bold, Leaf, Clear, BloomMono)
    }
}

enum class RefreshSpot { TopEnd, BottomEnd, BottomStart }

/** The page a tap on the widget opens. */
enum class WidgetTap(val label: String) { Today("Today"), Goals("Calendar"), Sleep("Sleep"), Heart("Heart"), Trends("Trends"), Ask("Ask Pulse"), Train("Train") }

/**
 * Every widget drawing. The name is saved on the phone, so never rename an entry.
 * [clear] = the background can be made see-through: false where the background is part of the picture.
 */
enum class WidgetDesign(
    val label: String,
    val about: String,
    val size: WidgetSize,
    val group: WidgetGroup,
    val tap: WidgetTap,
    val clear: Boolean = false,
) {
    Sky("Living Sky", "Changes through the day. The sun climbs as you walk.", WidgetSize.Wide, WidgetGroup.Day, WidgetTap.Today),
    Bloom("Bloom", "Your goals as petals.", WidgetSize.Square, WidgetGroup.Day, WidgetTap.Goals, clear = true),
    Pip("Pip", "A small friend. The mood follows your day.", WidgetSize.Square, WidgetGroup.Day, WidgetTap.Today),
    Garden("Month Garden", "Every day grows a plant. Reach your goals for a flower.", WidgetSize.Wide, WidgetGroup.Day, WidgetTap.Goals),
    Orbit("Orbit", "Six goals travel round the sun.", WidgetSize.Large, WidgetGroup.Day, WidgetTap.Today, clear = true),
    Poster("Shape Poster", "Steps, sleep, heart and streak as big shapes.", WidgetSize.Large, WidgetGroup.Day, WidgetTap.Today),
    StreakStars("Streak Stars", "Days in a row, joined like stars.", WidgetSize.Wide, WidgetGroup.Day, WidgetTap.Goals, clear = true),
    Signals("Body Signals", "Five body signals next to your usual range.", WidgetSize.Wide, WidgetGroup.Day, WidgetTap.Sleep, clear = true),
    AskBar("Ask Pulse", "Ask a question from your home screen.", WidgetSize.Strip, WidgetGroup.Day, WidgetTap.Ask),
    Band("My Band", "Band battery and last sync.", WidgetSize.Pill, WidgetGroup.Day, WidgetTap.Today),
    TinyStrip("Four tiny ones", "Steps, heart, sleep and sync in one row.", WidgetSize.Strip, WidgetGroup.Day, WidgetTap.Today),

    Trail("Summit Trail", "Walk up the mountain to your step goal.", WidgetSize.Wide, WidgetGroup.Move, WidgetTap.Today),
    BigFill("Big Fill", "Your steps fill the number.", WidgetSize.Wide, WidgetGroup.Move, WidgetTap.Today),
    StepCity("Step City", "Every hour of steps is a building.", WidgetSize.Wide, WidgetGroup.Move, WidgetTap.Today),
    Stairs("Stairs", "Floors climbed today.", WidgetSize.Square, WidgetGroup.Move, WidgetTap.Today),
    Flame("Flame", "Calories fill the flame.", WidgetSize.Square, WidgetGroup.Move, WidgetTap.Today, clear = true),
    Road("Road", "Distance along a road.", WidgetSize.Strip, WidgetGroup.Move, WidgetTap.Today),
    Race("Race Yesterday", "Today against yesterday at the same time.", WidgetSize.Strip, WidgetGroup.Move, WidgetTap.Today),
    StepWave("Step Wave", "Steps as a filling wave.", WidgetSize.Pill, WidgetGroup.Move, WidgetTap.Today),
    Fuse("Firework Fuse", "Zone minutes burn the fuse. Light it in seven days.", WidgetSize.Strip, WidgetGroup.Move, WidgetTap.Today, clear = true),
    TinySteps("Tiny steps", "Steps today.", WidgetSize.Tiny, WidgetGroup.Move, WidgetTap.Today),

    HeatLine("Heat Line", "Your heart rate through the day.", WidgetSize.Wide, WidgetGroup.Heart, WidgetTap.Heart, clear = true),
    HeartDial("Heart Dial", "24 hours of heart rate in a circle.", WidgetSize.Square, WidgetGroup.Heart, WidgetTap.Heart),
    StillWater("Still Water", "Heart rate variability (HRV) as calm water.", WidgetSize.Square, WidgetGroup.Heart, WidgetTap.Heart, clear = true),
    TinyHeart("Tiny heart", "Resting heart rate.", WidgetSize.Tiny, WidgetGroup.Heart, WidgetTap.Heart),

    DeepDive("Deep Dive", "Your night as a dive. Deep sleep is deep water.", WidgetSize.Wide, WidgetGroup.Sleep, WidgetTap.Sleep),
    Moon("Sleep Moon", "The moon fills as you reach your sleep goal.", WidgetSize.Square, WidgetGroup.Sleep, WidgetTap.Sleep, clear = true),
    SleepClock("Sleep Clock", "Sleep stages around a clock.", WidgetSize.Square, WidgetGroup.Sleep, WidgetTap.Sleep, clear = true),
    Hourglass("Bedtime Sand", "Time left until bed.", WidgetSize.Square, WidgetGroup.Sleep, WidgetTap.Sleep),
    SevenMoons("Seven Moons", "Seven nights, seven moons.", WidgetSize.Strip, WidgetGroup.Sleep, WidgetTap.Sleep, clear = true),
    TinyMoon("Tiny moon", "Sleep last night.", WidgetSize.Tiny, WidgetGroup.Sleep, WidgetTap.Sleep),
    TinySync("Tiny sync", "When Pulse last synced.", WidgetSize.Tiny, WidgetGroup.Day, WidgetTap.Today),
    ;

    val looks: List<WidgetLook> get() = if (this == Bloom) WidgetLook.BLOOM else WidgetLook.FINISHES

    /** Room for a refresh button that a finger can hit, without covering the picture's own words. */
    val canRefresh: Boolean get() = size in setOf(WidgetSize.Square, WidgetSize.Wide, WidgetSize.Large) && this !in setOf(BigFill, Signals, Orbit, Hourglass)

    /** The corner where a refresh button covers nothing important. */
    val refreshSpot: RefreshSpot get() = when (this) {
        Trail -> RefreshSpot.BottomStart
        HeatLine, StreakStars -> RefreshSpot.BottomEnd
        else -> RefreshSpot.TopEnd
    }

    companion object {
        fun bySize(size: WidgetSize) = entries.filter { it.size == size }
        fun named(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/** One placed widget's settings. */
data class WidgetConfig(
    val design: WidgetDesign,
    val look: WidgetLook = design.looks.first(),
    /** Bloom only: which goals get a petal. Never empty. */
    val petals: Set<GoalMetric> = GoalMetric.entries.toSet(),
    /** 0 = background hidden, 100 = solid. */
    val background: Int = 100,
    val tap: WidgetTap = design.tap,
    val refresh: Boolean = false,
) {
    fun encode(): String = listOf(
        design.name, look.name, petals.sortedBy { it.ordinal }.joinToString(",") { it.name }, background.toString(), tap.name, if (refresh) "1" else "0",
    ).joinToString(";")

    companion object {
        /** Null when the text is damaged or names a design this version does not know. */
        fun decode(text: String?): WidgetConfig? {
            val p = text?.split(";") ?: return null
            val design = WidgetDesign.named(p.getOrNull(0)) ?: return null
            val base = WidgetConfig(design)
            return base.copy(
                look = design.looks.firstOrNull { it.name == p.getOrNull(1) } ?: base.look,
                petals = p.getOrNull(2).orEmpty().split(",").mapNotNull { n -> GoalMetric.entries.firstOrNull { it.name == n } }.toSet().ifEmpty { base.petals },
                background = if (design.clear) (p.getOrNull(3)?.toIntOrNull() ?: 100).coerceIn(0, 100) else 100,
                tap = WidgetTap.entries.firstOrNull { it.name == p.getOrNull(4) } ?: base.tap,
                refresh = design.canRefresh && p.getOrNull(5) == "1",
            )
        }
    }
}
