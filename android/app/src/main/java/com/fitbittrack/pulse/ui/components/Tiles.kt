package com.fitbittrack.pulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitbittrack.pulse.ui.model.StageShares
import com.fitbittrack.pulse.ui.theme.DisplayFamily
import com.fitbittrack.pulse.ui.theme.PulseTheme

/** Big number + small unit, e.g. "58 BPM". */
@Composable
fun BigValue(value: String, unit: String?, size: Int = 30) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = size.sp, color = MaterialTheme.colorScheme.onSurface)
        if (unit != null) {
            Text(unit.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

/** The standard metric tile from the design: label, big value, optional extra line below. */
@Composable
fun MetricTile(
    label: String,
    labelColor: Color,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricLabel(label, labelColor)
        BigValue(value, unit)
        footer?.invoke()
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
        listOf(shares.deep to c.sleepDeep, shares.light to c.sleep, shares.rem to c.sleep.copy(alpha = 0.55f), shares.awake to c.heartLabel)
            .filter { it.first > 0f }
            .forEach { (w, color) -> Box(Modifier.weight(w).height(8.dp).background(color)) }
    }
}

/** Day / Week / Month style switch. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(o)) }
        }
    }
}

/** Shown on any screen that is drawing sample numbers instead of the user's data. */
@Composable
fun SampleBanner(onConnect: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Sample data. Connect your Google account to see yours.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (onConnect != null) TextButton(onClick = onConnect) { Text("Connect") }
    }
}
