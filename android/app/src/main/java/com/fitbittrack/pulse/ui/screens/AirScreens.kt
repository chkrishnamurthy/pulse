package com.fitbittrack.pulse.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.fitbittrack.pulse.air.AirLevel
import com.fitbittrack.pulse.air.AirProblem
import com.fitbittrack.pulse.air.AirReading
import com.fitbittrack.pulse.air.AirSource
import com.fitbittrack.pulse.air.AirState
import com.fitbittrack.pulse.air.Locator
import com.fitbittrack.pulse.ui.components.DetailPage
import com.fitbittrack.pulse.ui.components.IconBadge
import com.fitbittrack.pulse.ui.components.InfoRow
import com.fitbittrack.pulse.ui.components.PulseCard
import com.fitbittrack.pulse.ui.components.PulseIcons
import com.fitbittrack.pulse.ui.components.SectionTitle
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.model.Units
import com.fitbittrack.pulse.ui.theme.DisplayFamily
import com.fitbittrack.pulse.ui.theme.PulseTheme
import java.time.ZoneId

/** Today's air quality card. */
@Composable
fun AirCard(on: Boolean, state: AirState, units: Units, onEnable: (Boolean) -> Unit, onRefresh: () -> Unit, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val r = state.reading.takeIf { on }
    PulseCard(onClick = onOpen.takeIf { on }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(PulseIcons.Wind, PulseTheme.colors.bodyLabel)
            Column(Modifier.weight(1f)) {
                Text("Air quality", style = MaterialTheme.typography.titleMedium)
                if (r != null) Text(sourceLine(r, units), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (on) Icon(PulseIcons.Forward, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
        }
        if (r != null) {
            AirValue(r)
            AirScale(r.aqi)
            Text(r.level.tip, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        AirPrompt(on, state, onEnable, onRefresh)
    }
}

/** Everything about the reading, where it comes from, and the off switch. */
@Composable
fun AirScreen(on: Boolean, state: AirState, units: Units, contentPadding: PaddingValues, onBack: () -> Unit, onEnable: (Boolean) -> Unit, onRefresh: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val r = state.reading.takeIf { on }
    DetailPage("Air quality", contentPadding, onBack) {
        PulseCard {
            if (r != null) {
                AirValue(r)
                AirScale(r.aqi)
                Text(r.level.tip, style = MaterialTheme.typography.bodyMedium)
            }
            AirPrompt(on, state, onEnable, onRefresh)
        }
        if (r != null) {
            PulseCard {
                r.mainPollutant?.let { InfoRow("Main pollutant", it) }
                r.pm25?.let { InfoRow("Fine dust (PM2.5)", "${Fmt.oneDecimal(it)} µg/m³") }
                r.pm10?.let { InfoRow("Dust (PM10)", "${Fmt.oneDecimal(it)} µg/m³") }
                when (val s = r.source) {
                    is AirSource.Station -> {
                        InfoRow("Distance", Fmt.distance(s.distanceMeters, units).let { (v, u) -> "about $v $u" })
                        InfoRow("Measured", Fmt.ago(r.at))
                        Text("Station: ${s.name}", style = MaterialTheme.typography.bodyMedium)
                        Text("Measured outdoors at the station. The air where you are can be different.", style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    AirSource.Estimate -> {
                        InfoRow("Kind", "Computer estimate")
                        InfoRow("For the hour from", estimateHour(r))
                        Text(
                            "No nearby station was used. This is worked out for a wide area (11 to 45 km), so it can miss local smoke or traffic. The index looks at dust over the last 24 hours; the amounts are for this hour.",
                            style = MaterialTheme.typography.bodyMedium, color = muted,
                        )
                    }
                }
                r.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                Text(credit(r), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }

        SectionTitle("What the colours mean")
        PulseCard {
            AirLevel.entries.forEachIndexed { i, level ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(12.dp).background(airColor(level), CircleShape))
                    Text(level.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    val from = if (i == 0) 0 else AirLevel.entries[i - 1].top + 1
                    Text(if (level == AirLevel.Hazardous) "$from+" else "$from–${level.top}", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
            Text("US air quality index. Outdoor air only: your band has no air sensor.", style = MaterialTheme.typography.bodySmall, color = muted)
        }

        SectionTitle("Your privacy")
        PulseCard {
            Text(
                "Pulse uses your rough location only while this page or Today is open. It sends a rounded position, about 1 km to the station service and about 11 km to the estimate service. No name, account or health data is sent, and the reading is not saved.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
        }
        if (on) OutlinedButton(onClick = { onEnable(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Turn off air quality") }
    }
}

/** The turn-on button, waiting, and what to do when something is in the way. */
@Composable
private fun AirPrompt(on: Boolean, state: AirState, onEnable: (Boolean) -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refused = !ok
        // Turned on only once allowed, so saying no leaves nothing running.
        if (ok) {
            onEnable(true)
            onRefresh()
        }
    }
    val allow = {
        if (Locator(context).allowed()) {
            refused = false
            onEnable(true)
            onRefresh()
        } else ask.launch(Locator.PERMISSION)
    }

    when {
        !on || state.problem == AirProblem.NoPermission -> {
            Text(
                if (on) "Pulse is not allowed to use your location."
                else "See how clean the outdoor air is near you. Uses your rough location, only while Pulse is open.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
            if (refused) {
                Text(
                    "Pulse needs location for this. You can allow it in your phone's Settings → Apps → Pulse → Permissions.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = allow) { Text(if (on) "Allow" else "Turn on") }
                if (refused) {
                    TextButton(onClick = { open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("Open Settings") }
                }
            }
        }
        state.problem != null -> {
            Text(
                state.message.orEmpty() + if (state.reading != null) " Showing the last reading." else "",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh, enabled = !state.loading) { Text(if (state.loading) "Checking…" else "Try again") }
                if (state.problem == AirProblem.LocationOff) {
                    TextButton(onClick = { open(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Open Settings") }
                }
            }
        }
        state.reading == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Checking the air near you…", style = MaterialTheme.typography.bodyMedium, color = muted)
        }
    }
}

@Composable
private fun AirValue(r: AirReading) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Fmt.count(r.aqi), style = MaterialTheme.typography.displaySmall.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(airColor(r.level), CircleShape))
                Text(r.level.label, style = MaterialTheme.typography.titleSmall)
            }
            Text("Air quality index (US AQI)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Six coloured steps with a dot at the reading. */
@Composable
internal fun AirScale(aqi: Int) {
    val level = AirLevel.of(aqi)
    val edge = MaterialTheme.colorScheme.surfaceContainer
    val dot = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(18.dp).semantics { contentDescription = "Air quality $aqi, ${level.label}" }) {
        val gap = 3.dp.toPx()
        val step = (size.width - gap * (AirLevel.entries.size - 1)) / AirLevel.entries.size
        val h = 8.dp.toPx()
        val y = size.height / 2
        AirLevel.entries.forEachIndexed { i, l ->
            val x = i * (step + gap)
            drawLine(airColor(l), Offset(x + h / 2, y), Offset(x + step - h / 2, y), strokeWidth = h, cap = StrokeCap.Round)
        }
        val from = if (level.ordinal == 0) 0 else AirLevel.entries[level.ordinal - 1].top
        val part = ((aqi - from).toFloat() / (level.top - from)).coerceIn(0f, 1f)
        val outer = 9.dp.toPx()
        val x = (level.ordinal * (step + gap) + part * step).coerceIn(outer, size.width - outer)
        drawCircle(edge, outer, Offset(x, y))
        drawCircle(dot, 7.dp.toPx(), Offset(x, y))
        drawCircle(airColor(level), 4.5.dp.toPx(), Offset(x, y))
    }
}

private fun sourceLine(r: AirReading, units: Units): String = when (val s = r.source) {
    is AirSource.Station -> Fmt.distance(s.distanceMeters, units).let { (v, u) -> "Measured about $v $u away, ${Fmt.ago(r.at)}" }
    AirSource.Estimate -> "Estimate for your area, ${estimateHour(r)}"
}

private fun estimateHour(r: AirReading) = Fmt.clock(r.at.atZone(ZoneId.systemDefault()).toLocalTime())

private fun credit(r: AirReading): String = when (val s = r.source) {
    is AirSource.Station -> "Data: " + (s.credits.filterNot { "world air quality index" in it.lowercase() } + "World Air Quality Index Project (waqi.info)").joinToString(", ")
    AirSource.Estimate -> "Data: Open-Meteo.com (CC BY 4.0), from CAMS (Copernicus Atmosphere Monitoring Service)"
}

internal fun airColor(level: AirLevel): Color = when (level) {
    AirLevel.Good -> Color(0xFF35A35A)
    AirLevel.Moderate -> Color(0xFFE0B000)
    AirLevel.Sensitive -> Color(0xFFEB8420)
    AirLevel.Unhealthy -> Color(0xFFDB4046)
    AirLevel.VeryUnhealthy -> Color(0xFF9A5BC4)
    AirLevel.Hazardous -> Color(0xFF9E3350)
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing on this phone can show it.
    }
}
