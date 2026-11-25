package app.aaps.ui.widget.aimi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.aaps.core.data.model.TrendArrow
import java.text.DecimalFormat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Layout of the AIMI widget, drawn as one bitmap, with the drawing parts of [AimiWidgetDraw].
 *
 * Top: the value with arrow and delta and two lines of age and prediction, with the four values
 * (IOB, basal, ISF, SMB) as pills to the right of it. Then the chart, whose scale follows the data.
 *
 * Two forms, by height:
 * - compact (4x2): the time in range as a three coloured bar with "TIR 92%" beside it, then pod and sensor
 *   as lines with a bar. The profile name sits with the time range at the top right of the chart.
 * - large (4x3): the chart is lower, the profile name sits at its bottom left, the time in range is a wide
 *   tile and pod and sensor are two tiles below it.
 *
 * Priority when the height runs short: chart and header stay, then the time in range, then pod and sensor.
 * Pills need width: below [MIN_PILLS_DP] they are left out.
 */
class AimiWidgetRenderer {

    private val base = AimiWidgetDraw()

    fun render(widthDp: Float, heightDp: Float, density: Float, data: AimiWidgetData?, texts: AimiWidgetTexts): Bitmap {
        val w = widthDp.coerceAtLeast(MIN_DP)
        val h = heightDp.coerceAtLeast(MIN_DP)
        var d = density.coerceAtLeast(1f)
        // A widget bitmap goes through a binder transaction; keep it far below the limit.
        if (w * h * d * d > MAX_PIXELS) d = sqrt(MAX_PIXELS / (w * h))
        val bitmap = Bitmap.createBitmap((w * d).toInt().coerceAtLeast(1), (h * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(d, d)
        base.drawCard(canvas, w, h, data?.cardAlpha ?: AimiWidgetDraw.DEFAULT_CARD_ALPHA)
        if (data != null) drawContent(canvas, w, h, data, texts)
        return bitmap
    }

    private fun drawContent(c: Canvas, w: Float, h: Float, data: AimiWidgetData, t: AimiWidgetTexts) {
        val left = PAD
        val right = w - PAD
        val top = PAD
        val avail = h - 2 * PAD
        val headerH = (avail * 0.34f).coerceIn(56f, 78f)
        val largeChartH = avail - headerH - 3 * GAP - TIR_TILE_H - TILE_H
        val large = avail >= LARGE_AVAIL_DP && largeChartH >= MIN_LARGE_CHART_DP

        drawHeader(c, left, top, right, headerH, data, t)
        val y = top + headerH + GAP
        if (large) drawLarge(c, left, y, right, largeChartH, data, t) else drawCompact(c, left, y, right, top + avail, data, t)
    }

    // ---- Compact form (4x2) ----

    private fun drawCompact(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, data: AimiWidgetData, t: AimiWidgetTexts) {
        var showPods = data.pod != null || data.sensor != null
        var showTir = true
        fun chartHeight() = bottom - top -
            (if (showTir) TIR_H + GAP else 0f) - (if (showPods) ROW_H + GAP else 0f)
        if (chartHeight() < MIN_CHART_DP) showPods = false
        if (chartHeight() < MIN_CHART_DP) showTir = false
        val chartH = chartHeight().coerceAtLeast(20f)

        // Profile name and time range together at the top right.
        val range = t.rangeHours
        val paint = base.textPaint(CHART_LABEL, 10f)
        val suffix = " · $range"
        val profile = data.profileText
        val label = if (profile.isBlank()) range else base.ellipsize(profile, paint, (right - left) * 0.55f - paint.measureText(suffix)) + suffix
        base.drawChart(c, RectF(left, top, right, top + chartH), data, t, label)

        var y = top + chartH + GAP
        if (showTir) {
            drawTirLine(c, left, y, right, data.tir, t)
            y += TIR_H + GAP
        }
        if (showPods) base.drawPods(c, left, y, right, data, t)
    }

    // ---- Large form (4x3) ----

    private fun drawLarge(c: Canvas, left: Float, top: Float, right: Float, chartH: Float, data: AimiWidgetData, t: AimiWidgetTexts) {
        base.drawChart(c, RectF(left, top, right, top + chartH), data, t, t.rangeHours, data.profileText.takeIf { it.isNotBlank() })
        var y = top + chartH + GAP
        drawTirTile(c, left, y, right, data.tir, t)
        y += TIR_TILE_H + GAP

        val pod = base.podBar(data, t)
        val sensor = base.sensorBar(data, t)
        val gap = 6f
        when {
            pod != null && sensor != null -> {
                val podW = (right - left - gap) * 1.25f / 2.25f
                drawBarTile(c, RectF(left, y, left + podW, y + TILE_H), pod)
                drawBarTile(c, RectF(left + podW + gap, y, right, y + TILE_H), sensor)
            }
            pod != null                   -> drawBarTile(c, RectF(left, y, right, y + TILE_H), pod)
            sensor != null                -> drawBarTile(c, RectF(left, y, right, y + TILE_H), sensor)
        }
    }

    private fun tileBackground(c: Canvas, rect: RectF) =
        c.drawRoundRect(rect, TILE_RADIUS, TILE_RADIUS, base.paint(base.withAlpha(0xFFFFFFFF.toInt(), 23)))

    /** Time in range as a wide tile: "TIR 92%" at the left, the three coloured bar beside it. */
    private fun drawTirTile(c: Canvas, left: Float, top: Float, right: Float, tir: AimiWidgetTir?, t: AimiWidgetTexts) {
        tileBackground(c, RectF(left, top, right, top + TIR_TILE_H))
        val label = base.textPaint(AimiWidgetDraw.MUTED, 10.5f)
        val value = base.textPaint(AimiWidgetDraw.TEXT, 13f)
        val baseline = top + TIR_TILE_H / 2f + 4.5f
        val labelText = "${t.tir} "
        c.drawText(labelText, left + 10f, baseline, label)
        c.drawText(tir?.let { "${it.inRange}%" } ?: t.noValue, left + 10f + label.measureText(labelText), baseline, value)
        val barLeft = left + 10f + label.measureText(labelText) + value.measureText("100%") + 12f
        drawTirBar(c, barLeft, top + (TIR_TILE_H - 5f) / 2f, right - 10f, 5f, tir)
    }

    /** Pod or sensor as a tile: label, value in its colours, bar at the bottom. */
    private fun drawBarTile(c: Canvas, rect: RectF, info: AimiWidgetDraw.AimiBarInfo) {
        tileBackground(c, rect)
        val x = rect.left + 10f
        val width = rect.width() - 20f
        c.drawText(info.label, x, rect.top + 15f, base.textPaint(AimiWidgetDraw.MUTED, 10.5f))
        // All parts share one size, shrunk together until they fit.
        val paint = base.textPaint(AimiWidgetDraw.TEXT, 14f)
        base.shrinkToFit(paint, info.parts.joinToString("") { it.first }, width)
        var px = x
        for ((text, color) in info.parts) {
            paint.color = color
            c.drawText(text, px, rect.top + 32f, paint)
            px += paint.measureText(text)
        }
        val barTop = rect.bottom - 11f
        c.drawRoundRect(RectF(x, barTop, x + width, barTop + 4f), 2f, 2f, base.paint(base.withAlpha(0xFFFFFFFF.toInt(), 36)))
        val filled = (width * info.fraction.coerceIn(0f, 1f)).coerceAtLeast(if (info.fraction > 0f) 4f else 0f)
        if (filled > 0f) c.drawRoundRect(RectF(x, barTop, x + filled, barTop + 4f), 2f, 2f, base.paint(info.barColor))
    }

    // ---- Header: value, arrow, delta, two text lines, pills ----

    private fun drawHeader(c: Canvas, left: Float, top: Float, right: Float, headerH: Float, data: AimiWidgetData, t: AimiWidgetTexts) {
        val bgSize = headerH - 28f
        val color = base.bgColor(data.bgClass)
        val bgPaint = base.textPaint(color, bgSize, bold = true)
        if (data.stale) bgPaint.flags = bgPaint.flags or Paint.STRIKE_THRU_TEXT_FLAG
        val baseline = top + bgSize * 0.80f
        c.drawText(data.bgText, left, baseline, bgPaint)
        var x = left + bgPaint.measureText(data.bgText) + 8f

        val arrow = data.trend
        if (arrow != null && arrow != TrendArrow.NONE) {
            val size = bgSize * 0.46f
            base.drawArrow(c, x + size / 2, top + bgSize * 0.5f, size, arrow, color)
            x += size + 8f
        }
        val deltaPaint = base.textPaint(AimiWidgetDraw.TEXT, 14f, bold = true)
        c.drawText(data.deltaText, x, baseline, deltaPaint)
        val leftBlockRight = x + deltaPaint.measureText(data.deltaText)

        // Two quiet lines under the value: the age, and the 30 minute prediction when it was read cleanly.
        val prediction = data.prediction30?.let { t.prediction(base.formatBg(it, data.units)) }
        val lineW = leftBlockRight - left
        base.drawFitted(c, data.timeAgoText, left, top + bgSize + 10f, base.textPaint(AimiWidgetDraw.MUTED, 10.5f), lineW)
        if (prediction != null) {
            base.drawFitted(c, prediction, left, top + bgSize + 22f, base.textPaint(AimiWidgetDraw.MUTED, 10.5f), lineW)
        }

        val pillsLeft = leftBlockRight + 12f
        if (right - pillsLeft >= MIN_PILLS_DP) drawPills(c, pillsLeft, top, right, headerH, data, t)
    }

    private fun drawPills(c: Canvas, left: Float, top: Float, right: Float, headerH: Float, data: AimiWidgetData, t: AimiWidgetTexts) {
        val fmt2 = DecimalFormat("0.00")
        val fmtSmb = DecimalFormat("0.##")
        val items = listOf(
            Triple(t.iob, fmt2.format(data.iob), AimiWidgetDraw.BLUE),
            Triple(t.basal, data.basalNow?.let { fmt2.format(it) } ?: t.noValue, basalColor(data.basalNow, data.profileBasal)),
            Triple(t.isf, data.isfText ?: t.noValue, AimiWidgetDraw.TEXT),
            Triple(t.smb, data.lastSmb?.let { fmtSmb.format(it) } ?: t.noValue, AimiWidgetDraw.TEXT)
        )
        val gap = 5f
        val pillW = (right - left - gap) / 2f
        val pillH = (headerH - 4f - gap) / 2f
        val labelPaint = base.textPaint(AimiWidgetDraw.MUTED, 10.5f)
        items.forEachIndexed { i, (label, value, color) ->
            val px = left + (i % 2) * (pillW + gap)
            val py = top + 2f + (i / 2) * (pillH + gap)
            c.drawRoundRect(RectF(px, py, px + pillW, py + pillH), 11f, 11f, base.paint(base.withAlpha(0xFFFFFFFF.toInt(), 23)))
            val baselineY = py + pillH / 2f + 4.5f
            c.drawText(label, px + 8f, baselineY, labelPaint)
            // The value is drawn from the right edge, shrunk when it is too wide for what is left of the pill.
            val vp = base.textPaint(color, 13f).apply { textAlign = Paint.Align.RIGHT }
            val maxW = pillW - 16f - labelPaint.measureText(label) - 4f
            base.shrinkToFit(vp, value, maxW)
            c.drawText(value, px + pillW - 8f, baselineY, vp)
        }
    }

    /**
     * The basal value stands out only when the loop does something unusual: nothing delivered (grey) or a rate
     * well above the profile (blue, the colour of insulin). AIMI sets a different rate at almost every tick, so
     * every difference would colour the pill all the time and the colour would say nothing.
     */
    private fun basalColor(now: Double?, profile: Double?): Int = when {
        now == null || profile == null || profile <= 0.0 -> AimiWidgetDraw.TEXT
        now <= BASAL_ZERO_U_H                           -> AimiWidgetDraw.MUTED
        now >= profile * BASAL_HIGH_FACTOR              -> AimiWidgetDraw.BLUE
        else                                            -> AimiWidgetDraw.TEXT
    }

    // ---- Time in range ----

    /** Compact form: "TIR 92%" at the left, the bar on the same line, centred. */
    private fun drawTirLine(c: Canvas, left: Float, top: Float, right: Float, tir: AimiWidgetTir?, t: AimiWidgetTexts) {
        val label = base.textPaint(AimiWidgetDraw.MUTED, 11f)
        val value = base.textPaint(AimiWidgetDraw.TEXT, 11f)
        val labelText = "${t.tir} "
        val baseline = top + TIR_H / 2f + 4f
        c.drawText(labelText, left, baseline, label)
        c.drawText(tir?.let { "${it.inRange}%" } ?: t.noValue, left + label.measureText(labelText), baseline, value)
        val barLeft = left + label.measureText("${t.tir} 100%") + 10f
        drawTirBar(c, barLeft, top + (TIR_H - 5f) / 2f, right, 5f, tir)
    }

    /**
     * Low, in range and high as three segments in red, green and amber. A segment that exists is at least 3 dp
     * wide. Without a value (before the time of day the dashboard also waits for) only the empty track shows.
     */
    private fun drawTirBar(c: Canvas, left: Float, top: Float, right: Float, height: Float, tir: AimiWidgetTir?) {
        val width = right - left
        if (width <= 0f) return
        val radius = height / 2f
        val sum = max(1, (tir?.low ?: 0) + (tir?.inRange ?: 0) + (tir?.high ?: 0)).toFloat()
        val parts = if (tir == null) emptyList() else listOf(
            Pair(tir.low, AimiWidgetDraw.RED),
            Pair(tir.inRange, AimiWidgetDraw.EMERALD),
            Pair(tir.high, AimiWidgetDraw.AMBER)
        ).filter { it.first > 0 }
        val gap = 1f
        val clip = Path().apply { addRoundRect(RectF(left, top, right, top + height), radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        c.drawRect(left, top, right, top + height, base.paint(base.withAlpha(0xFFFFFFFF.toInt(), 36)))
        var x = left
        parts.forEachIndexed { i, (value, color) ->
            val segW = max(MIN_SEGMENT_DP, width * value / sum)
            val end = if (i == parts.lastIndex) right else min(right, x + segW)
            val paintEnd = if (i == parts.lastIndex) end else max(x, end - gap)
            if (paintEnd > x) c.drawRect(x, top, paintEnd, top + height, base.paint(color))
            x = end
        }
        c.restore()
    }

    private companion object {

        const val MIN_DP = 40f
        const val MAX_PIXELS = 1_400_000f
        const val PAD = 12f
        const val GAP = 6f
        /** Height of a label row with a bar under it, the same as pod and sensor. */
        const val ROW_H = 22f
        const val TIR_H = 12f
        const val MIN_CHART_DP = 36f
        /** Below this width the pills are left out. */
        const val MIN_PILLS_DP = 120f
        const val MIN_SEGMENT_DP = 3f
        /** At or below this the rate counts as nothing delivered. */
        const val BASAL_ZERO_U_H = 0.005
        /** From this multiple of the profile basal on the rate counts as well above it. */
        const val BASAL_HIGH_FACTOR = 1.5

        /** From this height of the usable area on, the large form is drawn. A 4x2 reports about 223, a 4x3 about 346. */
        const val LARGE_AVAIL_DP = 290f
        const val TIR_TILE_H = 36f
        const val TILE_H = 50f
        const val TILE_RADIUS = 14f
        const val MIN_LARGE_CHART_DP = 60f

        val CHART_LABEL = 0xFF64748B.toInt()
    }
}
