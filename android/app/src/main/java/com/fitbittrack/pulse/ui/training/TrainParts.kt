package com.fitbittrack.pulse.ui.training

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitbittrack.pulse.training.DoneSet
import com.fitbittrack.pulse.training.HistoryRow
import com.fitbittrack.pulse.training.Track
import com.fitbittrack.pulse.training.WeightUnit
import com.fitbittrack.pulse.training.Weights
import com.fitbittrack.pulse.training.done
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.Space
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Parts the training pages share. Everything else comes from the app's own components.

@Composable
internal fun locale(): Locale = LocalConfiguration.current.locales[0]

/** "Wed 25 Nov". */
internal fun LocalDate.short(locale: Locale): String = format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

/** "Wed 25". */
internal fun LocalDate.tiny(locale: Locale): String = format(DateTimeFormatter.ofPattern("EEE d", locale))

internal fun LocalDate.near(today: LocalDate, locale: Locale): String = when (this) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> short(locale)
}

internal fun clock(sec: Int): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

internal fun words(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

/** "60 kg: 8, 8, 8", "12, 10", "45, 40 seconds". */
internal fun lastText(sets: List<DoneSet>, track: Track?, unit: WeightUnit): String? {
    val s = sets.filter { it.kind.counts }
    if (s.isEmpty()) return null
    if (track == Track.Time) return s.mapNotNull { it.seconds }.joinToString(", ") + " seconds"
    val reps = s.mapNotNull { it.reps }.joinToString(", ")
    val w = s.mapNotNull { it.grams }.maxOrNull() ?: return reps
    return "${Weights.text(w, unit)}: $reps"
}

internal fun lastRows(rows: List<HistoryRow>, track: Track?, unit: WeightUnit): String? = lastText(rows.map { it.done() }, track, unit)

@Composable
internal fun Hint(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) = Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

@Composable
internal fun MainButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        Text(text)
    }
}

@Composable
internal fun SideButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        Text(text, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A quiet round button, like the app's plus and minus. */
@Composable
internal fun SoftButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    height: Int = 48,
    onClick: () -> Unit,
) {
    Box(
        modifier.heightIn(min = height.dp).clip(CircleShape).background(color).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1) }
}

@Composable
internal fun RadioRow(title: String, sub: String? = null, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().selectable(
            selected = on,
            role = Role.RadioButton,
            onClick = onClick,
        ).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(horizontal = 10.dp).size(20.dp).border(2.dp, if (on) scheme.primary else scheme.onSurfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Box(Modifier.size(10.dp).background(scheme.primary, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> Choices(options: List<T>, isOn: (T) -> Boolean, label: (T) -> String, onPick: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = isOn(o)
            Box(
                Modifier.clip(CircleShape).background(if (on) scheme.primary else scheme.surfaceContainerHigh).clickable(role = Role.RadioButton) { onPick(o) }
                    .semantics { selected = on }.heightIn(min = 48.dp).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurface) }
        }
    }
}

/** A filter chip on the page background, as in Calendar. */
@Composable
internal fun FilterChip(text: String, on: Boolean, dot: Color? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).background(if (on) scheme.primary else scheme.surfaceContainer)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurface, maxLines = 1)
    }
}

/** One line of text to type, in the style of the chat field. */
@Composable
internal fun TextBox(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    label: String = placeholder,
    max: Int = 80,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainerHigh)
            .heightIn(min = 56.dp)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        BasicTextField(
            value = value, onValueChange = { onChange(it.take(max)) }, singleLine = true,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp).semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(scheme.onSurface),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(
            PulseIcons.Close,
            contentDescription = "Clear",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        ) }
    }
}

@Composable
internal fun IconAct(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true, onClick: () -> Unit) =
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f)) }

/** Something to do in a [MoreMenu]. */
internal data class Act(val text: String, val danger: Boolean = false, val run: () -> Unit)

/** Three dots that open a short list of things to do. */
@Composable
internal fun MoreMenu(label: String, items: List<Act>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAct(PulseIcons.More, label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            items.forEach { a ->
                DropdownMenuItem(
                    text = { Text(
                        a.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (a.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    ) },
                    onClick = { open = false; a.run() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PulseSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
        )
    }
}

@Composable
internal fun Ask(title: String, body: String, action: String, onNo: () -> Unit, danger: Boolean = true, no: String = "Cancel", onYes: () -> Unit) = AlertDialog(
    onDismissRequest = onNo, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    title = { Text(title) }, text = { Text(body) },
    confirmButton = { TextButton(onClick = onYes) { Text(
        action,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    ) } },
    dismissButton = { TextButton(onClick = onNo) { Text(no) } },
)

/** Asks for one short text. */
@Composable
internal fun AskText(title: String, start: String, action: String, onNo: () -> Unit, onYes: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onNo, containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title) }, text = { TextBox(text, "Name", { text = it }) },
        confirmButton = { TextButton(onClick = { onYes(text.trim()) }, enabled = text.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancel") } },
    )
}

@Composable
internal fun TopBar(
    title: String,
    onBack: () -> Unit,
    close: Boolean = false,
    sub: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(if (close) PulseIcons.Close else PulseIcons.Back, contentDescription = if (close) "Close" else "Back") }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            sub?.invoke()
        }
        actions()
    }
}

/** A page with a back arrow and a long list that is drawn as it is scrolled. */
@Composable
internal fun ListPage(
    title: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopBar(title, onBack, actions = actions)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = Space.page, end = Space.page, top = 8.dp, bottom = Space.section),
                content = content,
            )
        }
    }
}

/** A row inside a long list that looks like part of one card. */
@Composable
internal fun CardSlice(first: Boolean, last: Boolean, inset: Int = 68, content: @Composable () -> Unit) {
    val top = if (first) 20.dp else 0.dp
    val bottom = if (last) 20.dp else 0.dp
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (!first) HorizontalDivider(Modifier.padding(start = inset.dp), color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

/** A small status on the week strip. */
@Composable
internal fun MarkCircle(mark: DayMark, today: Boolean) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .then(
                if (mark == DayMark.Done) Modifier.background(c.activeLabel)
                else Modifier.border(3.dp, if (mark == DayMark.Planned) c.active else c.track, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (mark == DayMark.Done) Icon(PulseIcons.Check, contentDescription = null, tint = scheme.surfaceContainer, modifier = Modifier.size(18.dp))
        else if (today) Box(Modifier.size(14.dp).background(scheme.primary, CircleShape))
    }
}

@Composable
internal fun Dot(color: Color, size: Int = 8) = Box(Modifier.size(size.dp).background(color, CircleShape))
