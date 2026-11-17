package app.aaps.ui.widget.aimi

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.overview.graph.BgType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class AimiWidgetRulesTest {

    private val min = 60_000L
    private val target = 1_800_000_000_000L

    private fun curve(type: BgType, vararg points: Pair<Long, Double>) =
        AimiWidgetCurve(type, points.map { AimiWidgetPoint(it.first, it.second) })

    // --- lifeTime ---

    @Test
    fun `a new pod has the whole planned time left`() {
        val time = AimiWidgetRules.lifeTime(ageHours = 0.0, plannedHours = 72.0, graceHours = 8.0)

        assertThat(time.left).isEqualTo(72.0)
        assertThat(time.fraction).isEqualTo(1f)
        assertThat(time.graceLeft).isNull()
    }

    @Test
    fun `half way through the planned time the bar is half full`() {
        val time = AimiWidgetRules.lifeTime(ageHours = 36.0, plannedHours = 72.0, graceHours = 8.0)

        assertThat(time.left).isEqualTo(36.0)
        assertThat(time.fraction).isEqualTo(0.5f)
        assertThat(time.graceLeft).isNull()
    }

    @Test
    fun `after the planned end the bar counts the grace period down`() {
        val time = AimiWidgetRules.lifeTime(ageHours = 76.0, plannedHours = 72.0, graceHours = 8.0)

        assertThat(time.left).isEqualTo(0.0)
        assertThat(time.graceLeft).isEqualTo(4.0)
        assertThat(time.fraction).isEqualTo(0.5f)
    }

    @Test
    fun `the grace period never goes below zero`() {
        val time = AimiWidgetRules.lifeTime(ageHours = 100.0, plannedHours = 72.0, graceHours = 8.0)

        assertThat(time.graceLeft).isEqualTo(0.0)
        assertThat(time.fraction).isEqualTo(0f)
    }

    @Test
    fun `without a grace period the end is the end`() {
        val time = AimiWidgetRules.lifeTime(ageHours = 80.0, plannedHours = 72.0, graceHours = 0.0)

        assertThat(time.left).isEqualTo(0.0)
        assertThat(time.fraction).isEqualTo(0f)
        assertThat(time.graceLeft).isNull()
    }

    // --- ageLevel ---

    @Test
    fun `age level follows warning and critical hours`() {
        assertThat(AimiWidgetRules.ageLevel(ageHours = 10.0, warnHours = 60, criticalHours = 72)).isEqualTo(AimiWidgetLevel.NORMAL)
        assertThat(AimiWidgetRules.ageLevel(ageHours = 60.0, warnHours = 60, criticalHours = 72)).isEqualTo(AimiWidgetLevel.WARN)
        assertThat(AimiWidgetRules.ageLevel(ageHours = 71.9, warnHours = 60, criticalHours = 72)).isEqualTo(AimiWidgetLevel.WARN)
        assertThat(AimiWidgetRules.ageLevel(ageHours = 72.0, warnHours = 60, criticalHours = 72)).isEqualTo(AimiWidgetLevel.CRIT)
    }

    // --- predictionAt ---

    @Test
    fun `no curve gives no prediction`() {
        assertThat(AimiWidgetRules.predictionAt(emptyList(), target)).isNull()
    }

    @Test
    fun `a curve with one point gives no prediction`() {
        val curves = listOf(curve(BgType.UAM_PREDICTION, target to 100.0))

        assertThat(AimiWidgetRules.predictionAt(curves, target)).isNull()
    }

    @Test
    fun `the value between two points is interpolated`() {
        val curves = listOf(curve(BgType.UAM_PREDICTION, (target - 10 * min) to 100.0, (target + 10 * min) to 120.0))

        assertThat(AimiWidgetRules.predictionAt(curves, target)).isEqualTo(110.0)
    }

    @Test
    fun `a curve that ends before the target by more than the tolerance gives no prediction`() {
        val curves = listOf(curve(BgType.UAM_PREDICTION, (target - 30 * min) to 100.0, (target - 10 * min) to 120.0))

        assertThat(AimiWidgetRules.predictionAt(curves, target)).isNull()
    }

    @Test
    fun `a curve that ends within the tolerance gives its last value`() {
        val curves = listOf(curve(BgType.UAM_PREDICTION, (target - 30 * min) to 100.0, (target - 2 * min) to 120.0))

        assertThat(AimiWidgetRules.predictionAt(curves, target)).isEqualTo(120.0)
    }

    @Test
    fun `a curve that starts after the target gives no prediction`() {
        val curves = listOf(curve(BgType.UAM_PREDICTION, (target + 5 * min) to 100.0, (target + 10 * min) to 120.0))

        assertThat(AimiWidgetRules.predictionAt(curves, target)).isNull()
    }

    @Test
    fun `the UAM curve is preferred over the IOB curve`() {
        val iob = curve(BgType.IOB_PREDICTION, (target - 10 * min) to 80.0, (target + 10 * min) to 80.0)
        val uam = curve(BgType.UAM_PREDICTION, (target - 10 * min) to 100.0, (target + 10 * min) to 100.0)

        assertThat(AimiWidgetRules.predictionAt(listOf(iob, uam), target)).isEqualTo(100.0)
    }

    @Test
    fun `without a UAM curve the IOB curve is used`() {
        val zt = curve(BgType.ZT_PREDICTION, (target - 10 * min) to 60.0, (target + 10 * min) to 60.0)
        val iob = curve(BgType.IOB_PREDICTION, (target - 10 * min) to 80.0, (target + 10 * min) to 80.0)

        assertThat(AimiWidgetRules.predictionAt(listOf(zt, iob), target)).isEqualTo(80.0)
    }

    // --- timeInRange ---

    @Test
    fun `no readings give no time in range`() {
        assertThat(AimiWidgetRules.timeInRange(emptyList(), 70.0, 180.0)).isNull()
    }

    @Test
    fun `readings are split into low, in range and high`() {
        val values = listOf(60.0, 70.0, 100.0, 180.0, 200.0)

        val tir = AimiWidgetRules.timeInRange(values, 70.0, 180.0)

        // the limits themselves are in range
        assertThat(tir).isEqualTo(AimiWidgetTir(low = 20, inRange = 60, high = 20))
    }

    @Test
    fun `shares are rounded and not cut off`() {
        // 23 of 25 readings in range = 92 %, 2 of 25 high = 8 %
        val values = List(23) { 100.0 } + List(2) { 250.0 }

        val tir = AimiWidgetRules.timeInRange(values, 70.0, 180.0)

        assertThat(tir).isEqualTo(AimiWidgetTir(low = 0, inRange = 92, high = 8))
    }

    // --- podGraceHours ---

    @Test
    fun `an Omnipod keeps running 8 hours after the planned end`() {
        assertThat(AimiWidgetRules.podGraceHours(PumpType.OMNIPOD_DASH)).isEqualTo(8.0)
        assertThat(AimiWidgetRules.podGraceHours(PumpType.OMNIPOD_EROS)).isEqualTo(8.0)
    }

    @Test
    fun `a pump without a known grace period gets none`() {
        assertThat(AimiWidgetRules.podGraceHours(PumpType.MEDTRUM_NANO)).isEqualTo(0.0)
        assertThat(AimiWidgetRules.podGraceHours(PumpType.EQUIL)).isEqualTo(0.0)
        assertThat(AimiWidgetRules.podGraceHours(PumpType.GENERIC_AAPS)).isEqualTo(0.0)
    }

    // --- sensorGraceHours ---

    @Test
    fun `a Dexcom G7 or ONE+ sensor keeps working 12 hours after its planned end`() {
        assertThat(AimiWidgetRules.sensorGraceHours(SourceSensor.DEXCOM_G7)).isEqualTo(12.0)
        assertThat(AimiWidgetRules.sensorGraceHours(SourceSensor.DEXCOM_G7_NATIVE)).isEqualTo(12.0)
        assertThat(AimiWidgetRules.sensorGraceHours(SourceSensor.DEXCOM_ONEPLUS_NATIVE)).isEqualTo(12.0)
    }

    @Test
    fun `another or an unknown sensor gets no grace period`() {
        assertThat(AimiWidgetRules.sensorGraceHours(SourceSensor.DEXCOM_G6)).isEqualTo(0.0)
        assertThat(AimiWidgetRules.sensorGraceHours(SourceSensor.LIBRE_2)).isEqualTo(0.0)
        assertThat(AimiWidgetRules.sensorGraceHours(SourceSensor.LIBRE_3)).isEqualTo(0.0)
        assertThat(AimiWidgetRules.sensorGraceHours(null)).isEqualTo(0.0)
    }

    // --- reservoirWarnBelowU ---

    @Test
    fun `a patch pump that reports exactly only under 50 U turns yellow at 50`() {
        assertThat(AimiWidgetRules.reservoirWarnBelowU(isPatchPump = true, maxReadingU = 50, settingWarnU = 80)).isEqualTo(50)
    }

    @Test
    fun `a pump that reads its real level follows the setting`() {
        // patch pump with a 200 U reading (Medtrum Nano) and a 300 U one
        assertThat(AimiWidgetRules.reservoirWarnBelowU(isPatchPump = true, maxReadingU = 200, settingWarnU = 80)).isEqualTo(80)
        assertThat(AimiWidgetRules.reservoirWarnBelowU(isPatchPump = true, maxReadingU = 300, settingWarnU = 40)).isEqualTo(40)
    }

    @Test
    fun `a pump that is not a patch pump follows the setting even with a reading of 50`() {
        assertThat(AimiWidgetRules.reservoirWarnBelowU(isPatchPump = false, maxReadingU = 50, settingWarnU = 80)).isEqualTo(80)
    }
}
