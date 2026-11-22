package com.fitbittrack.pulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Standard scrolling page: 20dp side padding, 20dp between sections. */
@Composable
fun PulsePage(contentPadding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

/** Mono, upper-case label in a metric colour, e.g. "RESTING HR". */
@Composable
fun MetricLabel(text: String, color: Color) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

/** Screen header: small label above a big title. */
@Composable
fun ScreenHeader(label: String, title: String, labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricLabel(label, labelColor)
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Rounded card on the theme's container colour (white in light, ink panel in dark). */
@Composable
fun PulseCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Green / amber status pill, e.g. "Synced 12 min ago". */
@Composable
fun StatusPill(text: String, dot: Color, container: Color) {
    Row(
        modifier = Modifier
            .background(container, CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = dot)
    }
}
