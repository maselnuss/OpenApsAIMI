package app.aaps.wear.watchfaces.utils

import app.aaps.core.interfaces.rx.weardata.EventData.TreatmentData.AimiPoint
import app.aaps.wear.watchfaces.utils.AimiOverlayRules.BasalState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

internal class AimiOverlayRulesTest {

    private val minute = 60_000L
    private val t0 = 1_000_000_000L

    // ---------- low warning ----------

    @Test
    fun `warning is never active when switched off`() {
        assertThat(AimiOverlayRules.lowWarningActive(false, 60, lowRisk = true, insulinOnly = emptyList(), lastBgTime = t0)).isFalse()
    }

    @Test
    fun `lead of 60 minutes follows the phone flag`() {
        assertThat(AimiOverlayRules.lowWarningActive(true, 60, lowRisk = true, insulinOnly = emptyList(), lastBgTime = t0)).isTrue()
        assertThat(AimiOverlayRules.lowWarningActive(true, 60, lowRisk = false, insulinOnly = emptyList(), lastBgTime = t0)).isFalse()
    }

    @Test
    fun `lead of 30 minutes needs a point under the line within 30 minutes`() {
        val soon = listOf(AimiPoint(t0 + 20 * minute, 65.0))
        val late = listOf(AimiPoint(t0 + 45 * minute, 60.0))
        assertThat(AimiOverlayRules.lowWarningActive(true, 30, lowRisk = true, insulinOnly = soon, lastBgTime = t0)).isTrue()
        assertThat(AimiOverlayRules.lowWarningActive(true, 30, lowRisk = true, insulinOnly = late, lastBgTime = t0)).isFalse()
    }

    @Test
    fun `lead of 30 minutes ignores points at or above 70`() {
        val onTheLine = listOf(AimiPoint(t0 + 10 * minute, 70.0))
        assertThat(AimiOverlayRules.lowWarningActive(true, 30, lowRisk = true, insulinOnly = onTheLine, lastBgTime = t0)).isFalse()
    }

    @Test
    fun `a point exactly at the end of the lead still counts`() {
        val atEnd = listOf(AimiPoint(t0 + 30 * minute, 69.0))
        assertThat(AimiOverlayRules.lowWarningActive(true, 30, lowRisk = true, insulinOnly = atEnd, lastBgTime = t0)).isTrue()
    }

    // ---------- acknowledgement ----------

    @Test
    fun `nothing acknowledged without a time`() {
        assertThat(AimiOverlayRules.acknowledged(0L, 60, t0)).isFalse()
    }

    @Test
    fun `acknowledgement holds inside its duration and ends after it`() {
        assertThat(AimiOverlayRules.acknowledged(t0, 30, t0 + 29 * minute)).isTrue()
        assertThat(AimiOverlayRules.acknowledged(t0, 30, t0 + 30 * minute)).isFalse()
    }

    @Test
    fun `duration 0 holds until the caller forgets it`() {
        assertThat(AimiOverlayRules.acknowledged(t0, 0, t0 + 24 * 60 * minute)).isTrue()
    }

    @Test
    fun `warning shown only when active and either a real low or not acknowledged`() {
        assertThat(AimiOverlayRules.warningShown(active = false, realLow = false, acknowledged = false)).isFalse()
        assertThat(AimiOverlayRules.warningShown(active = true, realLow = false, acknowledged = false)).isTrue()
        assertThat(AimiOverlayRules.warningShown(active = true, realLow = false, acknowledged = true)).isFalse()
        assertThat(AimiOverlayRules.warningShown(active = true, realLow = true, acknowledged = true)).isTrue()
    }

    @Test
    fun `double tap needs a first tap inside the window`() {
        assertThat(AimiOverlayRules.isDoubleTap(0L, t0)).isFalse()
        assertThat(AimiOverlayRules.isDoubleTap(t0, t0 + 500)).isTrue()
        assertThat(AimiOverlayRules.isDoubleTap(t0, t0 + 800)).isTrue()
        assertThat(AimiOverlayRules.isDoubleTap(t0, t0 + 801)).isFalse()
    }

