package com.fitbittrack.pulse.ui.widget.art

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import com.fitbittrack.pulse.MainActivity
import com.fitbittrack.pulse.R
import com.fitbittrack.pulse.ui.format.Fmt
import com.fitbittrack.pulse.ui.widget.SyncNowAction

/*
 * The drawn home-screen widgets. One receiver per size; which drawing a widget shows is the user's choice (WidgetStore).
 */

sealed class ArtWidget(private val size: WidgetSize) : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val store = WidgetStore(context)
        val first = store.forWidget(widgetId, size) to ArtDataLoader.load(context)
        provideContent {
            // A widget that is still being shown keeps running: new settings or data reach it through the version.
            val version by WidgetStore.version.collectAsState()
            val start = remember { version }
            val now by produceState(first, version) { if (version != start) value = store.forWidget(widgetId, size) to ArtDataLoader.load(context) }
            Content(context, now.first, now.second)
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetStore(context).remove(intArrayOf(GlanceAppWidgetManager(context).getAppWidgetId(glanceId)))
    }
}

class TinyArtWidget : ArtWidget(WidgetSize.Tiny)
class PillArtWidget : ArtWidget(WidgetSize.Pill)
class SquareArtWidget : ArtWidget(WidgetSize.Square)
class StripArtWidget : ArtWidget(WidgetSize.Strip)
class WideArtWidget : ArtWidget(WidgetSize.Wide)
class LargeArtWidget : ArtWidget(WidgetSize.Large)

class TinyArtReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TinyArtWidget() }
class PillArtReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = PillArtWidget() }
class SquareArtReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SquareArtWidget() }
class StripArtReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = StripArtWidget() }
class WideArtReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WideArtWidget() }
class LargeArtReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LargeArtWidget() }

@Composable
private fun Content(context: Context, config: WidgetConfig, data: ArtData) {
    val box = LocalSize.current
    val density = context.resources.displayMetrics.density
    val bitmap = remember(box, config, data) {
        ArtRenderer.render(context, config, data, (box.width.value * density).toInt(), (box.height.value * density).toInt())
    }
    Box(GlanceModifier.fillMaxSize().clickable(open(context, config.tap))) {
        Image(ImageProvider(bitmap), spoken(config.design, data), GlanceModifier.fillMaxSize(), ContentScale.Fit)
        if (config.design == WidgetDesign.AskBar && data.signedIn) AskTaps(context, box.width, box.height)
        if (config.refresh && data.signedIn && box.width >= 110.dp) RefreshButton(config.design.refreshSpot)
    }
}

/** See-through buttons over the three drawn questions. */
@Composable
private fun AskTaps(context: Context, width: Dp, height: Dp) {
    Column(GlanceModifier.fillMaxSize()) {
        Spacer(GlanceModifier.height(height * 0.52f))
        Row(GlanceModifier.fillMaxWidth().height(height * 0.48f)) {
            ArtSmall.QUESTIONS.forEachIndexed { i, q ->
                val share = ArtSmall.ASK_SPLIT[i + 1] - ArtSmall.ASK_SPLIT[i]
                Box(GlanceModifier.width(width * share).height(height * 0.48f).clickable(open(context, WidgetTap.Ask, i)).semantics { contentDescription = q }) {}
            }
        }
    }
}

@Composable
private fun RefreshButton(spot: RefreshSpot) {
    val where = when (spot) { RefreshSpot.TopEnd -> Alignment.TopEnd; RefreshSpot.BottomEnd -> Alignment.BottomEnd; RefreshSpot.BottomStart -> Alignment.BottomStart }
    Box(GlanceModifier.fillMaxSize().padding(4.dp), contentAlignment = where) {
        Box(
            GlanceModifier.size(48.dp).clickable(actionRunCallback<SyncNowAction>()).semantics { contentDescription = "Refresh" },
            contentAlignment = Alignment.Center,
        ) {
            Box(GlanceModifier.size(32.dp).cornerRadius(16.dp).background(ColorProvider(Color(0x66000000), Color(0x66000000))), contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.ic_widget_refresh), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(ColorProvider(Color.White, Color.White)))
            }
        }
    }
}

