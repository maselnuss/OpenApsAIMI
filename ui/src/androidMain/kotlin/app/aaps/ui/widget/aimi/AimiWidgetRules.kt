package app.aaps.ui.widget.aimi

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.pump.defs.PumpType

/** The pure rules of the AIMI widget, apart from the data sources, so they can be tested alone. */
internal object AimiWidgetRules {

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