    // ---------- offline ----------

    @Test
    fun `a fresh reading is not offline even without a phone in reach`() {
        assertThat(AimiOverlayRules.offline(nearbyLinked = false, lastBgTime = t0, now = t0 + 6 * minute)).isFalse()
    }

    @Test
    fun `a reading older than 7 minutes is offline whatever the link says`() {
        assertThat(AimiOverlayRules.offline(nearbyLinked = true, lastBgTime = t0, now = t0 + 8 * minute)).isTrue()
        assertThat(AimiOverlayRules.offline(nearbyLinked = false, lastBgTime = t0, now = t0 + 8 * minute)).isTrue()
    }

    @Test
    fun `exactly 7 minutes is still fresh`() {
        assertThat(AimiOverlayRules.offline(nearbyLinked = true, lastBgTime = t0, now = t0 + 7 * minute)).isFalse()
    }

    @Test
    fun `without any reading only the link decides`() {
        assertThat(AimiOverlayRules.offline(nearbyLinked = false, lastBgTime = 0L, now = t0)).isTrue()
        assertThat(AimiOverlayRules.offline(nearbyLinked = true, lastBgTime = 0L, now = t0)).isFalse()
    }

    // ---------- steps ----------

    private val none = AimiOverlayRules.NO_BASELINE

    @Test
    fun `first reading ever starts the day at zero`() {
        val r = AimiOverlayRules.stepsToday(raw = 12_000, day = 2026330, savedDay = 0, savedBaseline = none, savedLastRaw = -1, savedSteps = 0)
        assertThat(r.steps).isEqualTo(0)
        assertThat(r.baseline).isEqualTo(12_000)
    }

    @Test
    fun `later reading on the same day counts from the baseline`() {
        val r = AimiOverlayRules.stepsToday(raw = 12_400, day = 2026330, savedDay = 2026330, savedBaseline = 12_000, savedLastRaw = 12_390, savedSteps = 390)
        assertThat(r.steps).isEqualTo(400)
        assertThat(r.baseline).isEqualTo(12_000)
    }

    @Test
    fun `new day starts again from the current count`() {
        val r = AimiOverlayRules.stepsToday(raw = 20_000, day = 2026331, savedDay = 2026330, savedBaseline = 12_000, savedLastRaw = 19_900, savedSteps = 7_900)
        assertThat(r.steps).isEqualTo(0)
        assertThat(r.baseline).isEqualTo(20_000)
    }

    @Test
    fun `a restart of the watch keeps the steps so far and adds the new count`() {
        val r = AimiOverlayRules.stepsToday(raw = 150, day = 2026330, savedDay = 2026330, savedBaseline = 12_000, savedLastRaw = 15_000, savedSteps = 3_000)
        assertThat(r.steps).isEqualTo(3_150)
        assertThat(r.baseline).isEqualTo(-3_000)
    }

    @Test
    fun `after a restart the next reading continues from the negative baseline`() {
        val first = AimiOverlayRules.stepsToday(raw = 150, day = 2026330, savedDay = 2026330, savedBaseline = 12_000, savedLastRaw = 15_000, savedSteps = 3_000)
        val next = AimiOverlayRules.stepsToday(raw = 400, day = 2026330, savedDay = 2026330, savedBaseline = first.baseline, savedLastRaw = 150, savedSteps = first.steps)
        assertThat(next.steps).isEqualTo(3_400)
    }

    // ---------- basal pill ----------

    @Test
    fun `unknown basal`() {
        assertThat(AimiOverlayRules.basalState(null, 0.5)).isEqualTo(BasalState.UNKNOWN)
    }

