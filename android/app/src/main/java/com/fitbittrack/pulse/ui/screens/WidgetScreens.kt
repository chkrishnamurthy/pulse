package com.fitbittrack.pulse.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.RowGroup
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.goals.GoalMetric
import com.fitbittrack.pulse.ui.theme.PulseTheme
import com.fitbittrack.pulse.ui.widget.art.ArtData
import com.fitbittrack.pulse.ui.widget.art.ArtDataLoader
import com.fitbittrack.pulse.ui.widget.art.ArtRenderer
import com.fitbittrack.pulse.ui.widget.art.RefreshSpot
import com.fitbittrack.pulse.ui.widget.art.WidgetConfig
import com.fitbittrack.pulse.ui.widget.art.WidgetDesign
import com.fitbittrack.pulse.ui.widget.art.WidgetGroup
import com.fitbittrack.pulse.ui.widget.art.WidgetLook
import com.fitbittrack.pulse.ui.widget.art.WidgetSize
import com.fitbittrack.pulse.ui.widget.art.WidgetTap
import com.fitbittrack.pulse.ui.widget.art.spoken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The data every widget picture on these pages is drawn from. Null while loading. */
@Composable
fun rememberWidgetData(): ArtData? {
    val context = LocalContext.current
    val data by produceState<ArtData?>(null) { value = ArtDataLoader.preview(context) }
    return data
}

/** One widget, drawn exactly as on the home screen. */
@Composable
fun WidgetPicture(config: WidgetConfig, data: ArtData?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalResources.current.displayMetrics.density
    val size = config.design.size
    // The width a four-cell widget has on a phone, about 360 dp.
    val px = (size.w / 880f * 360 * density).toInt()
    val picture by produceState<ImageBitmap?>(null, config, data) {
        if (data != null) value = withContext(Dispatchers.Default) { ArtRenderer.render(context, config, data, px, px * size.h / size.w) }.asImageBitmap()
    }
    Box(modifier.aspectRatio(size.w.toFloat() / size.h).semantics { contentDescription = data?.let { spoken(config.design, it) } ?: config.design.label }) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer))
    }
}

