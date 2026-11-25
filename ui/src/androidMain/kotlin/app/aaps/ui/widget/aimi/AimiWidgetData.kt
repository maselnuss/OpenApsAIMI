package app.aaps.ui.widget.aimi

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.ui.compose.dashboard.DisplayForecast
import app.aaps.ui.widget.state.WidgetBgColorClass

enum class AimiWidgetLevel { NORMAL, WARN, CRIT }

/** Share of today's readings (from midnight, shown from 6 o'clock) below, in and above the range, each in percent. */
data class AimiWidgetTir(val low: Int, val inRange: Int, val high: Int)

/** Pod (or cannula) age with the time left. [fractionLeft] is 1 for new and 0 for expired. */
data class AimiWidgetPod(
    val isPatchPump: Boolean,
    val hoursLeft: Double,
    val fractionLeft: Float,
    val level: AimiWidgetLevel,
    /** Insulin left in U. Null when the pump has not reported it. */
    val reservoirU: Double?,
    /** The pump only reports "more than [reservoirU]" (Omnipod: over 50 U). */
    val reservoirCapped: Boolean,
    val reservoirLevel: AimiWidgetLevel,
    /** Hours left of the grace period after the planned end. Null while the planned time has not run out. */
    val graceHoursLeft: Double?
)

data class AimiWidgetSensor(
    val hoursLeft: Double,
    val fractionLeft: Float,
    val level: AimiWidgetLevel,
    /** Hours left of the grace period after the planned end. Null while the planned time has not run out. */
    val graceHoursLeft: Double?
)

/**
 * Everything the AIMI widget draws, already read. Own class on purpose: the shared WidgetState
 * is also used by iOS, so nothing here touches it.
 */
data class AimiWidgetData(
    val now: Long,
    /** Opacity of the card, 0 to 255, from the widget configuration (the same setting the other widgets use). */
    val cardAlpha: Int,
    val units: GlucoseUnit,
    val bgText: String,
    val bgClass: WidgetBgColorClass,
    val trend: TrendArrow?,
    val deltaText: String,
    val timeAgoText: String,
    val stale: Boolean,
    val runningModeText: String,
    val runningModeActive: Boolean,
    /** Current reading in user units, for the chart. */
    val currentValue: Double?,
    val history: List<BgDataPoint>,
    val historyMs: Long,
    val lowMark: Double,
    val highMark: Double,
    /**
     * The display forecast (measured trend fading out, range, low warning), the same as on MTR's home screens.
     * Not a loop curve: see [DisplayForecast]. Null without a fresh reading.
     */
    val forecast: DisplayForecast?,
    /** The forecast 30 min ahead in user units. Null without a forecast or when the trend could not be measured. */
    val prediction30: Double?,
    val tirPercent: Int?,
    /** The same time in range split into low, in range and high. */
    val tir: AimiWidgetTir?,
    val iob: Double,
    val basalNow: Double?,
    /** The basal rate of the profile at this time, to see how far the running rate is from it. */
    val profileBasal: Double?,
    val isfText: String?,
    val lastSmb: Double?,
    val pod: AimiWidgetPod?,
    val sensor: AimiWidgetSensor?,
    val profileText: String
)
