package com.fitbittrack.pulse.ui.widget.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Home-screen widgets can't draw free shapes, so each design is drawn into a picture here.
 * Designs are drawn in the space of their size (for example 880 × 420) and scaled to the widget.
 */

enum class Align { Start, Center, End }

/** Colours taken from the phone's wallpaper (Android 12 and newer), for the "Wallpaper" look. */
data class Accent(val ground: Int, val faint: Int, val strong: Int, val medium: Int, val ink: Int) {
    companion object {
        val FALLBACK = Accent(0xFFD7ECD8.toInt(), 0xFFBFDDC3.toInt(), 0xFF2E6B45.toInt(), 0xFF4C8C5F.toInt(), 0xFF10301C.toInt())
        fun of(c: Context): Accent = if (Build.VERSION.SDK_INT < 31) FALLBACK else Accent(
            c.getColor(android.R.color.system_accent1_100), c.getColor(android.R.color.system_accent1_200),
            c.getColor(android.R.color.system_accent1_700), c.getColor(android.R.color.system_accent1_500), c.getColor(android.R.color.system_accent1_900),
        )
    }
}

/** A drawing surface. [w] × [h] is the design's space plus [ex], [ey] when the widget has other proportions. */
class Art(val c: Canvas, val w: Float, val h: Float, size: WidgetSize, private val backgroundAlpha: Float, val accent: Accent) {

    val ex = w - size.w
    val ey = h - size.h
    private val stretchX = w / size.w

    // ---- where things sit when the widget is taller or wider than the design ----

    /** Keeps its distance to the bottom edge. */
    inline fun bottom(draw: () -> Unit) = at(0f, ey, draw)
    /** Stays in the middle. */
    inline fun mid(draw: () -> Unit) = at(ex / 2, ey / 2, draw)
    /** Middle across, bottom down. */
    inline fun midBottom(draw: () -> Unit) = at(ex / 2, ey, draw)
    inline fun midTop(draw: () -> Unit) = at(ex / 2, 0f, draw)
    /** Right edge, top. */
    inline fun right(draw: () -> Unit) = at(ex, 0f, draw)

    inline fun at(dx: Float, dy: Float, draw: () -> Unit) {
        val n = c.save(); c.translate(dx, dy); draw(); c.restoreToCount(n)
    }

    /** Landscape that reaches both sides: pulled wide, and kept at the bottom. */
    fun scene(draw: () -> Unit) = at(0f, ey) { pulled(stretchX, 1f, draw) }

    /** Pulls shapes wide or tall. Circles and words inside keep their own proportions. */
    fun pulled(x: Float, y: Float, draw: () -> Unit) {
        val (bx, by) = pullX to pullY
        val n = c.save(); c.scale(x, y); pullX *= x; pullY *= y
        draw()
        pullX = bx; pullY = by; c.restoreToCount(n)
    }

    private var pullX = 1f
    private var pullY = 1f

    /** Draws without any pull, around one point. */
    fun round(x: Float, y: Float, draw: () -> Unit) {
        val (bx, by) = pullX to pullY
        val n = c.save(); c.scale(1 / pullX, 1 / pullY, x, y); pullX = 1f; pullY = 1f
        draw()
        pullX = bx; pullY = by; c.restoreToCount(n)
    }

    fun rotate(deg: Float, px: Float, py: Float, draw: () -> Unit) {
        val n = c.save(); c.rotate(deg, px, py); draw(); c.restoreToCount(n)
    }

    fun clip(d: String, draw: () -> Unit) {
        val n = c.save(); c.clipPath(path(d)); draw(); c.restoreToCount(n)
    }

    /** The same surface seen as a smaller one, for a picture inside a picture. */
    fun part(w: Float, h: Float, size: WidgetSize) = Art(c, w, h, size, backgroundAlpha, accent)

    // ---- background ----

    fun ground(color: String) = ground(hex(color))
    fun ground(color: Int) = c.drawRect(0f, 0f, w, h, paint(color, backgroundAlpha))
    fun ground(vararg stops: Pair<Float, String>) = c.drawRect(0f, 0f, w, h, paint(Color.BLACK, backgroundAlpha).apply { shader = vertical(0f, h, *stops) })

    // ---- shapes ----

    fun path(d: String): Path = PathParser.createPathFromPathData(d)

    fun fill(d: String, color: String, alpha: Float = 1f) = c.drawPath(path(d), paint(hex(color), alpha))
    fun fill(d: String, color: Int) = c.drawPath(path(d), paint(color, 1f))
    fun fill(d: String, shader: Shader) = c.drawPath(path(d), paint(Color.BLACK, 1f).apply { this.shader = shader })

    fun stroke(d: String, color: String, width: Float, alpha: Float = 1f, dash: FloatArray? = null, round: Boolean = true, shader: Shader? = null) {
        if (d.isEmpty()) return
        c.drawPath(path(d), paint(hex(color), alpha).apply {
            style = Paint.Style.STROKE; strokeWidth = width
            strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
            strokeJoin = if (round) Paint.Join.ROUND else Paint.Join.MITER
            if (dash != null) pathEffect = DashPathEffect(dash, 0f)
            if (shader != null) this.shader = shader
        })
    }

