package com.fitbittrack.pulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The design uses Sora (numbers, titles), Inter (body) and JetBrains Mono (units).
// Phase 6 bundles those font files; until then these system families stand in.
val DisplayFamily = FontFamily.SansSerif
val BodyFamily = FontFamily.SansSerif
val MonoFamily = FontFamily.Monospace

val PulseTypography = Typography(
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 52.sp, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = BodyFamily, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = BodyFamily, fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.5.sp),
)
