package com.fitbittrack.pulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The design uses Sora (numbers, titles) and Inter (body). Until those font files are bundled,
// the system family stands in. Numbers are tabular, so columns line up.
val DisplayFamily = FontFamily.SansSerif
val BodyFamily = FontFamily.SansSerif

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(fontFamily = DisplayFamily, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp, fontFeatureSettings = "tnum")

val PulseTypography = Typography(
    displayLarge = style(52, 58, FontWeight.Bold, -1.5),
    headlineMedium = style(28, 34, FontWeight.Bold, -0.4),
    titleLarge = style(20, 26, FontWeight.SemiBold, -0.2),
    titleMedium = style(17, 23, FontWeight.SemiBold),
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(15, 22, FontWeight.Normal),
    bodyMedium = style(13, 19, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(11, 14, FontWeight.Medium),
)
