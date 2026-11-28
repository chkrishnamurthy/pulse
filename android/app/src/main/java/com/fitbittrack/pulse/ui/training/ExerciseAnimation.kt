package com.fitbittrack.pulse.ui.training

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitbittrack.pulse.training.EdbPictures
import com.fitbittrack.pulse.training.EdbRules
import com.fitbittrack.pulse.training.Picture
import com.fitbittrack.pulse.training.TrainingGraph
import com.fitbittrack.pulse.ui.components.IconBadge
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.theme.PulseTheme
import kotlinx.coroutines.delay
import pl.droidsonroids.gif.GifDrawable
import java.io.File

/** The ExerciseDB id behind a Pulse exercise: its own id for ExerciseDB rows, else a tie. */
internal fun edbIdOf(exerciseId: String, ties: Map<String, String>): String? = EdbRules.edbIdOf(exerciseId) ?: ties[exerciseId]

/** The saved animation of [edbId], once it is there. Downloads it when [fetch] and online. */
@Composable
private fun rememberAnimationFile(edbId: String?, fetch: Boolean): Pair<File?, Picture> {
    val context = LocalContext.current
    val store = remember { TrainingGraph.exerciseDb(context) }
    val saves by store.saves.collectAsStateWithLifecycle()
    var trying by remember(edbId) { mutableStateOf(false) }
    val saved = edbId != null && remember(edbId, saves) { store.saved(edbId) }
    var online by remember { mutableStateOf(store.online()) }
    // Offline now: look again every few seconds, so the animation comes in once the phone is back online.
    LaunchedEffect(edbId, saved, fetch) {
        while (fetch && edbId != null && !saved) { online = store.online(); delay(5_000) }
    }
    val pick = EdbPictures.pick(edbId, saved, online = fetch && online)
    LaunchedEffect(edbId, pick) {
        if (edbId != null && pick == Picture.Download && !trying) { trying = true; store.ensure(edbId); trying = false }
    }
    return (if (saved && edbId != null) store.file(edbId) else null) to pick
}

/** A GIF from a file. [speed] 1 is normal; [playing] false holds the frame. */
@Composable
private fun Gif(file: File, playing: Boolean, speed: Float, label: String, modifier: Modifier) {
    val drawable = remember(file) { runCatching { GifDrawable(file) }.getOrNull() }
    DisposableEffect(drawable) { onDispose { drawable?.recycle() } }
    if (drawable == null) { Drawing(label, modifier, false); return }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = label } },
        modifier = modifier.background(Color.White),
        update = { v ->
            v.setImageDrawable(drawable)
            drawable.setSpeed(speed)
            if (playing) drawable.start() else drawable.stop()
        },
    )
}

/** What shows when there is no animation yet. */
@Composable
private fun Drawing(label: String, modifier: Modifier, later: Boolean, loading: Boolean = false) {
    val c = PulseTheme.colors
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
            IconBadge(PulseIcons.Gym, c.activeLabel, 48)
            if (later) Text(
                "The animation downloads when you are online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The big animation on an exercise page: loops, with pause, half speed and full screen. */
@Composable
fun ExerciseAnimation(edbId: String?, name: String, modifier: Modifier = Modifier) {
    val (file, pick) = rememberAnimationFile(edbId, fetch = true)
    var playing by rememberSaveable { mutableStateOf(true) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var full by rememberSaveable { mutableStateOf(false) }
    val label = "$name, how-to animation"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(20.dp))) {
            if (file != null) {
                Gif(file, playing, speed, label, Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (playing) "Pause" else "Play", icon = if (playing) PulseIcons.Pause else PulseIcons.Play) { playing = !playing }
                    Pill(if (speed < 1f) "Normal speed" else "Slow", text = if (speed < 1f) "1×" else "½×") { speed = if (speed < 1f) 1f else 0.5f }
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)) { Pill("Full screen", icon = PulseIcons.Expand) { full = true } }
            } else Drawing(label, Modifier.fillMaxSize(), later = pick == Picture.Drawing && edbId != null, loading = pick == Picture.Download)
        }
        if (edbId != null) Text(EdbRules.CREDIT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (full && file != null) FullScreen(file, label, speed) { full = false }
}

@Composable
private fun Pill(label: String, text: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(width = if (text != null) 52.dp else 44.dp, height = 44.dp).clip(CircleShape).background(Color(0xB3111827))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        else Text(text.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun FullScreen(file: File, label: String, speed: Float, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            Gif(file, true, speed, label, Modifier.fillMaxSize())
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("Close", color = Color(0xFF111827)) }
        }
    }
}

/** Small picture for lists and workout cards. Still in lists so scrolling stays smooth; moving where asked. */
@Composable
fun ExerciseThumb(edbId: String?, name: String, size: Int = 48, moving: Boolean = false, onClick: (() -> Unit)? = null) {
    val (file, _) = rememberAnimationFile(edbId, fetch = moving)
    val shape = RoundedCornerShape(12.dp)
    val m = Modifier.size(size.dp).clip(shape).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "How to do $name", onClick = onClick) else Modifier)
    if (file != null) Gif(file, moving, 1f, "$name, picture", m)
    else Box(m.background(PulseTheme.colors.activeLabel.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(PulseIcons.Gym, contentDescription = if (onClick != null) "How to do $name" else null, tint = PulseTheme.colors.activeLabel, modifier = Modifier.size((size * 0.5f).dp))
    }
}