    @Test
    fun `zero basal is a pause whatever the profile says`() {
        assertThat(AimiOverlayRules.basalState(0.0, 0.5)).isEqualTo(BasalState.PAUSED)
        assertThat(AimiOverlayRules.basalState(0.0, null)).isEqualTo(BasalState.PAUSED)
    }

    @Test
    fun `basal compared with the profile`() {
        assertThat(AimiOverlayRules.basalState(0.3, 0.55)).isEqualTo(BasalState.BELOW_PROFILE)
        assertThat(AimiOverlayRules.basalState(1.2, 0.55)).isEqualTo(BasalState.ABOVE_PROFILE)
        assertThat(AimiOverlayRules.basalState(0.55, 0.55)).isEqualTo(BasalState.AS_PROFILE)
    }

    @Test
    fun `rates closer than the tolerance count as the profile`() {
        assertThat(AimiOverlayRules.basalState(0.552, 0.55)).isEqualTo(BasalState.AS_PROFILE)
        assertThat(AimiOverlayRules.basalState(0.548, 0.55)).isEqualTo(BasalState.AS_PROFILE)
    }

    @Test
    fun `unknown profile is no reason for an arrow`() {
        assertThat(AimiOverlayRules.basalState(0.8, null)).isEqualTo(BasalState.AS_PROFILE)
    }

    @Test
    fun `rate drops trailing zeros`() {
        assertThat(AimiOverlayRules.rate(0.55, Locale.US)).isEqualTo("0.55")
        assertThat(AimiOverlayRules.rate(0.3, Locale.US)).isEqualTo("0.3")
        assertThat(AimiOverlayRules.rate(1.2, Locale.US)).isEqualTo("1.2")
        assertThat(AimiOverlayRules.rate(1.0, Locale.US)).isEqualTo("1")
        assertThat(AimiOverlayRules.rate(10.0, Locale.US)).isEqualTo("10")
    }

    @Test
    fun `rate uses the decimal comma of the locale`() {
        assertThat(AimiOverlayRules.rate(0.55, Locale.GERMANY)).isEqualTo("0,55")
        assertThat(AimiOverlayRules.rate(1.0, Locale.GERMANY)).isEqualTo("1")
    }

    // ---------- IOB pill ----------

    @Test
    fun `iob text has one decimal`() {
        assertThat(AimiOverlayRules.iobText(1.24, Locale.US)).isEqualTo("1.2 U")
        assertThat(AimiOverlayRules.iobText(-0.4, Locale.US)).isEqualTo("-0.4 U")
    }

    @Test
    fun `a tiny negative iob shows as zero and is not blue`() {
        assertThat(AimiOverlayRules.iobText(-0.04, Locale.US)).isEqualTo("0.0 U")
        assertThat(AimiOverlayRules.iobNegative(-0.04)).isFalse()
    }

    @Test
    fun `iob is negative from minus 0_1`() {
        assertThat(AimiOverlayRules.iobNegative(-0.1)).isTrue()
        assertThat(AimiOverlayRules.iobNegative(0.3)).isFalse()
    }

    // ---------- trend arrow ----------

    @Test
    fun `trend symbols map to angles`() {
        assertThat(AimiOverlayRules.arrowAngle("→")).isEqualTo(0f)
        assertThat(AimiOverlayRules.arrowAngle("↗")).isEqualTo(-45f)
        assertThat(AimiOverlayRules.arrowAngle("↑")).isEqualTo(-90f)
        assertThat(AimiOverlayRules.arrowAngle("⇈")).isEqualTo(-90f)
        assertThat(AimiOverlayRules.arrowAngle("↘")).isEqualTo(45f)
        assertThat(AimiOverlayRules.arrowAngle("↓")).isEqualTo(90f)
        assertThat(AimiOverlayRules.arrowAngle("⇊")).isEqualTo(90f)
    }

    @Test
    fun `unknown trend symbol has no arrow`() {
        assertThat(AimiOverlayRules.arrowAngle("??")).isNull()
        assertThat(AimiOverlayRules.arrowAngle("")).isNull()
    }
}