/** You → Widgets: every design, and the ones already on the home screen. */
@Composable
fun WidgetGalleryScreen(
    placed: List<Pair<Int, WidgetConfig>>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPick: (WidgetDesign) -> Unit,
    onEdit: (Int) -> Unit,
) {
    val data = rememberWidgetData()
    var group by rememberSaveable { mutableStateOf<WidgetGroup?>(null) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(PulseIcons.Back, contentDescription = "Back") }
            Text("Widgets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("All", group == null) { group = null }
            WidgetGroup.entries.forEach { g -> Choice(g.label, group == g) { group = g } }
        }
        val designs = WidgetDesign.entries.filter { group == null || it.group == group }.sortedBy { ORDER.indexOf(it.size) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4), modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (placed.isNotEmpty() && group == null) item(span = { GridItemSpan(4) }, contentType = "placed") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("On your home screen", style = MaterialTheme.typography.titleMedium)
                        RowGroup(placed.map { (id, config) ->
                            {
                                Row(
                                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onEdit(id) }.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(Modifier.width(96.dp).height(52.dp), contentAlignment = Alignment.Center) { WidgetPicture(config, data, if (config.design.size.w > config.design.size.h * 1.8f) Modifier.fillMaxWidth() else Modifier.height(52.dp)) }
                                    Column(Modifier.weight(1f)) {
                                        Text(config.design.label, style = MaterialTheme.typography.titleSmall)
                                        Text("Tap to change", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                                    }
                                    Icon(PulseIcons.Forward, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                        }, dividerInset = 16)
                        Text("Add another", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                items(designs, key = { it.name }, span = { GridItemSpan(it.size.w / 210 + if (it.size == WidgetSize.Tiny) 1 else 0) }, contentType = { it.size }) { design ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onPick(design) },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WidgetPicture(WidgetConfig(design), data, Modifier.fillMaxWidth())
                        val small = design.size == WidgetSize.Tiny
                        Column(Modifier.padding(horizontal = 4.dp).padding(bottom = 10.dp)) {
                            Text(design.label, style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!small) Text(design.about, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private val ORDER = listOf(WidgetSize.Wide, WidgetSize.Square, WidgetSize.Large, WidgetSize.Strip, WidgetSize.Pill, WidgetSize.Tiny)

/** Sets up one widget. The preview changes with every choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetSetupScreen(
    config: WidgetConfig,
    /** Other drawings of the same size to switch to. Empty = the drawing is fixed. */
    choices: List<WidgetDesign>,
    contentPadding: PaddingValues,
    action: String,
    onBack: () -> Unit,
    onChange: (WidgetConfig) -> Unit,
    onAction: (ArtData) -> Unit,
    note: String? = null,
) {
    val data = rememberWidgetData()
    val scheme = MaterialTheme.colorScheme
    val design = config.design
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Box(Modifier.weight(1f)) {
            DetailPage(design.label, PaddingValues(), onBack) {
                // A picture of a wallpaper, so see-through backgrounds can be judged.
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 200.dp).clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFF2A65E), Color(0xFF2B6A78), Color(0xFF113A4C)))).padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.then(if (design.size.w > 500) Modifier.fillMaxWidth() else Modifier.width((design.size.w / 880f * 360).dp))) {
                        WidgetPicture(config, data, Modifier.fillMaxWidth())
                        if (config.refresh) {
                            val where = when (design.refreshSpot) { RefreshSpot.TopEnd -> Alignment.TopEnd; RefreshSpot.BottomEnd -> Alignment.BottomEnd; RefreshSpot.BottomStart -> Alignment.BottomStart }
                            Box(Modifier.align(where).padding(12.dp).size(32.dp).background(Color(0x66000000), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(PulseIcons.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Text("${design.about} Size ${design.size.cells}.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)

                if (choices.size > 1) {
                    SectionTitle("Picture")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        choices.forEach { d ->
                            val on = d == design
                            Column(
                                Modifier.width(if (d.size.w > 500) 180.dp else 104.dp).clip(RoundedCornerShape(16.dp))
                                    .border(2.dp, if (on) scheme.secondary else Color.Transparent, RoundedCornerShape(16.dp))
                                    .clickable(role = Role.RadioButton) { if (!on) onChange(WidgetConfig(d)) }.semantics { selected = on }.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                WidgetPicture(WidgetConfig(d), data, Modifier.fillMaxWidth())
                                Text(d.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                SectionTitle("Look")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    design.looks.forEach { l -> Choice(l.label, config.look == l) { onChange(config.copy(look = l)) } }
                }

                if (design == WidgetDesign.Bloom) {
                    SectionTitle("Petals to show")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GoalMetric.entries.forEach { m ->
                            val on = m in config.petals
                            // At least one petal stays.
                            Choice(m.label, on, Role.Checkbox) { (if (on) config.petals - m else config.petals + m).let { if (it.isNotEmpty()) onChange(config.copy(petals = it)) } }
                        }
                    }
                }

                if (design.clear && config.look != WidgetLook.Clear) {
                    SectionTitle("Background")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Slider(
                            value = config.background.toFloat(), onValueChange = { onChange(config.copy(background = it.toInt())) }, valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Background" },
                        )
                        Text(when (config.background) { 0 -> "Hidden"; 100 -> "Solid"; else -> "${config.background}%" }, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                    }
                }

                SectionTitle("A tap opens")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetTap.entries.forEach { t -> Choice(t.label, config.tap == t) { onChange(config.copy(tap = t)) } }
                }

                if (design.canRefresh) RowGroup(listOf({ SwitchRow("Refresh button", "Syncs right from the widget", config.refresh) { onChange(config.copy(refresh = it)) } }))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = PulseTheme.colors.good)
            Button(onClick = { data?.let(onAction) }, enabled = data != null, modifier = Modifier.widthIn(max = 608.dp).fillMaxWidth().height(52.dp)) { Text(action) }
        }
    }
}

/** A round button that is either chosen or not. */
@Composable
private fun Choice(label: String, on: Boolean, role: Role = Role.RadioButton, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.clip(CircleShape).background(if (on) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(role = role, onClick = onClick).semantics { selected = on }.heightIn(min = 48.dp).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurface) }
}