private fun open(context: Context, tap: WidgetTap, question: Int? = null): Action = actionStartActivity(WidgetOpen.intent(context, tap, question))

/** What a screen reader says for the picture. */
internal fun spoken(design: WidgetDesign, d: ArtData): String {
    if (!d.signedIn) return "Open Pulse to sign in"
    val f = Fmt
    val steps = "${f.count(d.steps)} of ${f.count(d.stepGoal)} steps"
    val sleep = if (d.sleepMinutes == null) "No sleep yet" else "${f.minutes(d.sleepMinutes)} of sleep"
    val heart = "Resting heart rate ${f.count(d.restingHr)}"
    return "${design.label}. " + when (design.group) {
        WidgetGroup.Move -> steps
        WidgetGroup.Heart -> "$heart. HRV ${f.whole(d.hrv)}"
        WidgetGroup.Sleep -> sleep
        WidgetGroup.Day -> "$steps. $sleep. $heart. Streak ${d.streak} days"
    }
}

/** Placing, listing and redrawing the drawn widgets. */
object ArtWidgets {

    private val RECEIVERS = mapOf(
        WidgetSize.Tiny to TinyArtReceiver::class.java, WidgetSize.Pill to PillArtReceiver::class.java, WidgetSize.Square to SquareArtReceiver::class.java,
        WidgetSize.Strip to StripArtReceiver::class.java, WidgetSize.Wide to WideArtReceiver::class.java, WidgetSize.Large to LargeArtReceiver::class.java,
    )

    private fun component(context: Context, size: WidgetSize) = ComponentName(context, RECEIVERS.getValue(size))

    suspend fun refreshAll(context: Context) {
        WidgetStore.changed()
        listOf(TinyArtWidget(), PillArtWidget(), SquareArtWidget(), StripArtWidget(), WideArtWidget(), LargeArtWidget()).forEach { it.updateAll(context) }
    }

    /** The size of one of our placed widgets, null for anything else. */
    fun sizeOf(context: Context, id: Int): WidgetSize? {
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ?: return null
        return RECEIVERS.keys.firstOrNull { component(context, it) == provider }
    }

    /** Widgets on the home screen now, with their settings. */
    fun placed(context: Context): List<Pair<Int, WidgetConfig>> {
        val manager = AppWidgetManager.getInstance(context)
        val store = WidgetStore(context)
        return RECEIVERS.keys.flatMap { size -> manager.getAppWidgetIds(component(context, size)).toList().mapNotNull { id -> store.saved(id)?.let { id to it } } }
    }

    fun count(context: Context): Int = RECEIVERS.keys.sumOf { AppWidgetManager.getInstance(context).getAppWidgetIds(component(context, it)).size }

    fun showHome(context: Context) = context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Asks the phone to place a widget. False when this home screen can't. */
    fun pin(context: Context, config: WidgetConfig, data: ArtData): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        WidgetStore(context).setPending(config)
        val preview = RemoteViews(context.packageName, R.layout.widget_pin_preview).apply {
            setImageViewBitmap(R.id.picture, ArtRenderer.render(context, config, data, config.design.size.w / 2, config.design.size.h / 2))
        }
        return manager.requestPinAppWidget(component(context, config.design.size), Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview) }, null)
    }
}

/** A page a widget asks the app to open. [question] is a place in [ArtSmall.QUESTIONS]: never free text, because any app can start ours. */
data class WidgetOpen(val tap: WidgetTap, val question: String?) {
    companion object {
        private const val TAP = "pulse.widget.tap"
        private const val QUESTION = "pulse.widget.question"

        fun intent(context: Context, tap: WidgetTap, question: Int? = null): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(TAP, tap.name)
            .apply { if (question != null) putExtra(QUESTION, question) }

        fun from(intent: Intent?): WidgetOpen? {
            if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val tap = WidgetTap.entries.firstOrNull { it.name == intent.getStringExtra(TAP) } ?: return null
            return WidgetOpen(tap, ArtSmall.QUESTIONS.getOrNull(intent.getIntExtra(QUESTION, -1)).takeIf { tap == WidgetTap.Ask })
        }
    }
}
