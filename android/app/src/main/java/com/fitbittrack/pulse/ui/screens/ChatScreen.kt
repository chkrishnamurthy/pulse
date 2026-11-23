package com.fitbittrack.pulse.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitbittrack.pulse.chat.Answer
import com.fitbittrack.pulse.chat.ChatItem
import com.fitbittrack.pulse.chat.ChatState
import com.fitbittrack.pulse.chat.ModelState
import com.fitbittrack.pulse.chat.Suggestions
import com.fitbittrack.pulse.ui.components.BigValue
import com.fitbittrack.pulse.ui.components.CardHeader
import com.fitbittrack.pulse.ui.components.IconBadge
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.fill
import com.fitbittrack.pulse.ui.model.Tone
import com.fitbittrack.pulse.ui.theme.PulseTheme

@Composable
fun ChatScreen(
    state: ChatState,
    model: ModelState,
    isSample: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onSetup: () -> Unit,
) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    var draft by rememberSaveable { mutableStateOf("") }
    val send = { if (draft.isNotBlank() && !state.busy) { onAsk(draft); draft = "" } }

    // Follow the conversation: a new item or a longer answer scrolls into view.
    val lastLength = (state.items.lastOrNull() as? ChatItem.Reply)?.ai?.length ?: 0
    LaunchedEffect(state.items.size, lastLength / 40) { if (state.items.isNotEmpty()) list.animateScrollToItem(state.items.size) else list.scrollToItem(0) }

    Column(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PulseIcons.Back, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Ask Pulse", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    if (isSample) "Sample data" else "On this phone, private",
                    style = MaterialTheme.typography.bodySmall, color = if (isSample) scheme.onSurfaceVariant else c.good,
                )
            }
            if (state.items.isNotEmpty()) TextButton(onClick = { focus.clearFocus(); onNewChat() }) { Text("New chat") }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize(), state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.items.isEmpty()) item { Home(state, model, onAsk, onSetup) }
                itemsIndexed(state.items) { i, item -> Item(item, last = i == state.items.lastIndex, busy = state.busy, loading = state.loading, onAsk = onAsk) }
                item { Box(Modifier.height(1.dp)) }
            }
        }

        Column(Modifier.fillMaxWidth().background(scheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.widthIn(max = 608.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(scheme.surfaceContainerHigh).heightIn(min = 56.dp).padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = draft, onValueChange = { draft = it.take(300) },
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp).semantics { contentDescription = "Your question" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(scheme.onSurface), maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) Text("Ask about your health data", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                            inner()
                        }
                    },
                )
                val stop = state.busy
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (stop || draft.isNotBlank()) scheme.primary else scheme.surfaceContainerHighest)
                        .clickable(enabled = stop || draft.isNotBlank(), role = Role.Button, onClickLabel = if (stop) "Stop" else "Send") { if (stop) onStop() else send() }
                        .semantics { contentDescription = if (stop) "Stop" else "Send" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (stop) Box(Modifier.size(14.dp).background(scheme.onPrimary, RoundedCornerShape(3.dp)))
                    else Icon(PulseIcons.Up, contentDescription = null, tint = if (draft.isNotBlank()) scheme.onPrimary else scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
            Text("AI can be wrong. Not medical advice.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private val looks = listOf(
    PulseIcons.Sleep to Tone.Sleep, PulseIcons.Flame to Tone.Energy, PulseIcons.Heart to Tone.Heart, PulseIcons.Steps to Tone.Move,
)

@Composable
private fun Home(state: ChatState, model: ModelState, onAsk: (String) -> Unit, onSetup: () -> Unit) {
    val c = PulseTheme.colors
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What would you like to know?", style = MaterialTheme.typography.headlineMedium)
            Text("Ask about your sleep, heart, activity and goals. Your data stays on this phone.", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
        }
        Suggestions.start.forEachIndexed { i, q ->
            val (icon, tone) = looks[i % looks.size]
            PulseCard(onClick = { onAsk(q) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(icon, c.fill(tone))
                    Text(q, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(PulseIcons.Forward, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        when (model) {
            is ModelState.Absent -> PulseCard(onClick = onSetup) {
                CardHeader("Add explanations, free")
                Text(
                    model.problem ?: "Pulse already answers with your exact numbers. Download Google's Gemma AI once (2.6 GB) and it also explains them in words, without internet.",
                    style = MaterialTheme.typography.bodyMedium, color = if (model.problem != null) scheme.error else scheme.onSurfaceVariant,
                )
            }
            is ModelState.Downloading, ModelState.Checking -> PulseCard(onClick = onSetup) {
                CardHeader("AI download", downloadText(model))
                Text("You can already chat. Explanations come when it is ready.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            is ModelState.Unsupported -> Text(model.reason + " Pulse still answers with your exact numbers.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            ModelState.Ready -> state.aiProblem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.error) }
        }
    }
}

@Composable
private fun Item(item: ChatItem, last: Boolean, busy: Boolean, loading: Boolean, onAsk: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    when (item) {
        is ChatItem.Question -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                item.text, style = MaterialTheme.typography.bodyLarge, color = scheme.onPrimary,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(scheme.primary).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        is ChatItem.Old -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.answer, style = MaterialTheme.typography.bodyLarge)
            if (item.ai.isNotEmpty()) Text(item.ai, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
        }
        is ChatItem.Notice -> Notice(item)
        is ChatItem.Reply -> Reply(item, last, busy, loading, onAsk)
    }
}

@Composable
private fun Notice(n: ChatItem.Notice) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val urgent = n.kind == ChatItem.Notice.Kind.Emergency
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (urgent) scheme.errorContainer else scheme.surfaceContainer).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(n.title, style = MaterialTheme.typography.titleMedium, color = if (urgent) scheme.onErrorContainer else scheme.onSurface)
        Text(n.text, style = MaterialTheme.typography.bodyLarge, color = if (urgent) scheme.onSurface else scheme.onSurfaceVariant)
        if (urgent) Button(
            onClick = { try { context.startActivity(Intent(Intent.ACTION_DIAL)) } catch (_: ActivityNotFoundException) { } },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
        ) { Text("Open phone dialler") }
    }
}

@Composable
private fun Reply(r: ChatItem.Reply, last: Boolean, busy: Boolean, loading: Boolean, onAsk: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val a = r.answer
    var used by rememberSaveable(a.direct) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (a.hasData) PulseCard {
            CardHeader(a.title, a.fact)
            BigValue(a.big, a.unit, 36)
            if (a.bars.any { it.value != null }) Bars(a)
            a.barNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        }
        Text(a.direct, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        if (r.ai.isNotEmpty()) Text(r.ai, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        if (r.writing) Text(if (loading) "Starting the AI…" else "Writing…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        // Without the AI (or if what it wrote was not allowed) one plain tip still helps.
        else if (r.ai.isEmpty() && a.ideas.isNotEmpty()) Text("One idea: ${a.ideas.first().replaceFirstChar { it.lowercase() }}.", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))

        if (!r.writing && a.hasData) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("What was used", filled = true) { used = !used }
            Chip("Copy", filled = true) { clipboard.setText(AnnotatedString(listOf(a.direct, r.ai).filter { it.isNotEmpty() }.joinToString("\n\n"))) }
        }
        if (used) PulseCard {
            a.used.forEach { (k, v) -> InfoRow(k, v) }
            Text(
                if (r.ai.isEmpty()) "Pulse worked out these numbers. Nothing left your phone." else "Pulse worked out these numbers. The AI only explained them. Nothing left your phone.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        if (last && !busy) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { a.followUps.forEach { q -> Chip(q, filled = false) { onAsk(q) } } }
    }
}

@Composable
private fun Chip(text: String, filled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .then(if (filled) Modifier.background(scheme.surfaceContainerHigh) else Modifier.border(1.dp, scheme.outline, RoundedCornerShape(24.dp)))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Bars(a: Answer) {
    val c = PulseTheme.colors
    val tint = c.fill(a.tone)
    val values = a.bars.mapNotNull { it.value }
    val max = values.maxOrNull() ?: 1.0
    val min = values.minOrNull() ?: 0.0
    // Amounts start at zero. Measurements (58, 60, 61 bpm) would all look the same, so they start near the lowest.
    fun part(v: Double): Double = if (a.fromZero) (if (max > 0) v / max else 0.0) else if (max > min) 0.3 + 0.7 * (v - min) / (max - min) else 0.65
    val spoken = a.bars.joinToString(", ") { b -> "${b.label}: ${if (b.value == null) "no data" else if (b.reached) "reached" else "below"}" }
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${a.title}. $spoken" }, horizontalArrangement = Arrangement.spacedBy(if (a.bars.size > 10) 2.dp else 8.dp), verticalAlignment = Alignment.Bottom) {
        a.bars.forEach { b ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.height(72.dp), contentAlignment = Alignment.BottomCenter) {
                    val h = b.value?.let { (part(it).coerceIn(0.0, 1.0) * 72).dp.coerceAtLeast(3.dp) } ?: 3.dp
                    Box(
                        Modifier.widthIn(max = 30.dp).fillMaxWidth().height(h).clip(RoundedCornerShape(6.dp))
                            .background(if (b.value == null) c.track else if (b.reached) tint else tint.copy(alpha = 0.42f)),
                    )
                }
                Text(b.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Questions on a detail page that open the chat. */
@Composable
fun AskChips(title: String, questions: List<String>, onAsk: (String) -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        questions.forEach { q -> Chip(q, filled = false) { onAsk(q) } }
    }
}

internal fun downloadText(m: ModelState): String = when (m) {
    is ModelState.Downloading -> "%.1f of %.1f GB".format(m.bytes / 1e9, m.total / 1e9)
    ModelState.Checking -> "Checking the file"
    else -> ""
}
