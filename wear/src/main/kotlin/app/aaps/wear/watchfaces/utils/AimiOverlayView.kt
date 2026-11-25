package app.aaps.wear.watchfaces.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import app.aaps.wear.watchfaces.utils.AimiOverlayRules.BasalState
import com.google.android.gms.wearable.Wearable
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The AIMI part of the Custom watch face: what the standard CWF views cannot draw.
 *
 * - the rings around the dial: time in range (top), pod (bottom left), sensor (bottom right) and the watch's
 *   own battery (right). The phone battery ring is a normal CWF `dynData` image, not drawn here;
 * - the glucose graph: history, the measured forecast band, the low warning curve and the SMB strip;
 * - the pills: pulse and steps (the watch's own sensors), weather (from the phone), IOB and basal;
 * - a red border while a low is expected, a dashed grey one while the phone or the readings are missing;
 * - the always-on picture ([drawAmbient]).
 *
 * It draws in the CWF's 400x400 design space and scales to its own size, so the json only decides whether it
 * shows (`aimi_overlay` with `visibility`, `width` and `height` of 400, `topmargin` and `leftmargin` 0). The
 * static parts (ring tracks, graph card, ticks) come from the zip's `Background` image.
 *
 * The data arrives in [update]. Besides that it reads the watch's battery, the pulse and step sensors (only while
 * [setActive] is on), whether a phone is connected, and the settings of the watch face. The decisions behind that
 * are in [AimiOverlayRules]. The view is never attached to a window: the face lays it out and draws it by hand.
 */
class AimiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var aimi: EventData.TreatmentData.Aimi? = null
    private var history: List<EventData.SingleBg> = emptyList()
    private var boluses: List<EventData.TreatmentData.Treatment> = emptyList()
    private var smbSmallBelow = -1.0
    private var smbLargeFrom = -1.0
    private var lastBgTime = 0L
    private var watchBattery = -1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val ringRect = RectF(200f - RING_R, 200f - RING_R, 200f + RING_R, 200f + RING_R)

    /** Reused for every pill, so a frame allocates no rectangles. */
    private val box = RectF()

    // Created once; a frame only picks one.
    private val dashBorder = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    private val dashMark = DashPathEffect(floatArrayOf(2f, 4f), 0f)
    private val dashForecast = DashPathEffect(floatArrayOf(5f, 4f), 0f)
    private val dashLow = DashPathEffect(floatArrayOf(1f, 4f), 0f)

    /** Takes the newest data and redraws. Called when the watch face reads its fields. */
    fun update(
        aimi: EventData.TreatmentData.Aimi?,
        history: List<EventData.SingleBg>,
        boluses: List<EventData.TreatmentData.Treatment>,
        smbSmallBelow: Double,
        smbLargeFrom: Double
    ) {
        this.aimi = aimi
        this.history = history.filter { it.sgv > 39.0 }.sortedBy { it.timeStamp }
        this.boluses = boluses
        this.smbSmallBelow = smbSmallBelow
        this.smbLargeFrom = smbLargeFrom
        this.lastBgTime = this.history.lastOrNull()?.timeStamp ?: 0L
        this.watchBattery = readWatchBattery()
        forgetAckWhenGone()
        refreshPhoneLink()
        invalidate()
    }

    @Volatile private var phoneLinked = true
    private var linkCheckedAt = 0L

    /** Asks the Wear OS data layer whether any phone node is connected; at most every 2 minutes. */
    private fun refreshPhoneLink() {
        val now = System.currentTimeMillis()
        if (now - linkCheckedAt < LINK_CHECK_MS) return
        linkCheckedAt = now
        try {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                // Only a phone in reach counts. A cloud node means the watch is online, not that the phone is.
                val linked = nodes.any { it.isNearby }
                if (linked != phoneLinked) {
                    phoneLinked = linked
                    requestRedraw?.invoke()
                }
            }
        } catch (_: Exception) {
            // Keep the last known state.
        }
    }

    // The four side pills: pulse and steps on the left (the watch's own sensors), weather and its day range on
    // the right (from the phone, in the AIMI data).
    // Written by the sensor threads, read when drawing.
    @Volatile private var pulseBpm = 0
    @Volatile private var pulseAt = 0L
    @Volatile private var lastRedraw = 0L

    private val prefs by lazy { context.getSharedPreferences(AimiOverlayRules.PREFS, Context.MODE_PRIVATE) }
    private var sensorsOn = false

    private val sensorListener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
            val value = event.values.firstOrNull() ?: return
            if (value > 0f && event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                pulseBpm = value.roundToInt()
                pulseAt = System.currentTimeMillis()
                redrawSoon()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * The day's steps, kept by [app.aaps.wear.wearStepCount.DailyStepsTracker] in the preferences. A saved total of an
     * earlier day means no step yet today. Nothing saved at all: the tracker never ran, so no number.
     */
    private fun stepsToday(): Long {
        val savedDay = prefs.getInt(AimiOverlayRules.KEY_DAY, 0)
        if (savedDay == 0) return -1L
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        return if (savedDay == today) prefs.getLong(AimiOverlayRules.KEY_STEPS_TODAY, 0L) else 0L
    }

    /** Redraws at most every few seconds; the step counter changes with every step. */
    private fun redrawSoon() {
        val now = System.currentTimeMillis()
        if (now - lastRedraw < REDRAW_MS) return
        lastRedraw = now
        requestRedraw?.invoke()
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Set by the watch face: asks it to redraw (the sensors and the phone link report from other threads). */
    var requestRedraw: (() -> Unit)? = null

    /**
     * Switches the pulse sensor on while the face is interactive and off in ambient mode or when the face goes
     * away. The face calls this: the view sits in a layout that is drawn by hand and never attached to a window,
     * so it cannot learn about showing or hiding by itself. Without the permission a pill stays at "–".
     */
    fun setActive(active: Boolean) {
        if (active == sensorsOn) return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        if (active) {
            if (granted(Manifest.permission.BODY_SENSORS)) {
                manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { manager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
            }
        } else {
            manager.unregisterListener(sensorListener)
        }
        sensorsOn = active
    }

    private fun drawPills(canvas: Canvas, d: EventData.TreatmentData.Aimi?) {
        val pulseFresh = System.currentTimeMillis() - pulseAt <= PULSE_FRESH_MS
        val pulse = if (pulseBpm > 0 && pulseFresh) "$pulseBpm" else NO_VALUE
        val stepsTotal = stepsToday()
        val steps = if (stepsTotal >= 0) String.format(Locale.getDefault(), "%,d", stepsTotal) else NO_VALUE
        val now = d?.weatherNowC?.takeIf { it > AimiOverlayRules.UNKNOWN_LIMIT }
        val low = d?.weatherLowC?.takeIf { it > AimiOverlayRules.UNKNOWN_LIMIT }
        val high = d?.weatherHighC?.takeIf { it > AimiOverlayRules.UNKNOWN_LIMIT }
        pill(canvas, 46f, 145f, 109f, 185f, pulse, "Pulse", PULSE_COLOR)
        pill(canvas, 46f, 189f, 109f, 229f, steps, "Steps", STEPS_COLOR)
        pill(canvas, 291f, 145f, 354f, 185f, now?.let { degrees(it) } ?: NO_VALUE, "Weather", TEXT_COLOR)
        pill(canvas, 291f, 189f, 354f, 229f, if (low != null && high != null) "${degrees(low)} / ${degrees(high)}" else NO_VALUE, "Low / High", TEXT_COLOR)
    }

    private fun degrees(value: Double) = "${value.roundToInt()}°"

    private fun pill(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, value: String, label: String, color: Int) {
        box.set(l, t, r, b)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = PILL_FILL
        canvas.drawRoundRect(box, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = PILL_STROKE
        canvas.drawRoundRect(box, 10f, 10f, paint)
        paint.reset()
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = VALUE_SIZE
        // A long value ("-12° / -5°") shrinks to the pill instead of running over its edge.
        val room = (r - l) - 8f
        val wide = paint.measureText(value)
        if (wide > room) paint.textSize = VALUE_SIZE * room / wide
        paint.color = color
        canvas.drawText(value, (l + r) / 2, t + 21f, paint)
        // The label: bold and light, so it reads on the dark pill; shrinks if it does not fit.
        paint.isFakeBoldText = true
        paint.textSize = 12f
        val labelWide = paint.measureText(label)
        if (labelWide > room) paint.textSize = 12f * room / labelWide
        paint.color = LABEL_COLOR
        canvas.drawText(label, (l + r) / 2, t + 35f, paint)
    }

    private fun readWatchBattery(): Float = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) level.toFloat() / scale else -1f
    } catch (_: Exception) {
        -1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val data = aimi
        val offline = offline()
        val low = lowState(data)
        canvas.save()
        canvas.scale(width / DESIGN, height / DESIGN)
        drawBorder(canvas, low.shown && redBorderOn() && !offline, stale = data != null && offline)
        drawPills(canvas, data)
        drawStatusPills(canvas, data)
        if (data != null) {
            drawRings(canvas, data)
            if (offline) drawOffline(canvas) else drawGraph(canvas, data, low)
        }
        canvas.restore()
    }

    // ---------- low warning, its settings and the acknowledgement ----------

    private val settings by lazy { PreferenceManager.getDefaultSharedPreferences(context) }

    private fun lowWarningOn() = settings.getBoolean(context.getString(R.string.key_aimi_low_warning), true)
    private fun redBorderOn() = settings.getBoolean(context.getString(R.string.key_aimi_red_border), true)
    private fun leadMinutes() = settings.getString(context.getString(R.string.key_aimi_low_lead), "60")?.toIntOrNull() ?: 60
    private fun ackMinutes() = settings.getString(context.getString(R.string.key_aimi_ack_minutes), "60")?.toIntOrNull() ?: 60

    /** Whether a low is expected, and whether that warning is shown (it is not once acknowledged). One value per frame. */
    private class LowState(val active: Boolean, val shown: Boolean)

    private fun lowState(d: EventData.TreatmentData.Aimi?): LowState {
        val active = lowWarningActive(d)
        return LowState(active, d != null && AimiOverlayRules.warningShown(active, realLow(d), acknowledged()))
    }

    private fun lowWarningActive(d: EventData.TreatmentData.Aimi?): Boolean =
        d != null && AimiOverlayRules.lowWarningActive(lowWarningOn(), leadMinutes(), d.lowRisk, d.insulinOnly, lastBgTime)

    /** The reading itself is already low: that is no forecast and cannot be acknowledged away. */
    private fun realLow(d: EventData.TreatmentData.Aimi): Boolean = (history.lastOrNull()?.sgv ?: Double.MAX_VALUE) < d.lowMarkMgdl

    private fun acknowledged(): Boolean = AimiOverlayRules.acknowledged(prefs.getLong(KEY_ACK_AT, 0L), ackMinutes(), System.currentTimeMillis())

    /** An acknowledgement ends when the warning has been gone once: the next one must show again. */
    private fun forgetAckWhenGone() {
        if (prefs.getLong(KEY_ACK_AT, 0L) > 0L && !lowWarningActive(aimi)) prefs.edit().remove(KEY_ACK_AT).apply()
    }

    private var lastCardTap = 0L

    /**
     * A double tap in the graph card acknowledges an active, unacknowledged low warning. Returns true for a tap that
     * belongs to this (the first of the two as well), so the face does not treat it as anything else; [x] and [y] are
     * in the pixels of the face. Nothing to acknowledge while the forecast is not shown, or when the reading is low.
     */
    fun handleTap(x: Float, y: Float): Boolean {
        val d = aimi ?: return false
        if (width <= 0 || offline() || !lowWarningActive(d) || realLow(d) || acknowledged()) return false
        val dx = x * DESIGN / width
        val dy = y * DESIGN / height
        if (dx !in 86f..313f || dy !in 240f..307f) return false
        val now = System.currentTimeMillis()
        if (AimiOverlayRules.isDoubleTap(lastCardTap, now)) {
            prefs.edit().putLong(KEY_ACK_AT, now).apply()
            lastCardTap = 0L
        } else {
            lastCardTap = now
        }
        return true
    }

    // ---------- ambient ----------

    /**
     * The ambient (always-on) picture: black, only outlines, about 95% of the pixels stay dark. Time, glucose with
     * its trend arrow and the age of the reading; no rings, pills, graph or sensors. Drawn instead of the whole
     * layout, so nothing of the interactive face shows through. In the low-bit modes everything is plain white.
     * Returns false when the view has not been laid out yet.
     */
    fun drawAmbient(
        canvas: Canvas,
        hour: String,
        minute: String,
        sgv: String,
        sgvMgdl: Double,
        arrow: String,
        age: String,
        strike: Boolean,
        lowBit: Boolean
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        canvas.drawColor(0xFF000000.toInt())
        canvas.save()
        canvas.scale(width / DESIGN, height / DESIGN)
        val white = 0xFFFFFFFF.toInt()
        val lowMark = aimi?.lowMarkMgdl ?: 70.0
        val highMark = aimi?.highMarkMgdl ?: 180.0
        val bgColor = when {
            lowBit               -> white
            sgvMgdl <= 0.0       -> GREY
            sgvMgdl < lowMark    -> RED
            sgvMgdl > highMark   -> AMBER
            else                 -> GREEN
        }
        val mute = if (lowBit) white else LABEL_COLOR

        // A thin rim keeps the round shape without lighting up a ring.
        paint.reset()
        paint.isAntiAlias = !lowBit
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (lowBit) white else 0xFF1B2740.toInt()
        canvas.drawCircle(200f, 200f, 198f, paint)

        outlineText(canvas, "$hour:$minute", 200f, 160f, 60f, white, lowBit)
        // The glucose value sits exactly in the middle, like the time; the arrow stands to its right.
        paint.reset()
        paint.isAntiAlias = !lowBit
        paint.isFakeBoldText = true
        paint.textSize = 84f
        val textW = paint.measureText(sgv)
        val left = 200f - textW / 2
        outlineText(canvas, sgv, 200f, 258f, 84f, bgColor, lowBit)
        if (strike) {
            paint.reset()
            paint.isAntiAlias = !lowBit
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = bgColor
            canvas.drawLine(left - 4f, 228f, left + textW + 4f, 228f, paint)
        }
        AimiOverlayRules.arrowAngle(arrow)?.let { drawArrow(canvas, left + textW + 48f, 228f, it, bgColor, lowBit) }

        paint.reset()
        paint.isAntiAlias = !lowBit
        paint.color = mute
        paint.textSize = 18f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(age, 200f, 306f, paint)
        canvas.restore()
        return true
    }

    /** Text as an outline only. [baseline] is the baseline in the 400 design space, [cx] the centre. */
    private fun outlineText(canvas: Canvas, text: String, cx: Float, baseline: Float, size: Float, color: Int, lowBit: Boolean) {
        paint.reset()
        paint.isAntiAlias = !lowBit
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        paint.strokeJoin = Paint.Join.ROUND
        paint.isFakeBoldText = true
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.color = color
        canvas.drawText(text, cx, baseline, paint)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, angle: Float, color: Int, lowBit: Boolean) {
        canvas.save()
        canvas.rotate(angle, cx, cy)
        paint.reset()
        paint.isAntiAlias = !lowBit
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4.6f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        path.reset()
        path.moveTo(cx - 28f, cy)
        path.lineTo(cx + 28f, cy)
        path.moveTo(cx + 10f, cy - 18f)
        path.lineTo(cx + 28f, cy)
        path.lineTo(cx + 10f, cy + 18f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    // ---------- offline ----------

    /** No reading for [AimiOverlayRules.STALE_MS]: the forecast is not shown, it would be old news. */
    private fun offline(): Boolean = AimiOverlayRules.offline(phoneLinked, lastBgTime, System.currentTimeMillis())

    private fun drawOffline(canvas: Canvas) {
        val text = if (phoneLinked) NO_DATA else PHONE_DISCONNECTED
        paint.reset()
        paint.isAntiAlias = true
        paint.isFakeBoldText = true
        paint.textSize = 14f
        val w = paint.measureText(text) + 28f
        box.set(200f - w / 2, 252f, 200f + w / 2, 276f)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF2A1F05.toInt()
        canvas.drawRoundRect(box, 12f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = AMBER
        canvas.drawRoundRect(box, 12f, 12f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, 200f, 269f, paint)
        paint.isFakeBoldText = true
        paint.textSize = 13f
        paint.color = LABEL_COLOR
        canvas.drawText(FORECAST_OFF, 200f, 296f, paint)
    }

    // ---------- IOB and basal pills ----------

    private fun drawStatusPills(canvas: Canvas, d: EventData.TreatmentData.Aimi?) {
        val iob = d?.iobU?.takeIf { it > AimiOverlayRules.UNKNOWN_LIMIT }
        val iobBlue = iob != null && AimiOverlayRules.iobNegative(iob)
        statusPill(
            canvas, 106f, 318f, 186f, 341f, "IOB",
            iob?.let { AimiOverlayRules.iobText(it) } ?: NO_VALUE,
            if (iobBlue) SENSOR_COLOR else TEXT_COLOR, if (iobBlue) PILL_BLUE_FILL else PILL_FILL,
            if (iobBlue) SENSOR_COLOR else PILL_STROKE
        )
        val now = d?.basalNowU?.takeIf { it > AimiOverlayRules.UNKNOWN_LIMIT }
        val profile = d?.basalProfileU?.takeIf { it > AimiOverlayRules.UNKNOWN_LIMIT }
        val rate = now?.let { AimiOverlayRules.rate(it) } ?: NO_VALUE
        val look = when (AimiOverlayRules.basalState(now, profile)) {
            BasalState.UNKNOWN       -> Look(NO_VALUE, TEXT_COLOR, PILL_FILL, PILL_STROKE, 0)
            BasalState.PAUSED        -> Look("0 Pause", RED, PILL_RED_FILL, RED, 0)
            BasalState.BELOW_PROFILE -> Look(rate, SENSOR_COLOR, PILL_BLUE_FILL, SENSOR_COLOR, -1)
            BasalState.ABOVE_PROFILE -> Look(rate, AMBER, PILL_AMBER_FILL, AMBER, 1)
            BasalState.AS_PROFILE    -> Look(rate, TEXT_COLOR, PILL_FILL, PILL_STROKE, 0)
        }
        statusPill(canvas, 192f, 318f, 294f, 341f, "Basal", look.value, look.color, look.fill, look.stroke, look.trend)
    }

    /** [trend] is 1 for a triangle up, -1 for down, 0 for none; drawn, not typed, so no font has to have the glyph. */
    private data class Look(val value: String, val color: Int, val fill: Int, val stroke: Int, val trend: Int)

    /** A pill with the muted label and the value after it, the two centred together. */
    private fun statusPill(
        canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, value: String, valueColor: Int, fill: Int, stroke: Int, trend: Int = 0
    ) {
        box.set(l, t, r, b)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(box, 11f, 11f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (stroke == PILL_STROKE) 1f else 1.6f
        paint.color = stroke
        canvas.drawRoundRect(box, 11f, 11f, paint)
        paint.reset()
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.LEFT
        paint.isFakeBoldText = true
        paint.textSize = 12f
        val labelW = paint.measureText("$label ")
        paint.textSize = STATUS_SIZE
        val mark = if (trend != 0) TRIANGLE + 4f else 0f
        // The value shrinks when label, value and triangle do not fit the pill.
        val room = (box.right - box.left) - 8f - labelW - mark
        val wide = paint.measureText(value)
        if (wide > room) paint.textSize = STATUS_SIZE * room / wide
        val valueW = paint.measureText(value)
        val x = (box.left + box.right) / 2 - (labelW + valueW + mark) / 2
        val y = box.top + 17f
        canvas.drawText(value, x + labelW, y, paint.also { it.color = valueColor })
        if (trend != 0) {
            val tx = x + labelW + valueW + 4f
            path.reset()
            if (trend > 0) {
                path.moveTo(tx, y)
                path.lineTo(tx + TRIANGLE, y)
                path.lineTo(tx + TRIANGLE / 2, y - TRIANGLE)
            } else {
                path.moveTo(tx, y - TRIANGLE)
                path.lineTo(tx + TRIANGLE, y - TRIANGLE)
                path.lineTo(tx + TRIANGLE / 2, y)
            }
            path.close()
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
        }
        paint.textSize = 12f
        paint.color = LABEL_COLOR
        canvas.drawText("$label ", x, y, paint)
    }

    // ---------- border ----------

    /** The red border of a low warning, or the dashed grey one while the phone or the readings are missing. */
    private fun drawBorder(canvas: Canvas, lowWarning: Boolean, stale: Boolean) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        when {
            stale      -> {
                paint.color = MUTED_COLOR
                paint.pathEffect = dashBorder
                canvas.drawCircle(200f, 200f, BORDER_R, paint)
            }

            lowWarning -> {
                paint.color = RED
                canvas.drawCircle(200f, 200f, BORDER_R, paint)
            }
        }
    }

    // ---------- rings ----------

    private fun arc(canvas: Canvas, start: Float, sweep: Float, color: Int) {
        if (sweep <= 0f) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = RING_W
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = color
        canvas.drawArc(ringRect, start, sweep, false, paint)
    }

    private fun levelColor(level: Int, normal: Int): Int = when (level) {
        2    -> RED
        1    -> AMBER
        else -> normal
    }

    private fun drawRings(canvas: Canvas, d: EventData.TreatmentData.Aimi) {
        // Time in range, left to right: low, in range, high. 110 degrees from -145.
        if (d.tirLow >= 0 && d.tirIn >= 0 && d.tirHigh >= 0) {
            val total = (d.tirLow + d.tirIn + d.tirHigh).coerceAtLeast(1).toFloat()
            var start = TIR_START
            for ((value, color) in listOf(d.tirLow to RED, d.tirIn to GREEN, d.tirHigh to AMBER)) {
                val sweep = TIR_SPAN * value / total
                arc(canvas, start, sweep, color)
                start += sweep
            }
            // The number under the top of the ring.
            paint.reset()
            paint.isAntiAlias = true
            paint.color = LABEL_COLOR
            paint.isFakeBoldText = true
            paint.textSize = 14f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("TIR ${d.tirIn} %", 200f, 46f, paint)
        }
        // Pod (bottom left) and sensor (bottom right) fill from the outer end towards the bottom.
        if (d.podFraction >= 0f) {
            val f = d.podFraction.coerceIn(0f, 1f)
            arc(canvas, POD_END - POD_SPAN * f, POD_SPAN * f, levelColor(d.podLevel, POD_COLOR))
        }
        if (d.sensorFraction >= 0f) {
            val f = d.sensorFraction.coerceIn(0f, 1f)
            arc(canvas, SENSOR_END - SENSOR_SPAN * f, SENSOR_SPAN * f, levelColor(d.sensorLevel, SENSOR_COLOR))
        }
        // The watch's own battery, right side, from the top downwards.
        if (watchBattery >= 0f) {
            val f = watchBattery.coerceIn(0f, 1f)
            val color = when {
                f < 0.15f -> RED
                f < 0.30f -> AMBER
                else      -> GREY
            }
            arc(canvas, BATTERY_START, BATTERY_SPAN * f, color)
        }
    }

    // ---------- graph ----------

    private fun drawGraph(canvas: Canvas, d: EventData.TreatmentData.Aimi, low: LowState) {
        if (history.isEmpty()) return
        val ref = lastBgTime
        val from = ref - HISTORY_MS
        val to = ref + FUTURE_MS
        val shown = history.filter { it.timeStamp in from..ref }
        if (shown.isEmpty()) return

        val values = ArrayList<Double>()
        shown.forEach { values += it.sgv }
        listOf(d.centre, d.low, d.high).forEach { list -> list.forEach { values += it.value } }
        values += d.lowMarkMgdl
        values += d.highMarkMgdl
        var min = (values.minOrNull() ?: 70.0) - 8.0
        var max = (values.maxOrNull() ?: 180.0) + 8.0
        if (max - min < 60.0) {
            val mid = (max + min) / 2
            min = mid - 30.0
            max = mid + 30.0
        }

        fun x(t: Long): Float = GX0 + (t - from).toFloat() / (to - from).toFloat() * (GX1 - GX0)
        fun y(v: Double): Float = (GY1 - ((v - min) / (max - min)).toFloat() * (GY1 - GY0))

        // In-range zone and its two lines.
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = GREEN
        paint.alpha = 18
        canvas.drawRect(GX0, y(d.highMarkMgdl), GX1, y(d.lowMarkMgdl), paint)
        for ((mark, color) in listOf(d.lowMarkMgdl to RED, d.highMarkMgdl to AMBER)) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = color
            paint.alpha = 150
            paint.pathEffect = dashMark
            canvas.drawLine(GX0, y(mark), GX1, y(mark), paint)
        }

        // Forecast band: high edge forwards, low edge backwards.
        if (d.centre.size > 1 && d.low.size == d.centre.size && d.high.size == d.centre.size) {
            path.reset()
            d.high.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.timeStamp), y(p.value)) else path.lineTo(x(p.timeStamp), y(p.value)) }
            d.low.asReversed().forEach { p -> path.lineTo(x(p.timeStamp), y(p.value)) }
            path.close()
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = FORECAST
            paint.alpha = 61
            canvas.drawPath(path, paint)

            path.reset()
            d.centre.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.timeStamp), y(p.value)) else path.lineTo(x(p.timeStamp), y(p.value)) }
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = FORECAST
            paint.pathEffect = dashForecast
            canvas.drawPath(path, paint)
        }

        // The insulin-only curve, only while a low is possible.
        if (low.shown && d.insulinOnly.size > 1) {
            path.reset()
            d.insulinOnly.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.timeStamp), y(p.value)) else path.lineTo(x(p.timeStamp), y(p.value)) }
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = RED
            paint.pathEffect = dashLow
            canvas.drawPath(path, paint)
            paint.reset()
            paint.isAntiAlias = true
            paint.color = RED
            paint.textSize = 13f
            paint.isFakeBoldText = true
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(LOW_POSSIBLE, GX1 - 2f, GY0 + 9f, paint)
        }

        // Acknowledged: a small hollow red dot instead of the curve and the label.
        if (low.active && !low.shown) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = RED
            canvas.drawCircle(GX1 - 8f, GY0 + 7f, 4f, paint)
        }

        // History.
        path.reset()
        shown.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.timeStamp), y(p.sgv)) else path.lineTo(x(p.timeStamp), y(p.sgv)) }
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = GREEN
        canvas.drawPath(path, paint)

        // Now: marker line and dot at the newest reading.
        val nowX = x(ref)
        val nowY = y(shown.last().sgv)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0xFF475569.toInt()
        canvas.drawLine(nowX, GY0 - 2f, nowX, SMB_SEPARATOR, paint)
        paint.style = Paint.Style.FILL
        paint.color = GREEN
        paint.alpha = 64
        canvas.drawCircle(nowX, nowY, 8f, paint)
        paint.alpha = 255
        canvas.drawCircle(nowX, nowY, 4.2f, paint)

        // SMB strip under the graph: one dot per SMB, the size follows the phone's size classes.
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = SMB_COLOR
        boluses.filter { it.isSMB && it.isValid && it.date in from..ref }.forEach { t ->
            val r = when {
                smbSmallBelow > 0 && t.bolus < smbSmallBelow -> 2.2f
                smbLargeFrom > 0 && t.bolus >= smbLargeFrom   -> 3.8f
                else                                          -> 3f
            }
            canvas.drawCircle(x(t.date), SMB_Y, r, paint)
        }
    }

    private companion object {

        const val DESIGN = 400f
        const val RING_R = 181f
        const val RING_W = 7.4f
        const val BORDER_R = 197f

        // Ring angles in degrees, 0 at 3 o'clock and growing clockwise, as Canvas.drawArc takes them.
        const val TIR_START = 215f
        const val TIR_SPAN = 110f
        const val POD_END = 145f
        const val POD_SPAN = 40f
        const val SENSOR_END = 75f
        const val SENSOR_SPAN = 40f
        const val BATTERY_START = -25f
        const val BATTERY_SPAN = 50f

        // The graph area inside the card the zip draws (86..313 x 240..307).
        const val GX0 = 92f
        const val GX1 = 309f
        const val GY0 = 247f
        const val GY1 = 283f
        const val SMB_SEPARATOR = 291f
        const val SMB_Y = 299f

        const val HISTORY_MS = 120 * 60_000L
        const val FUTURE_MS = 60 * 60_000L
        const val LOW_POSSIBLE = "Low possible"
        const val NO_VALUE = "–"
        const val REDRAW_MS = 5_000L
        const val TRIANGLE = 8f

        /** Text sizes in the 400 design space: the values of the side pills and of IOB and basal. */
        const val VALUE_SIZE = 19f
        const val STATUS_SIZE = 14.5f
        const val KEY_ACK_AT = "low_ack_at"
        const val LINK_CHECK_MS = 2 * 60_000L
        const val PHONE_DISCONNECTED = "Phone disconnected"
        const val NO_DATA = "No recent data"
        const val FORECAST_OFF = "Forecast off"
        val PILL_BLUE_FILL = 0xFF0C2036.toInt()
        val PILL_AMBER_FILL = 0xFF2A1F05.toInt()
        val PILL_RED_FILL = 0xFF2A0C0C.toInt()
        const val PULSE_FRESH_MS = 15 * 60_000L
        val PILL_FILL = 0xFF0F1A2E.toInt()
        val PILL_STROKE = 0xFF1F3050.toInt()
        val PULSE_COLOR = 0xFFF87171.toInt()
        val STEPS_COLOR = 0xFFA78BFA.toInt()
        val TEXT_COLOR = 0xFFF8FAFC.toInt()
        val MUTED_COLOR = 0xFF64748B.toInt()
        val LABEL_COLOR = 0xFFCBD5E1.toInt()

        val GREEN = 0xFF22E06B.toInt()
        val AMBER = 0xFFF5B301.toInt()
        val RED = 0xFFEF4444.toInt()
        val GREY = 0xFF94A3B8.toInt()
        val POD_COLOR = 0xFF34D399.toInt()
        val SENSOR_COLOR = 0xFF38BDF8.toInt()
        val FORECAST = 0xFFE6D39A.toInt()
        val SMB_COLOR = 0xFF7DD3FC.toInt()
    }
}
