package app.aaps.ui.widget.aimi

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.overview.graph.BgType

/** The pure rules of the AIMI widget, apart from the data sources, so they can be tested alone. */
internal object AimiWidgetRules {

    /** How far before the target the last point of a curve may end and still count as reaching it. */
    const val TOLERANCE_MS = 150_000L

    /** A patch pump whose reading stops at this amount or below reports the exact amount only under it. */
    private const val EXACT_READING_BELOW_U = 50

    /**
     * Hours a pump keeps running after the planned change time. Only where the code of AAPS knows it:
     * Omnipod runs 80 hours in all (see `PodConstants.MAX_POD_LIFETIME` and the Eros overview), 8 hours
     * after the planned 72. Any other pump gets none, so the bar never shows more time than is certain.
     */
    fun podGraceHours(pumpType: PumpType): Double = when (pumpType) {
        PumpType.OMNIPOD_DASH, PumpType.OMNIPOD_EROS -> 8.0
        else                                         -> 0.0
    }

    /**
     * Hours a sensor keeps working after its planned end. A Dexcom G7 and ONE+ sensor runs 12 hours more
     * (`DexcomOnePlusStaging.SENSOR_GRACE_MS`). No other sensor has a value in the code, so it gets none,
     * and so does an unknown source ([sensor] null).
     */
    fun sensorGraceHours(sensor: SourceSensor?): Double = when (sensor) {
        SourceSensor.DEXCOM_G7, SourceSensor.DEXCOM_G7_NATIVE, SourceSensor.DEXCOM_ONEPLUS_NATIVE -> 12.0
        else                                                                                       -> 0.0
    }

    /**
     * Below which amount the reservoir turns yellow. A patch pump whose reading stops at [maxReadingU]
     * (Omnipod and EOPatch: 50) shows the exact amount only under that, so that is the mark. Every other
     * pump follows the warning of the overview settings, [settingWarnU].
     */
    fun reservoirWarnBelowU(isPatchPump: Boolean, maxReadingU: Int, settingWarnU: Int): Int =
        if (isPatchPump && maxReadingU <= EXACT_READING_BELOW_U) maxReadingU else settingWarnU

    /** Time left of the planned life, or - once that has run out - of the grace period. */
    class LifeTime(val left: Double, val fraction: Float, val graceLeft: Double?)

    /**
     * Time left of the planned life, or - once that has run out - of the grace period. The fraction is
     * for the bar: 1 for new down to 0, and during the grace period the share of it that is left.
     */
    fun lifeTime(ageHours: Double, plannedHours: Double, graceHours: Double): LifeTime {
        if (ageHours < plannedHours) {
            val left = plannedHours - ageHours
            return LifeTime(left, (left / plannedHours).toFloat().coerceIn(0f, 1f), null)
        }
        if (graceHours <= 0.0) return LifeTime(0.0, 0f, null)
        val graceLeft = (plannedHours + graceHours - ageHours).coerceAtLeast(0.0)
        return LifeTime(0.0, (graceLeft / graceHours).toFloat().coerceIn(0f, 1f), graceLeft)
    }

    fun ageLevel(ageHours: Double, warnHours: Int, criticalHours: Int): AimiWidgetLevel = when {
        ageHours >= criticalHours -> AimiWidgetLevel.CRIT
        ageHours >= warnHours     -> AimiWidgetLevel.WARN
        else                      -> AimiWidgetLevel.NORMAL
    }

    /** Value of the main curve at [target], only when the curve really reaches it. Never the end value. */
    fun predictionAt(curves: List<AimiWidgetCurve>, target: Long): Double? {
        // AIMI treats the UAM curve as its authoritative scenario (the IOB curve is the pessimistic floor).
        val curve = curves.firstOrNull { it.type == BgType.UAM_PREDICTION }
            ?: curves.firstOrNull { it.type == BgType.IOB_PREDICTION }
            ?: curves.firstOrNull() ?: return null
        val points = curve.points
        if (points.size < 2) return null
        if (points.first().timestamp > target) return null
        if (points.last().timestamp < target - TOLERANCE_MS) return null
        if (points.last().timestamp <= target) return points.last().value
        val after = points.indexOfFirst { it.timestamp >= target }
        if (after <= 0) return points.first().value
        val a = points[after - 1]
        val b = points[after]
        val span = (b.timestamp - a.timestamp).coerceAtLeast(1L)
        return a.value + (b.value - a.value) * (target - a.timestamp).toDouble() / span
    }

    /** Share of [valuesMgdl] below, in and above the range, each rounded to a whole percent. Null without readings. */
    fun timeInRange(valuesMgdl: List<Double>, lowMgdl: Double, highMgdl: Double): AimiWidgetTir? {
        if (valuesMgdl.isEmpty()) return null
        val inRange = valuesMgdl.count { it in lowMgdl..highMgdl }
        val below = valuesMgdl.count { it < lowMgdl }
        val above = valuesMgdl.count { it > highMgdl }
        val n = valuesMgdl.size.toDouble()
        // Rounded, not cut off: 91.95 is 92, as the dashboard shows it.
        return AimiWidgetTir(low = Math.round(below * 100.0 / n).toInt(), inRange = Math.round(inRange * 100.0 / n).toInt(), high = Math.round(above * 100.0 / n).toInt())
    }
}