    /** Round dots at each point. */
    fun dots(points: List<Pair<Float, Float>>, color: String, size: Float, alpha: Float = 1f) {
        val p = paint(hex(color), alpha)
        points.forEach { round(it.first, it.second) { c.drawCircle(it.first, it.second, size / 2, p) } }
    }

    fun circle(x: Float, y: Float, r: Float, color: String, alpha: Float = 1f) = round(x, y) { c.drawCircle(x, y, r, paint(hex(color), alpha)) }
    fun circle(x: Float, y: Float, r: Float, shader: Shader) = c.drawCircle(x, y, r, paint(Color.BLACK, 1f).apply { this.shader = shader })
    fun ring(x: Float, y: Float, r: Float, color: String, width: Float, alpha: Float = 1f, dash: FloatArray? = null) =
        round(x, y) { c.drawCircle(x, y, r, paint(hex(color), alpha).apply { style = Paint.Style.STROKE; strokeWidth = width; if (dash != null) pathEffect = DashPathEffect(dash, 0f) }) }
    fun oval(x: Float, y: Float, rx: Float, ry: Float, color: String, width: Float = 0f, alpha: Float = 1f) =
        c.drawOval(RectF(x - rx, y - ry, x + rx, y + ry), paint(hex(color), alpha).apply { if (width > 0) { style = Paint.Style.STROKE; strokeWidth = width } })
    fun rect(x: Float, y: Float, rw: Float, rh: Float, color: String, r: Float = 0f) = c.drawRoundRect(RectF(x, y, x + rw, y + rh), r, r, paint(hex(color), 1f))

    fun vertical(y0: Float, y1: Float, vararg stops: Pair<Float, String>): Shader =
        LinearGradient(0f, y0, 0f, y1, stops.map { hex(it.second) }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
    fun radial(x: Float, y: Float, r: Float, vararg stops: Pair<Float, String>): Shader =
        RadialGradient(x, y, r, stops.map { hex(it.second) }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)

    // ---- words ----

    /** One line. [top] and [line] work like on the design canvas: the line box starts at [top]. Shrinks to fit [max]. Returns its width. */
    fun text(
        s: String, x: Float, top: Float, size: Float, color: String, line: Float = size * 1.2f, weight: Int = 600,
        align: Align = Align.Start, max: Float = 10_000f, spacing: Float = 0f,
    ): Float = text(s, x, top, size, hex(color), line, weight, align, max, spacing)

    fun text(
        s: String, x: Float, top: Float, size: Float, color: Int, line: Float = size * 1.2f, weight: Int = 600,
        align: Align = Align.Start, max: Float = 10_000f, spacing: Float = 0f,
    ): Float {
        val p = textPaint(size, weight, spacing).apply { this.color = color }
        val full = p.measureText(s)
        if (full > max) p.textSize = size * max / full
        val width = min(full, max)
        val fm = p.fontMetrics
        val base = top + (line - (fm.descent - fm.ascent)) / 2 - fm.ascent
        val left = when (align) { Align.Start -> x; Align.Center -> x - width / 2; Align.End -> x - width }
        round(x, top + line / 2) { c.drawText(s, left, base, p) }
        return width
    }

    /** Up to two lines, broken at spaces; shrinks when that is not enough. Returns the height used. */
    fun lines(s: String, x: Float, top: Float, size: Float, color: String, line: Float, max: Float, weight: Int = 700, spacing: Float = 0f): Float {
        val p = textPaint(size, weight, spacing)
        if (p.measureText(s) <= max) { text(s, x, top, size, color, line, weight, max = max, spacing = spacing); return line }
        val words = s.split(" ")
        var cut = 1
        var best = Float.MAX_VALUE
        for (i in 1 until words.size) {
            val a = p.measureText(words.take(i).joinToString(" "))
            val b = p.measureText(words.drop(i).joinToString(" "))
            // The most even split.
            val score = max(a, b)
            if (score < best) { best = score; cut = i }
        }
        text(words.take(cut).joinToString(" "), x, top, size, color, line, weight, max = max, spacing = spacing)
        text(words.drop(cut).joinToString(" "), x, top + line, size, color, line, weight, max = max, spacing = spacing)
        return line * 2
    }

    fun textWidth(s: String, size: Float, weight: Int = 600, spacing: Float = 0f) = textPaint(size, weight, spacing).measureText(s)

    private fun textPaint(size: Float, weight: Int, spacing: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, weight, false)
        textSize = size
        letterSpacing = spacing / size
        fontFeatureSettings = "tnum"
    }

    private fun paint(color: Int, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt()
    }

    companion object {
        fun hex(s: String): Int = Color.parseColor(s)
        fun pt(x: Float, y: Float) = "%.1f %.1f".format(java.util.Locale.ROOT, x, y)
    }
}

object ArtRenderer {

    /** The largest picture a widget gets. Bigger ones are refused by some home screens. */
    private const val MAX_PIXELS = 900_000f

