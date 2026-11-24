package com.fitbittrack.pulse.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.fitbittrack.pulse.MainActivity
import com.fitbittrack.pulse.ui.DataLayerBridge
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.widget.art.ArtWidgets

/*
 * Home-screen widgets, built with Jetpack Glance. Each one is resizable and picks a layout for its size.
 * Colours follow the phone's light/dark setting, using the same palette as the app.
 */

object PulseWidgets {
    /** Redraw every Pulse widget. Call after each successful sync. */
    suspend fun refreshAll(context: Context) {
        SmartWidget().updateAll(context)
        StepsWidget().updateAll(context)
        HeartWidget().updateAll(context)
        SleepWidget().updateAll(context)
        SyncWidget().updateAll(context)
        ArtWidgets.refreshAll(context)
    }
}

// ---- Palette (day = light theme, night = dark theme) ----
private object W {
    val bg = ColorProvider(Color(0xFFFFFFFF), Color(0xFF141B26))
    val text = ColorProvider(Color(0xFF111827), Color(0xFFEEF2F8))
    val muted = ColorProvider(Color(0xFF5B6576), Color(0xFF8C97AA))
    val track = ColorProvider(Color(0xFFE3E7EE), Color(0xFF1E2735))
    val move = ColorProvider(Color(0xFFE8950F), Color(0xFFF5A524))
    val moveLabel = ColorProvider(Color(0xFFA86200), Color(0xFFF5B84A))
    val heart = ColorProvider(Color(0xFFE03A57), Color(0xFFFF5C74))
    val heartLabel = ColorProvider(Color(0xFFC42D49), Color(0xFFFF8A9B))
    val sleep = ColorProvider(Color(0xFF5462E0), Color(0xFF8E9BFF))
    val sleepLabel = ColorProvider(Color(0xFF4350C9), Color(0xFFAEB7FF))
    val body = ColorProvider(Color(0xFF0B7F72), Color(0xFF5FE3D3))
    val good = ColorProvider(Color(0xFF157A45), Color(0xFF5FE0A0))
}

private fun label(color: androidx.glance.unit.ColorProvider) = TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
private fun big(size: Int = 28) = TextStyle(color = W.text, fontSize = size.sp, fontWeight = FontWeight.Bold)
private val small = TextStyle(color = W.muted, fontSize = 12.sp)

/** Refresh button: asks for one background sync. The widgets redraw by themselves when it finishes. */
class SyncNowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        DataLayerBridge.syncNow(context)
    }
}

/** Rounded card that opens the app when tapped. [refresh] adds a refresh button in the corner. */
@Composable
private fun Card(refresh: Boolean = false, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().background(W.bg).cornerRadius(24.dp).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        content()
        // Very small widgets have no room for a button you could hit.
        if (refresh && LocalSize.current.width >= 110.dp) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Box(
                    GlanceModifier.size(40.dp).clickable(actionRunCallback<SyncNowAction>())
                        .semantics { contentDescription = "Refresh" },
                    contentAlignment = Alignment.TopEnd,
                ) { Text("↻", style = TextStyle(color = W.muted, fontSize = 16.sp)) }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: androidx.glance.unit.ColorProvider) {
    LinearProgressIndicator(
        progress = fraction.coerceIn(0f, 1f),
        modifier = GlanceModifier.fillMaxWidth().height(8.dp),
        color = color,
        backgroundColor = W.track,
    )
}

private val SMALL = DpSize(110.dp, 50.dp)
private val SQUARE = DpSize(150.dp, 150.dp)
private val WIDE = DpSize(300.dp, 70.dp)
private val LARGE = DpSize(300.dp, 150.dp)

// ---------- Smart widget (4×2): changes by time of day ----------
class SmartWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SQUARE, LARGE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetDataLoader.load(context)
        provideContent { Card(refresh = d.signedIn) { SmartContent(d) } }
    }
}

@Composable
private fun SmartContent(d: WidgetData) {
    val s = d.smart
    val accent = when (s.kind) {
        SmartState.Kind.Sleep, SmartState.Kind.Evening -> W.sleepLabel
        else -> W.moveLabel
    }
    Column(GlanceModifier.fillMaxSize()) {
        Text(s.label, style = label(accent))
        Spacer(GlanceModifier.height(6.dp))
        Text(s.headline, style = big(if (LocalSize.current.width < 200.dp) 20 else 26), maxLines = 2)
        Spacer(GlanceModifier.height(4.dp))
        Text(s.detail, style = small, maxLines = 2)
        Spacer(GlanceModifier.defaultWeight())
        when (s.kind) {
            SmartState.Kind.Sleep -> Bar((d.sleepMinutes ?: 0).toFloat() / d.sleepGoal, W.sleep)
            SmartState.Kind.Steps, SmartState.Kind.Evening -> Bar((d.steps ?: 0).toFloat() / d.stepGoal, W.move)
            SmartState.Kind.Empty -> Unit
        }
        if (d.signedIn) Text("● synced ${d.syncedAgo}", style = TextStyle(color = W.good, fontSize = 10.sp))
    }
}

// ---------- Steps (2×1, 2×2, 4×1) ----------
class StepsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, SQUARE, WIDE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetDataLoader.load(context)
        provideContent { Card(refresh = d.signedIn) { StepsContent(d) } }
    }
}

