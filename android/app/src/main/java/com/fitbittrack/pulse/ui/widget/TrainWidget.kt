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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.fitbittrack.pulse.training.PlannedState
import com.fitbittrack.pulse.training.TrainingGraph
import com.fitbittrack.pulse.ui.widget.art.WidgetOpen
import com.fitbittrack.pulse.ui.widget.art.WidgetTap
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/** The next workout and this week. Reads only the training database on this phone. */
class TrainWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 50.dp), DpSize(150.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = load(context)
        provideContent { Content(context, d) }
    }

    data class Data(val label: String, val title: String, val action: String?, val week: List<Pair<String, Int>>, val done: Int, val all: Int)

    companion object {
        /** 0 = nothing, 1 = planned, 2 = done. */
        suspend fun load(context: Context, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): Data {
            val dao = TrainingGraph.repository(context).dao
            val first: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
            val start = today.minusDays(((today.dayOfWeek.value - first.value + 7) % 7).toLong())
            val active = dao.activeSessionNow()
            val plan = dao.activeEnrollmentNow()
            val rows = plan?.let { dao.plannedNow(it.id) }.orEmpty()
            val next = rows.filter { it.state == PlannedState.Planned }.minWithOrNull(compareBy({ it.date }, { it.seq }))
            val doneDays = dao.finished().first().map { it.date }.toSet()
            val open = rows.filter { it.state == PlannedState.Planned }.map { it.date }.toSet()
            val week = (0L..6L).map { start.plusDays(it) }.map { d -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, locale) to if (d.toString() in doneDays) 2 else if (d.toString() in open) 1 else 0 }
            val day = next?.let { LocalDate.parse(it.date) }
            return Data(
                label = when { active != null -> "OPEN WORKOUT"; day == null -> "TRAINING"; day == today -> "TODAY"; day < today -> "MISSED"; day == today.plusDays(1) -> "TOMORROW"; else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)).uppercase(locale) },
                title = active?.title ?: next?.title ?: "No workout planned",
                action = when { active != null -> "Go on"; day != null && day <= today -> "Start"; else -> null },
                week = week, done = week.count { it.second == 2 }, all = week.count { it.second > 0 },
            )
        }
    }

    @Composable
    private fun Content(context: Context, d: Data) {
        val bg = ColorProvider(Color(0xFFFFFFFF), Color(0xFF141B26))
        val text = ColorProvider(Color(0xFF111827), Color(0xFFEEF2F8))
        val muted = ColorProvider(Color(0xFF5B6576), Color(0xFF8C97AA))
        val track = ColorProvider(Color(0xFFE3E7EE), Color(0xFF1E2735))
        val green = ColorProvider(Color(0xFF6AA321), Color(0xFF9FD356))
        val label = ColorProvider(Color(0xFF41700C), Color(0xFFB4E070))
        val pri = ColorProvider(Color(0xFF111827), Color(0xFFEEF2F8))
        val on = ColorProvider(Color(0xFFFFFFFF), Color(0xFF0B1018))
        val size = LocalSize.current
        Column(GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(14.dp).clickable(actionStartActivity(WidgetOpen.intent(context, WidgetTap.Train))), verticalAlignment = Alignment.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(d.label, style = TextStyle(color = label, fontSize = 10.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Text(d.title, style = TextStyle(color = text, fontSize = if (size.width < 150.dp) 16.sp else 20.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                }
                if (d.action != null && size.width >= 250.dp) Box(GlanceModifier.background(pri).cornerRadius(20.dp).padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(d.action, style = TextStyle(color = on, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                }
            }
            if (size.height >= 110.dp) {
                Spacer(GlanceModifier.defaultWeight())
                if (size.width >= 250.dp) Row(GlanceModifier.fillMaxWidth()) {
                    d.week.forEach { (name, mark) ->
                        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(name, style = TextStyle(color = muted, fontSize = 11.sp))
                            Spacer(GlanceModifier.height(4.dp))
                            Box(GlanceModifier.size(20.dp).cornerRadius(10.dp).background(if (mark > 0) green else track), contentAlignment = Alignment.Center) {
                                if (mark < 2) Box(GlanceModifier.size(14.dp).cornerRadius(7.dp).background(bg)) {}
                            }
                        }
                    }
                } else {
                    Text("${d.done} of ${d.all} this week", style = TextStyle(color = muted, fontSize = 12.sp))
                    Spacer(GlanceModifier.height(6.dp))
                    LinearProgressIndicator(if (d.all == 0) 0f else d.done.toFloat() / d.all, GlanceModifier.fillMaxWidth().height(8.dp), green, track)
                }
            }
        }
    }
}

class TrainWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TrainWidget() }
