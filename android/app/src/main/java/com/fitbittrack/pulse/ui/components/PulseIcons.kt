package com.fitbittrack.pulse.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons drawn from the design canvas (24×24, 2px stroke). Tinted by the caller. */
object PulseIcons {
    val Today = icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7v5l3 2")
    val Heart = icon("M3 12h4l2-5 4 10 2-5h6")
    val Sleep = icon("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Activity = icon("M13 2.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z M7 21l3-6 3 2v4 M10 15l1-5 4 3h3 M6 12l2-3h4")
    val Trends = icon("M5 20V10 M12 20V4 M19 20v-7")
    val Back = icon("M15 5l-7 7 7 7")

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