@Composable
private fun StepsContent(d: WidgetData) {
    val size = LocalSize.current
    val pct = d.steps?.let { (it * 100 / d.stepGoal.coerceAtLeast(1)).toInt() }
    if (size.width >= 250.dp && size.height < 110.dp) {
        // Wide: goal rings row as bars
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Text("STEPS", style = label(W.moveLabel)); Text(Fmt.count(d.steps), style = big(18))
                Bar((d.steps ?: 0).toFloat() / d.stepGoal, W.move)
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text("ZONE MIN", style = label(W.moveLabel)); Text("${Fmt.count(d.zoneMinutes)}/${d.zoneTarget}", style = big(18))
                Bar((d.zoneMinutes ?: 0).toFloat() / d.zoneTarget, W.move)
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text("SLEEP", style = label(W.sleepLabel)); Text(Fmt.minutes(d.sleepMinutes), style = big(18))
                Bar((d.sleepMinutes ?: 0).toFloat() / d.sleepGoal, W.sleep)
            }
        }
    } else if (size.height < 110.dp) {
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.count(d.steps), style = big(22))
            Text("steps${pct?.let { " · $it%" } ?: ""}", style = small)
        }
    } else {
        Column(GlanceModifier.fillMaxSize()) {
            Text("STEPS", style = label(W.moveLabel))
            Spacer(GlanceModifier.defaultWeight())
            Text(Fmt.count(d.steps), style = big(32))
            Text("of ${Fmt.count(d.stepGoal)}${pct?.let { " · $it%" } ?: ""}", style = small)
            Spacer(GlanceModifier.height(8.dp))
            Bar((d.steps ?: 0).toFloat() / d.stepGoal, W.move)
        }
    }
}

// ---------- Heart (1×1, 2×2) ----------
class HeartWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(60.dp, 60.dp), SQUARE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetDataLoader.load(context)
        provideContent { Card(refresh = d.signedIn) { HeartContent(d) } }
    }
}

@Composable
private fun HeartContent(d: WidgetData) {
    if (LocalSize.current.width < 120.dp) {
        Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.count(d.restingHeartRate), style = TextStyle(color = W.heartLabel, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Text("REST BPM", style = label(W.muted))
        }
        return
    }
    Column(GlanceModifier.fillMaxSize()) {
        Text("RESTING HR", style = label(W.heartLabel))
        Spacer(GlanceModifier.defaultWeight())
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.count(d.restingHeartRate), style = big(36))
            Text(" bpm", style = small)
        }
        // A tiny 7-day trend as bars (Glance has no free-form drawing).
        Row(GlanceModifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.Bottom) {
            val vals = d.restingLast7
            val lo = vals.filterNotNull().minOrNull() ?: 0
            val hi = vals.filterNotNull().maxOrNull()?.takeIf { it > lo } ?: (lo + 1)
            vals.forEach { v ->
                val h = if (v == null) 3 else 6 + (v - lo) * 18 / (hi - lo)
                Box(GlanceModifier.defaultWeight().height(h.dp).padding(horizontal = 2.dp)) {
                    Box(GlanceModifier.fillMaxSize().background(if (v == null) W.track else W.heart).cornerRadius(3.dp)) {}
                }
            }
        }
        Text("HRV ${Fmt.whole(d.hrv)} ms", style = small)
    }
}

// ---------- Last night's sleep (4×1, 2×2) ----------
class SleepWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WIDE, SQUARE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetDataLoader.load(context)
        provideContent { Card(refresh = d.signedIn) { SleepContent(d) } }
    }
}

@Composable
private fun SleepContent(d: WidgetData) {
    val shares = d.sleepShares
    @Composable
    fun stages() {
        if (shares == null) { Bar(0f, W.sleep); return }
        Row(GlanceModifier.fillMaxWidth().height(10.dp)) {
            listOf(shares.deep to W.sleepLabel, shares.light to W.sleep, shares.rem to W.sleepLabel, shares.awake to W.heartLabel)
                .filter { it.first > 0.01f }
                .forEach { (w, c) ->
                    // Glance has no weighted widths beyond defaultWeight; approximate with fixed proportions of 280dp.
                    Box(GlanceModifier.width((w * 260).dp).height(10.dp).background(c)) {}
                }
        }
    }
    if (LocalSize.current.height < 110.dp) {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("LAST NIGHT", style = label(W.sleepLabel))
                Text(Fmt.minutes(d.sleepMinutes), style = big(20))
            }
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight()) { stages() }
        }
    } else {
        Column(GlanceModifier.fillMaxSize()) {
            Text("LAST NIGHT", style = label(W.sleepLabel))
            Spacer(GlanceModifier.defaultWeight())
            Text(Fmt.minutes(d.sleepMinutes), style = big(30))
            Text("SpO2 ${Fmt.whole(d.spo2)}%", style = TextStyle(color = W.body, fontSize = 12.sp))
            Spacer(GlanceModifier.height(8.dp))
            Bar((d.sleepMinutes ?: 0).toFloat() / d.sleepGoal, W.sleep)
        }
    }
}

// ---------- Sync status (1×1) ----------
class SyncWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetDataLoader.load(context)
        provideContent {
            Card {
                Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    Text("●", style = TextStyle(color = if (d.signedIn) W.good else W.muted, fontSize = 16.sp))
                    Text(if (d.signedIn) d.syncedAgo else "sign in", style = TextStyle(color = W.muted, fontSize = 10.sp), maxLines = 2)
                }
            }
        }
    }
}

// ---------- Receivers (one per widget, listed in AndroidManifest.xml) ----------
class SmartWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SmartWidget() }
class StepsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = StepsWidget() }
class HeartWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = HeartWidget() }
class SleepWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SleepWidget() }
class SyncWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SyncWidget() }
