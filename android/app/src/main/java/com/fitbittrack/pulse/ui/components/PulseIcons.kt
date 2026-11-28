package com.fitbittrack.pulse.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons (24×24, 2px stroke). Tinted by the caller. */
object PulseIcons {
    // Tabs
    val Today = icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7v5l3 2")
    val Calendar = icon("M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M4 10h16 M8 3v4 M16 3v4 M9 15l2 2 4-4")
    val Health = icon("M12 20C5 15.5 3 12 3 8.8A4.8 4.8 0 0 1 12 6.5A4.8 4.8 0 0 1 21 8.8C21 12 19 15.5 12 20z")
    val Trends = icon("M5 20V10 M12 20V4 M19 20v-7")
    val You = icon("M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M4.5 20.5c1-4 4-5.5 7.5-5.5s6.5 1.5 7.5 5.5")

    // Metrics
    val Heart = icon("M3 12h4l2-5 4 10 2-5h6")
    val Sleep = icon("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Sun = icon("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M12 2.5v2 M12 19.5v2 M2.5 12h2 M19.5 12h2 M5.3 5.3l1.4 1.4 M17.3 17.3l1.4 1.4 M18.7 5.3l-1.4 1.4 M6.7 17.3l-1.4 1.4")
    val Activity = icon("M13 2.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z M7 21l3-6 3 2v4 M10 15l1-5 4 3h3 M6 12l2-3h4")
    val Steps = icon("M8 3c-2 0-3 2-3 4.5S6 12 6 13.5h4c0-1.5 1-3 1-6S10 3 8 3z M6.5 16.5h3v1a1.5 1.5 0 0 1-3 0z M16 6c2 0 3 2 3 4.500S18 15 18 16.500h-4c0-1.500-1-3-1-6S14 6 16 6z M14.500 19.500h3v0.500a1.500 1.500 0 0 1-3 0z")
    val Flame = icon("M12 3c0.500 4 5.500 5.500 5.500 11a5.500 5.500 0 0 1-11 0c0-2.500 1.500-3.500 2-5.500c1.500 1 2 2 2 3.500c1.500-2 2-5 1.500-9z")
    val Bolt = icon("M13 3L5 13.500h6L10.500 21L19 10.500h-6z")
    val Floors = icon("M3 20h4.500v-4.500H12V11h4.500V6.500H21")
    val Distance = icon("M12 21c-4.500-5-6.500-8-6.500-11a6.500 6.500 0 0 1 13 0c0 3-2 6-6.500 11z M12 7.500a2.500 2.500 0 1 0 0 5a2.500 2.500 0 1 0 0-5z")
    val Drop = icon("M12 3.500c4 4.500 6 7.500 6 10.500a6 6 0 0 1-12 0c0-3 2-6 6-10.500z")
    val Wind = icon("M3 9h11a2.500 2.500 0 1 0-2.500-2.500 M3 13h15a2.500 2.500 0 1 1-2.500 2.500 M3 17h8")
    val Thermo = icon("M10 13.500V5.500a2 2 0 0 1 4 0v8a4 4 0 1 1-4 0z")
    val Gauge = icon("M4 17a8.500 8.500 0 1 1 16 0 M12 15l4-5")

    // Actions
    val Back = icon("M20 12H5 M11 6l-6 6 6 6")
    val Previous = icon("M15 5l-7 7 7 7")
    val Forward = icon("M9 5l7 7-7 7")
    val Check = icon("M5 12.500l4.500 4.500L19 7.500")
    val Plus = icon("M12 5v14 M5 12h14")
    val Minus = icon("M5 12h14")
    val Up = icon("M12 19V5 M6 11l6-6 6 6")
    val Down = icon("M12 5v14 M6 13l6 6 6-6")
    val Chat = icon("M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-7l-5 4v-4H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")
    val Target = icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z")
    val Sync = icon("M4 12a8 8 0 0 1 14-5l2 2 M20 4v5h-5 M20 12a8 8 0 0 1-14 5l-2-2 M4 20v-5h5")
    val Gym = icon("M6.500 7v10 M3.500 9.500v5 M17.500 7v10 M20.500 9.500v5 M6.500 12h11")
    val Search = icon("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M16 16l4.500 4.500")
    val More = icon("M12 5v.01 M12 12v.01 M12 19v.01")
    val Link = icon("M9 8H7a4 4 0 0 0 0 8h2 M15 8h2a4 4 0 0 1 0 8h-2 M8.500 12h7")
    val Swap = icon("M4 8h14 M14 4l4 4-4 4 M20 16H6 M10 12l-4 4 4 4")
    val File = icon("M7 3h7l5 5v13H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M14 3v5h5")
    val Copy = icon("M9 9h10v11H9z M5 15V4h10")
    val Play = icon("M8 5v14l11-7z")
    val Pause = icon("M8 5v14 M16 5v14")
    val Expand = icon("M4 9V4h5 M20 9V4h-5 M4 15v5h5 M20 15v5h-5")
    val Star = icon("M12 4l2.400 5 5.600.700-4.100 3.900 1 5.500-4.900-2.700-4.900 2.700 1-5.500L4 9.700 9.600 9z")
    val Save = icon("M12 4v11 M7 10l5 5 5-5 M5 20h14")
    val Undo = icon("M9 5L4 10l5 5 M4 10h10a5 5 0 0 1 0 10h-3")
    val Close = icon("M6 6l12 12 M18 6L6 18")
    val Note = icon("M5 4h14v16H5z M9 9h6 M9 13h6")
    val Bell = icon("M6 10a6 6 0 0 1 12 0c0 5 2 6 2 7H4c0-1 2-2 2-7z M10 20h4")
    val Layout = icon("M4 5h7v6H4z M13 5h7v9h-7z M4 13h7v6H4z M13 16h7v3h-7z")

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
