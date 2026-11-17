package app.aaps.ui.widget.aimi

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.ui.widget.state.WidgetBgColorClass
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Texts the renderer needs, resolved from string resources by the widget. */
class AimiWidgetTexts(
    val iob: String,
    val basal: String,
    val isf: String,
    val smb: String,
    val pod: String,
    val site: String,
    val sensor: String,
    val tir: String,
    val rangeHours: String,
    val noValue: String,
    val prediction: (String) -> String,
    val hours: (String) -> String,
    val daysLeft: (String) -> String,
    val grace: (String) -> String,
    val overUnits: (String) -> String,
    val units: (String) -> String
)

/**
 * Drawing parts of the AIMI widget card: the card itself, the chart with prediction curves, the trend arrow,
 * pod and sensor bars and the text helpers. The layout that puts them together is [AimiWidgetRenderer].
 * Everything is drawn on one bitmap, because Glance has no dashed lines, arcs, rounded bars or
 * per-segment colours. All sizes are in dp: the canvas is scaled by the density.
 */
class AimiWidgetDraw {

    internal fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, strokeWidth: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.style = style
        this.strokeWidth = strokeWidth
    }

    internal fun textPaint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    internal fun drawCard(c: Canvas, w: Float, h: Float, alpha: Int) {
        val rect = RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        c.drawRoundRect(rect, CORNER, CORNER, paint(withAlpha(CARD_BASE, alpha)))
        c.drawRoundRect(rect, CORNER, CORNER, paint(CARD_BORDER, Paint.Style.STROKE, 0.8f))
    }

    /** A trend arrow drawn as lines: no font needed, so it looks the same on every phone. */
    internal fun drawArrow(c: Canvas, cx: Float, cy: Float, size: Float, arrow: TrendArrow, color: Int) {
        val angle = when (arrow) {
            TrendArrow.TRIPLE_UP, TrendArrow.DOUBLE_UP, TrendArrow.SINGLE_UP -> -90f
            TrendArrow.FORTY_FIVE_UP                   -> -45f
            TrendArrow.FLAT                            -> 0f
            TrendArrow.FORTY_FIVE_DOWN                 -> 45f
            TrendArrow.SINGLE_DOWN, TrendArrow.DOUBLE_DOWN, TrendArrow.TRIPLE_DOWN -> 90f
            else                                       -> return
        }
        val double = arrow in DOUBLE_ARROWS
        val stroke = paint(color, Paint.Style.STROKE, max(2f, size * 0.12f)).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        c.save()
        c.rotate(angle, cx, cy)
        val half = size / 2f
        val head = size * 0.30f
        c.drawLine(cx - half, cy, cx + half, cy, stroke)
        // One head at the tip, a second one just behind it for a double arrow.
        val tips = if (double) listOf(cx + half, cx + half - head * 1.1f) else listOf(cx + half)
        for (tipX in tips) {
            val path = Path().apply {
                moveTo(tipX - head, cy - head)
                lineTo(tipX, cy)
                lineTo(tipX - head, cy + head)
            }
            c.drawPath(path, stroke)
        }
        c.restore()
    }

    // ---- Chart ----

    internal fun drawChart(c: Canvas, area: RectF, data: AimiWidgetData, t: AimiWidgetTexts, maxCurves: Int, topRightText: String, bottomLeftText: String? = null) {
        val histW = area.width() * HISTORY_SHARE
        val futureW = area.width() - histW
        val nowX = area.left + histW
        val curves = data.curves.take(maxCurves)

        // Value range: follows the data, with a margin and a minimum width, so a calm curve is not squeezed
        // into a small part of the chart. The target band then runs off the chart. Only what is drawn counts:
        // points past the right edge must not squash the chart.
        val values = ArrayList<Double>()
        data.history.forEach { values += it.value }
        data.currentValue?.let { values += it }
        curves.forEach { cv -> cv.points.filter { it.timestamp <= data.now + FUTURE_MS }.forEach { values += it.value } }
        val mgdl = data.units == GlucoseUnit.MGDL
        val margin = if (mgdl) 8.0 else 0.45
        val minSpan = if (mgdl) 80.0 else 4.4
        val dataMin: Double
        val dataMax: Double
        if (values.isEmpty()) {
            dataMin = if (mgdl) 40.0 else 2.2
            dataMax = if (mgdl) 220.0 else 12.2
        } else {
            val lo = values.min() - margin
            val hi = values.max() + margin
            val half = max(hi - lo, minSpan) / 2.0
            dataMin = (lo + hi) / 2.0 - half
            dataMax = (lo + hi) / 2.0 + half
        }
        val span = (dataMax - dataMin).coerceAtLeast(0.001)
        val inset = 5f
        fun yOf(v: Double) = area.bottom - inset - ((v - dataMin) / span).toFloat() * (area.height() - 2 * inset)
        fun xOf(ts: Long): Float =
            if (ts <= data.now) area.left + (ts - (data.now - data.historyMs)).toFloat() / data.historyMs * histW
            else nowX + (ts - data.now).toFloat() / FUTURE_MS * futureW

        // Target band and its limits.
        // Kept inside the chart: with the tight scale the band may reach past the chart.
        val bandTop = max(area.top, yOf(data.highMark))
        val bandBottom = min(area.bottom, yOf(data.lowMark))
        if (bandBottom > bandTop) c.drawRoundRect(RectF(area.left, bandTop, area.right, bandBottom), 3f, 3f, paint(withAlpha(EMERALD, 28)))

        // Divider at "now".
        val divider = paint(0xFF475569.toInt(), Paint.Style.STROKE, 0.8f).apply { pathEffect = DashPathEffect(floatArrayOf(2f, 3f), 0f) }
        c.drawLine(nowX, area.top + 2f, nowX, area.bottom - 2f, divider)

        // History: one line, with the points out of range in their own colour.
        val line = paint(withAlpha(BLUE, 160), Paint.Style.STROKE, 1.8f).apply {
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path()
        var last: Long? = null
        for (p in data.history) {
            val x = xOf(p.timestamp)
            val y = yOf(p.value)
            // A gap of more than 15 minutes is a real gap, no line through it.
            if (last == null || p.timestamp - last > GAP_MS) path.moveTo(x, y) else path.lineTo(x, y)
            last = p.timestamp
        }
        c.drawPath(path, line)
        for (p in data.history) {
            val color = when (p.range) {
                BgRange.HIGH     -> AMBER
                BgRange.LOW      -> RED
                BgRange.IN_RANGE -> continue
            }
            c.drawCircle(xOf(p.timestamp), yOf(p.value), 2.1f, paint(color))
        }

        // Prediction curves: dashed, right of "now", starting at the current value. Only what AIMI delivered.
        val startValue = data.currentValue
        if (startValue != null && !data.stale) {
            for (curve in curves) {
                val cp = paint(curveColor(curve.type), Paint.Style.STROKE, 1.8f).apply {
                    pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
                    strokeCap = Paint.Cap.ROUND
                }
                val cpath = Path().apply { moveTo(nowX, yOf(startValue)) }
                for (p in curve.points) {
                    if (p.timestamp <= data.now) continue
                    val x = xOf(p.timestamp)
                    if (x > area.right) break
                    cpath.lineTo(x, yOf(p.value))
                }
                c.drawPath(cpath, cp)
            }
        }

        // The current value as a dot on the divider.
        if (startValue != null) c.drawCircle(nowX, yOf(startValue), 4.5f, paint(bgColor(data.bgClass)))

        // Quiet labels with a halo in the card colour: they stay readable when a curve runs under them.
        val label = textPaint(0xFF64748B.toInt(), 10f)
        drawHaloText(c, topRightText, area.right - 2f, area.top + 10f, label.apply { textAlign = Paint.Align.RIGHT })
        if (bottomLeftText != null) {
            val left = textPaint(0xFF64748B.toInt(), 10f)
            drawHaloText(c, ellipsize(bottomLeftText, left, area.width() * 0.5f), area.left + 2f, area.bottom - 3f, left)
        }
    }

    // ---- Pod and sensor ----

    /** Pod or sensor as the renderer needs it: label, the value in coloured parts, how full the bar is. */
    internal class AimiBarInfo(val label: String, val parts: List<Pair<String, Int>>, val fraction: Float, val barColor: Int)

    internal fun podBar(data: AimiWidgetData, t: AimiWidgetTexts): AimiBarInfo? = data.pod?.let { pod ->
        val label = if (pod.isPatchPump) t.pod else t.site
        // The time part and the insulin part each get their own colour.
        val timeText = pod.graceHoursLeft?.let { t.grace(DecimalFormat("0").format(it)) }
            ?: t.hours(DecimalFormat("0").format(pod.hoursLeft))
        val parts = mutableListOf(Pair(timeText, levelColor(pod.level, TEXT)))
        pod.reservoirU?.let {
            val text = DecimalFormat("0").format(it)
            val shown = if (pod.reservoirCapped) t.overUnits(text) else t.units(text)
            parts += Pair(" \u00b7 ", MUTED)
            parts += Pair(shown, levelColor(pod.reservoirLevel, TEXT))
        }
        AimiBarInfo(label, parts, pod.fractionLeft, levelColor(pod.level, BLUE))
    }

    internal fun sensorBar(data: AimiWidgetData, t: AimiWidgetTexts): AimiBarInfo? = data.sensor?.let { sensor ->
        val days = DecimalFormat("0.0").format(sensor.hoursLeft / 24.0)
        val timeText = sensor.graceHoursLeft?.let { t.grace(DecimalFormat("0").format(it)) } ?: t.daysLeft(days)
        AimiBarInfo(t.sensor, listOf(Pair(timeText, levelColor(sensor.level, TEXT))), sensor.fractionLeft, levelColor(sensor.level, EMERALD))
    }

    internal fun drawPods(c: Canvas, left: Float, top: Float, right: Float, data: AimiWidgetData, t: AimiWidgetTexts) {
        val colW = (right - left - 14f) / 2f
        podBar(data, t)?.let { drawBarColumn(c, left, top, colW, it.label, it.parts, it.fraction, it.barColor) }
        sensorBar(data, t)?.let { drawBarColumn(c, left + colW + 14f, top, colW, it.label, it.parts, it.fraction, it.barColor) }
    }

    private fun drawBarColumn(
        c: Canvas, x: Float, top: Float, width: Float, label: String, parts: List<Pair<String, Int>>, fraction: Float, barColor: Int
    ) {
        val labelPaint = textPaint(MUTED, 11f)
        c.drawText(label, x, top + 11f, labelPaint)
        // All parts share one size, shrunk together until they fit beside the label; drawn from the right.
        val maxValueW = width - labelPaint.measureText(label) - 8f
        val paint = textPaint(TEXT, 11f)
        val whole = parts.joinToString("") { it.first }
        shrinkToFit(paint, whole, maxValueW)
        var px = x + width - parts.sumOf { paint.measureText(it.first).toDouble() }.toFloat()
        for ((text, color) in parts) {
            paint.color = color
            c.drawText(text, px, top + 11f, paint)
            px += paint.measureText(text)
        }
        val barTop = top + 17f
        c.drawRoundRect(RectF(x, barTop, x + width, barTop + 4f), 2f, 2f, paint(withAlpha(0xFFFFFFFF.toInt(), 26)))
        val filled = (width * fraction.coerceIn(0f, 1f)).coerceAtLeast(if (fraction > 0f) 4f else 0f)
        if (filled > 0f) c.drawRoundRect(RectF(x, barTop, x + filled, barTop + 4f), 2f, 2f, paint(barColor))
    }

    // ---- Helpers ----

    /** Text with a thin halo in the card colour around it. */
    internal fun drawHaloText(c: Canvas, text: String, x: Float, baseline: Float, p: Paint) {
        val halo = Paint(p).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeJoin = Paint.Join.ROUND
            color = withAlpha(CARD_BASE, 217)
        }
        c.drawText(text, x, baseline, halo)
        c.drawText(text, x, baseline, p)
    }

    /** [text] cut with "…" when it is wider than [maxWidth]. */
    internal fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || p.measureText(text) <= maxWidth) return text
        val count = p.breakText(text, true, maxWidth - p.measureText("…"), null)
        return text.take(count.coerceAtLeast(0)) + "…"
    }

    internal fun shrinkToFit(p: Paint, text: String, maxWidth: Float) {
        val minSize = p.textSize * 0.7f
        while (p.measureText(text) > maxWidth && p.textSize > minSize) p.textSize -= 0.5f
    }

    /** Draws [text] smaller when it is too wide, and cut with "…" when even the smallest size is too wide. */
    internal fun drawFitted(c: Canvas, text: String, x: Float, baseline: Float, p: Paint, maxWidth: Float) {
        if (maxWidth <= 0f) return
        shrinkToFit(p, text, maxWidth)
        if (p.measureText(text) <= maxWidth) {
            c.drawText(text, x, baseline, p)
            return
        }
        val count = p.breakText(text, true, maxWidth - p.measureText("…"), null)
        c.drawText(text.take(count.coerceAtLeast(0)) + "…", x, baseline, p)
    }

    internal fun formatBg(value: Double, units: GlucoseUnit): String =
        if (units == GlucoseUnit.MGDL) Math.round(value).toString() else String.format(Locale.getDefault(), "%.1f", value)

    internal fun bgColor(cls: WidgetBgColorClass): Int = when (cls) {
        WidgetBgColorClass.LOW      -> RED
        WidgetBgColorClass.IN_RANGE -> EMERALD
        WidgetBgColorClass.HIGH     -> AMBER
        WidgetBgColorClass.STALE    -> MUTED
    }

    internal fun levelColor(level: AimiWidgetLevel, normal: Int): Int = when (level) {
        AimiWidgetLevel.NORMAL -> normal
        AimiWidgetLevel.WARN   -> AMBER
        AimiWidgetLevel.CRIT   -> RED
    }

    internal fun curveColor(type: BgType): Int = when (type) {
        BgType.IOB_PREDICTION   -> BLUE
        BgType.UAM_PREDICTION   -> 0xFFF59E0B.toInt()
        BgType.ZT_PREDICTION    -> 0xFFA78BFA.toInt()
        BgType.COB_PREDICTION   -> 0xFFFB923C.toInt()
        BgType.A_COB_PREDICTION -> 0xFFFDBA74.toInt()
        else                    -> BLUE
    }

    internal fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    internal companion object {

        val DOUBLE_ARROWS = setOf(TrendArrow.DOUBLE_UP, TrendArrow.DOUBLE_DOWN, TrendArrow.TRIPLE_UP, TrendArrow.TRIPLE_DOWN)

        const val CORNER = 24f
        /** Share of the chart width for the history. The rest is for the prediction curves. */
        const val HISTORY_SHARE = 0.76f
        const val FUTURE_MS = 90L * 60 * 1000
        const val GAP_MS = 15L * 60 * 1000

        val CARD_BASE = 0xFF080C14.toInt()
        const val DEFAULT_CARD_ALPHA = 199
        val CARD_BORDER = 0x24FFFFFF
        val TEXT = 0xFFF8FAFC.toInt()
        val MUTED = 0xFF94A3B8.toInt()
        val EMERALD = 0xFF22E06B.toInt()
        val EMERALD_LIGHT = 0xFF34D399.toInt()
        val AMBER = 0xFFF5B301.toInt()
        val RED = 0xFFEF4444.toInt()
        val BLUE = 0xFF38BDF8.toInt()
    }
}
