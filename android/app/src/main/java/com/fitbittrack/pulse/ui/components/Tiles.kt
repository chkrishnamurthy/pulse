package com.fitbittrack.pulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fitbittrack.pulse.ui.model.StageShares
import com.fitbittrack.pulse.ui.theme.BodyFamily
import com.fitbittrack.pulse.ui.theme.DisplayFamily
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** Big number with a small unit on the same baseline. Always one line: it shrinks instead of wrapping. */
@Composable
fun BigValue(value: String, unit: String?, size: Int = 28) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BasicText(
        text = buildAnnotatedString {
            append(value)
            if (unit != null) withStyle(SpanStyle(fontSize = 0.48.em, fontWeight = FontWeight.Medium, fontFamily = BodyFamily, color = muted, letterSpacing = 0.sp)) { append(" $unit") }
        },
        modifier = Modifier.fillMaxWidth(),
        style = TextStyle(
            fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = (-0.5).sp,
            color = MaterialTheme.colorScheme.onSurface, fontFeatureSettings = "tnum",
        ),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = size.sp, stepSize = 1.sp),
    )
}

/** Tiles side by side, all as tall as the tallest one. */
@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.gap), content = content)
}

/** The standard metric tile: label, big value, and an optional extra line held at the bottom edge. */
@Composable
fun MetricTile(
    label: String,
    labelColor: Color,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    size: Int = 26,
    footer: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(Space.card),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MetricLabel(label, labelColor)
        BigValue(value, unit, size)
        if (footer != null) {
            Spacer(Modifier.weight(1f))
            footer()
        }
    }
}

/** Horizontal progress bar; [fraction] is clamped to 0..1. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Int = 8) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp))
            .background(PulseTheme.colors.track),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height.dp).clip(RoundedCornerShape(height.dp)).background(color))
    }
}

/** Deep / light / REM / awake as one segmented bar. */
@Composable
fun StageBar(shares: StageShares, modifier: Modifier = Modifier) {
    val c = PulseTheme.colors
    Row(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(shares.deep to c.sleepDeep, shares.light to c.sleepLight, shares.rem to c.sleepRem, shares.awake to c.sleepAwake)
            .filter { it.first > 0f }
            .forEach { (w, color) -> Box(Modifier.weight(w).height(8.dp).background(color)) }
    }
}

/** Day / Week / Month style switch, in Pulse's own colours. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) scheme.primary else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(o) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Shown on tab pages while they draw sample numbers instead of the user's data. */
@Composable
fun SampleBanner(onConnect: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Sample data",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onConnect != null) TextButton(onClick = onConnect) { Text("Connect Google") }
    }
}
