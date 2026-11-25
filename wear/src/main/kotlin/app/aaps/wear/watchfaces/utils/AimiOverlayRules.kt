package app.aaps.wear.watchfaces.utils

import app.aaps.core.interfaces.rx.weardata.EventData
import java.util.Locale

/**
 * The decisions of the AIMI overlay that need no Android: which warning is shown, when it counts as
 * acknowledged, what the day's steps are and how the basal pill looks. Kept apart from [AimiOverlayView] so they
 * can be tested without a canvas.
 */
internal object AimiOverlayRules {

    /** The glucose below which the insulin-only curve counts as a low, the same line the phone uses. */
    const val LOW_LINE = 70.0

    /** Rates closer than this to the profile's rate (U/h) count as equal. */
    const val BASAL_EPS = 0.005

    /** Anything at or below this is the "not known" marker of the AIMI data (-999). */
    const val UNKNOWN_LIMIT = -900.0

    /** Two taps within this many milliseconds are a double tap, as on the rest of the watch face. */
    const val DOUBLE_TAP_MS = 800L

    /** The preferences file the step tracker writes and the overlay reads. */
    const val PREFS = "aimi_overlay"
    const val KEY_DAY = "steps_day"
    const val KEY_BASELINE = "steps_baseline"
    const val KEY_LAST_RAW = "steps_last_raw"
    const val KEY_STEPS_TODAY = "steps_today"

    /** Marks "no baseline yet" in the preferences: a baseline can be negative after a restart of the watch. */
    const val NO_BASELINE = Long.MIN_VALUE

    /** No reading for this long counts as old: one missed reading (they come every 5 minutes) plus some slack. */
    const val STALE_MS = 7 * 60_000L

    /**
     * Whether the picture is old news. The age of the last reading decides; the link only matters while there has been
     * no reading at all. A cloud node (the watch is online, the phone may not be) is no sign of a working phone, so only
     * [nearbyLinked] counts, and with a fresh reading even that is not needed (the data came another way).
     */
    fun offline(nearbyLinked: Boolean, lastBgTime: Long, now: Long): Boolean =
        if (lastBgTime > 0L) now - lastBgTime > STALE_MS else !nearbyLinked

    /**
     * A low is expected. With a lead time of 60 minutes that is the phone's flag; with a shorter one the insulin-only
     * curve is checked here, which the phone sends only while its own flag is set. Never when the warning is off.
     */
    fun lowWarningActive(
        enabled: Boolean,
        leadMinutes: Int,
        lowRisk: Boolean,
        insulinOnly: List<EventData.TreatmentData.AimiPoint>,
        lastBgTime: Long
    ): Boolean {
        if (!enabled) return false
        if (leadMinutes >= 60) return lowRisk
        val until = lastBgTime + leadMinutes * 60_000L
        return insulinOnly.any { it.timeStamp <= until && it.value < LOW_LINE }
    }

    /**
     * Whether an acknowledgement given at [ackAt] still holds. 0 means none was given; [ackMinutes] of 0 means it holds
     * until the warning has been gone once (that part is the caller's, see the view).
     */
    fun acknowledged(ackAt: Long, ackMinutes: Int, now: Long): Boolean {
        if (ackAt <= 0L) return false
        return ackMinutes == 0 || now - ackAt < ackMinutes * 60_000L
    }

    /** The warning is shown when it is active and either the reading is already low or it was not acknowledged. */
    fun warningShown(active: Boolean, realLow: Boolean, acknowledged: Boolean): Boolean = active && (realLow || !acknowledged)

    fun isDoubleTap(previousTap: Long, now: Long): Boolean = previousTap > 0L && now - previousTap <= DOUBLE_TAP_MS

    /** The result of [stepsToday]: the day's steps and the count they are measured from. */
    data class Steps(val steps: Long, val baseline: Long)

    /**
     * The step counter counts since the watch started. The day's steps are the count minus the first one seen on the
     * day. A new day starts again from 0. A count that fell means the watch restarted: the steps so far
     * ([savedSteps]) are kept and the new count is added to them, so the baseline turns negative.
     */
    fun stepsToday(raw: Long, day: Int, savedDay: Int, savedBaseline: Long, savedLastRaw: Long, savedSteps: Long): Steps = when {
        savedDay != day || savedBaseline == NO_BASELINE -> Steps(0L, raw)
        raw < savedLastRaw                              -> Steps(raw + savedSteps, -savedSteps)
        else                                            -> Steps(raw - savedBaseline, savedBaseline)
    }

    enum class BasalState { UNKNOWN, PAUSED, BELOW_PROFILE, ABOVE_PROFILE, AS_PROFILE }

    /** How the basal rate running now compares with the profile's; either may be unknown (null). */
    fun basalState(now: Double?, profile: Double?): BasalState = when {
        now == null                                  -> BasalState.UNKNOWN
        now < BASAL_EPS                              -> BasalState.PAUSED
        profile != null && now < profile - BASAL_EPS -> BasalState.BELOW_PROFILE
        profile != null && now > profile + BASAL_EPS -> BasalState.ABOVE_PROFILE
        else                                         -> BasalState.AS_PROFILE
    }

    /** The IOB as shown, one decimal. A tiny negative value that rounds to zero shows as 0.0, not as -0.0. */
    fun iobText(iob: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f U", if (Math.round(iob * 10.0) == 0L) 0.0 else iob)

    /** Whether the IOB shows as negative (blue): judged on the rounded value, like the text. */
    fun iobNegative(iob: Double): Boolean = Math.round(iob * 10.0) < 0L

    /** Up to two decimals without trailing zeros: 0.55, 0.3, 1.2, 10. */
    fun rate(value: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.2f", value).trimEnd('0').trimEnd('.', ',')

    /** The angle of an AAPS trend symbol, 0 pointing right and growing clockwise; null for none or unknown. */
    fun arrowAngle(symbol: String): Float? = when (symbol) {
        "⇈", "↑" -> -90f
        "↗"           -> -45f
        "→"           -> 0f
        "↘"           -> 45f
        "↓", "⇊" -> 90f
        else               -> null
    }
}