    /** How far a design may grow past its own proportions before it is centred with empty space around it. */
    private const val MAX_STRETCH = 1.35f

    fun render(context: Context, config: WidgetConfig, data: ArtData, widthPx: Int, heightPx: Int): Bitmap {
        val size = config.design.size
        val shrink = min(1f, sqrt(MAX_PIXELS / (widthPx.toFloat() * heightPx).coerceAtLeast(1f)))
        val bw = (widthPx * shrink).roundToInt().coerceAtLeast(1)
        val bh = (heightPx * shrink).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(bw, bh)
        val c = Canvas(bitmap)

        val s = min(bw.toFloat() / size.w, bh.toFloat() / size.h)
        val w = min(bw / s, size.w * MAX_STRETCH)
        val h = min(bh / s, size.h * MAX_STRETCH)
        c.translate((bw - w * s) / 2, (bh - h * s) / 2)
        c.scale(s, s)

        val finish = finish(config.look)
        val layer = if (finish != null) c.saveLayer(0f, 0f, w, h, Paint().apply { colorFilter = finish }) else c.save()
        // Four tiny widgets in a row bring their own corners.
        if (config.design != WidgetDesign.TinyStrip || !data.signedIn) c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), CORNER, CORNER, Path.Direction.CW) })
        val art = Art(c, w, h, size, config.background / 100f, Accent.of(context))
        if (data.signedIn) paint(art, config, data) else signIn(art, size)
        c.restoreToCount(layer)
        return bitmap
    }

    private const val CORNER = 60f

    private fun finish(look: WidgetLook): ColorMatrixColorFilter? = when (look) {
        WidgetLook.Soft -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.5f) })
        WidgetLook.Mono -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        WidgetLook.Dim -> ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.68f, 0.68f, 0.72f, 1f) })
        else -> null
    }

    private fun signIn(a: Art, size: WidgetSize) {
        a.ground("#141B26")
        a.mid {
            val x = size.w / 2f
            val y = size.h / 2f
            a.stroke("M${x - 28} ${y - 26}h14l9 -20l11 36l9 -16h13", "#FF5C74", 7f)
            a.text("Open Pulse", x, y + 18, if (size.w < 300) 27f else 34f, "#EEF2F8", align = Align.Center, weight = 700, max = size.w - 24f)
            if (size.w >= 400 && size.h >= 400) a.text("to sign in", x, y + 62, 28f, "#B4BDCC", align = Align.Center, weight = 500)
        }
    }

    private fun paint(a: Art, config: WidgetConfig, d: ArtData) = when (config.design) {
        WidgetDesign.Sky -> ArtDay.sky(a, d)
        WidgetDesign.Bloom -> ArtDay.bloom(a, d, config)
        WidgetDesign.Pip -> ArtDay.pip(a, d)
        WidgetDesign.Garden -> ArtDay.garden(a, d)
        WidgetDesign.Orbit -> ArtDay.orbit(a, d)
        WidgetDesign.Poster -> ArtDay.poster(a, d)
        WidgetDesign.StreakStars -> ArtDay.streakStars(a, d)
        WidgetDesign.Signals -> ArtDay.signals(a, d)
        WidgetDesign.AskBar -> ArtSmall.askBar(a)
        WidgetDesign.Band -> ArtSmall.band(a, d)
        WidgetDesign.TinyStrip -> ArtSmall.tinyStrip(a, d)
        WidgetDesign.TinySteps -> ArtSmall.tinySteps(a, d)
        WidgetDesign.TinyHeart -> ArtSmall.tinyHeart(a, d)
        WidgetDesign.TinyMoon -> ArtSmall.tinyMoon(a, d)
        WidgetDesign.TinySync -> ArtSmall.tinySync(a, d)
        WidgetDesign.Trail -> ArtMove.trail(a, d)
        WidgetDesign.BigFill -> ArtMove.bigFill(a, d)
        WidgetDesign.StepCity -> ArtMove.stepCity(a, d)
        WidgetDesign.Stairs -> ArtMove.stairs(a, d)
        WidgetDesign.Flame -> ArtMove.flame(a, d)
        WidgetDesign.Road -> ArtMove.road(a, d)
        WidgetDesign.Race -> ArtMove.race(a, d)
        WidgetDesign.StepWave -> ArtMove.stepWave(a, d)
        WidgetDesign.Fuse -> ArtMove.fuse(a, d)
        WidgetDesign.HeatLine -> ArtHeart.heatLine(a, d)
        WidgetDesign.HeartDial -> ArtHeart.heartDial(a, d)
        WidgetDesign.StillWater -> ArtHeart.stillWater(a, d)
        WidgetDesign.DeepDive -> ArtSleep.deepDive(a, d)
        WidgetDesign.Moon -> ArtSleep.moon(a, d)
        WidgetDesign.SleepClock -> ArtSleep.sleepClock(a, d)
        WidgetDesign.Hourglass -> ArtSleep.hourglass(a, d)
        WidgetDesign.SevenMoons -> ArtSleep.sevenMoons(a, d)
    }
}
