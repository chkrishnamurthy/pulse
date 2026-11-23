package com.fitbittrack.pulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One spacing system for every screen. */
object Space {
    val page = 16.dp
    val gap = 12.dp
    val card = 16.dp
    val section = 24.dp
}

val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun PageColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    // Centred and limited in width, so tablets and landscape don't stretch everything.
    Box(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = Space.page, end = Space.page, top = 8.dp, bottom = Space.section),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
            content = content,
        )
    }
}

/** A tab's first page: scrolls as one, with its own header inside. */
@Composable
fun PulsePage(contentPadding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) { PageColumn(Modifier, content) }
}

/** A page opened from another page: fixed top bar with a back arrow, then scrolling content. */
@Composable
fun DetailPage(
    title: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(PulseIcons.Back, contentDescription = "Back") }
            Text(
                title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            )
            actions()
        }
        PageColumn(Modifier.weight(1f), content)
    }
}

/** Header of a tab's first page: big title, small line above, optional things on the right. */
@Composable
fun TabHeader(title: String, label: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        actions()
    }
}

/** Heading between groups of cards. */
@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) {
            Text(
                action, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
    }
}

/** One line that shrinks to fit instead of being cut off (large text settings). */
@Composable
fun FitText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier, style.copy(color = color, textAlign = TextAlign.Center), maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** Small label in a metric's colour. */
@Composable
fun MetricLabel(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Rounded card on the theme's container colour (white in light, ink panel in dark). */
@Composable
fun PulseCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(Space.card),
        verticalArrangement = Arrangement.spacedBy(Space.gap),
        content = content,
    )
}

/** A card title on the left with a quieter fact on the right, on one baseline. */
@Composable
fun CardHeader(title: String, fact: String? = null, factColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.alignByBaseline().weight(1f))
        if (fact != null) Text(fact, style = MaterialTheme.typography.bodyMedium, color = factColor, modifier = Modifier.alignByBaseline())
    }
}

/** A group of rows in one card, with dividers between them. */
@Composable
fun RowGroup(rows: List<@Composable () -> Unit>, dividerInset: Int = 68) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = dividerInset.dp), color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
    }
}

/** Icon · title and subtitle · value · arrow. The standard row on Health, You and Day. */
@Composable
fun ListRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = Space.card, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.gap),
    ) {
        IconBadge(icon, tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        trailing?.invoke()
        if (onClick != null && trailing == null) {
            Icon(PulseIcons.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Round tinted icon. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Int = 40) {
    Box(Modifier.size(size.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

/** Green / amber status pill, e.g. "Synced 12 min ago". */
@Composable
fun StatusPill(text: String, dot: Color, container: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(container, CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = dot, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Label on the left, value on the right. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline().weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alignByBaseline())
    }
}
