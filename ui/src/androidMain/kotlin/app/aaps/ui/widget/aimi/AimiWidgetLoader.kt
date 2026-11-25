package app.aaps.ui.widget.aimi

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.ui.compose.dashboard.DisplayForecast
import app.aaps.ui.widget.state.WidgetStateBuilder
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException

/**
 * Reads the data for the AIMI widget. Each optional part is read on its own and left out when it
 * fails, so one broken source never blanks the whole widget. The widget runs inside the loop's process.
 */
@Inject
class AimiWidgetLoader(
    private val widgetStateBuilder: WidgetStateBuilder,
    // A provider, like BgGraphStateLoader: the cache must not be built before the app is initialised.
    private val cacheProvider: () -> OverviewDataCache,
    private val persistenceLayer: PersistenceLayer,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val iobCobCalculator: IobCobCalculator,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val weather: AimiWeather
) {

    private inline fun <T> safe(block: () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    suspend fun load(appWidgetId: Int): AimiWidgetData {
        val state = widgetStateBuilder.build()
        val now = dateUtil.now()
        val units = state.units
        val stale = state.isStaleAt(now)

        val lowMark = preferences.get(UnitDoubleKey.OverviewLowMark)
        val highMark = preferences.get(UnitDoubleKey.OverviewHighMark)

        val cache = cacheProvider()
        val history = safe {
            cache.bucketedDataFlow.value
                .filter { it.type == BgType.BUCKETED && it.timestamp in (now - HISTORY_MS)..now }
                .sortedBy { it.timestamp }
        } ?: emptyList()

        // The forecast is built from the measured readings, not from a loop curve (see DisplayForecast). The
        // loop curves only give the insulin-only curve for the low warning.
        val forecast = if (stale) null else safe {
            DisplayForecast.build(history, cache.predictionsFlow.value, now) { profileUtil.fromMgdlToUnits(it, units) }
        }
        val prediction30 = forecast?.takeIf { it.trendKnown }?.let { it.at(it.centre, DisplayForecast.HEADLINE_MINUTES)?.value }

        val tir = safe { timeInRange(now, units, lowMark, highMark) }

        return AimiWidgetData(
            now = now,
            cardAlpha = safe { preferences.get(IntComposedKey.WidgetOpacity, appWidgetId) }?.coerceIn(0, 255) ?: DEFAULT_ALPHA,
            units = units,
            bgText = state.bgText,
            bgClass = state.bgColorClass(now),
            trend = state.trendArrow,
            deltaText = state.deltaText,
            timeAgoText = state.timeAgoText,
            stale = stale,
            runningModeText = state.runningModeText,
            runningModeActive = state.runningModeActive,
            currentValue = state.bgMgdl?.let { profileUtil.fromMgdlToUnits(it, units) },
            history = history,
            historyMs = HISTORY_MS,
            lowMark = lowMark,
            highMark = highMark,
            forecast = forecast,
            prediction30 = prediction30,
            tirPercent = tir?.inRange,
            tir = tir,
            iob = state.iob,
            basalNow = safe { basalNow(now) },
            profileBasal = safe { profileBasal(now) },
            isfText = state.isfFromText.takeIf { it.isNotEmpty() }?.let { "$it→${state.isfToText}" },
            lastSmb = safe { lastSmb(now) },
            pod = safe { pod(now) },
            sensor = safe { sensor(now) },
            profileText = state.profileText
        )
    }

    /**
     * The same forecast, time in range, pod and sensor as [load], for the AIMI watch face, glucose in mg/dL.
     * Each part is left out when it cannot be read, like in [load].
     */
    suspend fun loadWatchExtras(): EventData.TreatmentData.Aimi? {
        val now = dateUtil.now()
        val state = safe { widgetStateBuilder.build() } ?: return null
        val units = state.units
        val cache = cacheProvider()
        val history = safe {
            cache.bucketedDataFlow.value
                .filter { it.type == BgType.BUCKETED && it.timestamp in (now - HISTORY_MS)..now }
                .sortedBy { it.timestamp }
        } ?: emptyList()
        val forecast = safe {
            DisplayForecast.build(history, cache.predictionsFlow.value, now) { profileUtil.fromMgdlToUnits(it, units) }
        }
        // The forecast is built in the display unit; the watch gets mg/dL.
        fun points(list: List<app.aaps.ui.compose.dashboard.ForecastPoint>) =
            ArrayList(list.map { EventData.TreatmentData.AimiPoint(it.timeMs, profileUtil.convertToMgdl(it.value, units)) })

        val lowMark = preferences.get(UnitDoubleKey.OverviewLowMark)
        val highMark = preferences.get(UnitDoubleKey.OverviewHighMark)
        val tir = safe { timeInRange(now, units, lowMark, highMark) }
        val pod = safe { pod(now) }
        val sensor = safe { sensor(now) }
        val wx = safe { weather.current(now) }
        val iob = state.iob.takeIf { it.isFinite() }
        val basalNow = safe { basalNow(now) }
        val profileBasal = safe { profileBasal(now) }
        return EventData.TreatmentData.Aimi(
            centre = forecast?.let { points(it.centre) } ?: ArrayList(),
            low = forecast?.let { points(it.low) } ?: ArrayList(),
            high = forecast?.let { points(it.high) } ?: ArrayList(),
            insulinOnly = forecast?.let { points(it.insulinOnly) } ?: ArrayList(),
            lowRisk = forecast?.lowRisk ?: false,
            tirLow = tir?.low ?: -1,
            tirIn = tir?.inRange ?: -1,
            tirHigh = tir?.high ?: -1,
            podFraction = pod?.fractionLeft ?: -1f,
            podLevel = pod?.level?.ordinal ?: 0,
            sensorFraction = sensor?.fractionLeft ?: -1f,
            sensorLevel = sensor?.level?.ordinal ?: 0,
            lowMarkMgdl = profileUtil.convertToMgdl(lowMark, units),
            highMarkMgdl = profileUtil.convertToMgdl(highMark, units),
            weatherNowC = wx?.nowC ?: EventData.TreatmentData.Aimi.UNKNOWN,
            weatherLowC = wx?.lowC ?: EventData.TreatmentData.Aimi.UNKNOWN,
            weatherHighC = wx?.highC ?: EventData.TreatmentData.Aimi.UNKNOWN,
            iobU = iob ?: EventData.TreatmentData.Aimi.UNKNOWN,
            basalNowU = basalNow ?: EventData.TreatmentData.Aimi.UNKNOWN,
            basalProfileU = profileBasal ?: EventData.TreatmentData.Aimi.UNKNOWN,
        )
    }

    private suspend fun timeInRange(now: Long, units: GlucoseUnit, low: Double, high: Double): AimiWidgetTir? {
        val lowMgdl = profileUtil.convertToMgdl(low, units)
        val highMgdl = profileUtil.convertToMgdl(high, units)
        // Today from midnight, like the dashboard. Early in the day it rests on too few readings, so the
        // dashboard waits [TIR_DAY_READY_MS] and so does this: before that there is no value.
        val dayStart = dateUtil.beginOfDay(now)
        if (now - dayStart < TIR_DAY_READY_MS) return null
        val values = persistenceLayer.getBgReadingsDataFromTimeToTime(dayStart, now, true).filter { it.isValid }
        if (values.size < MIN_TIR_READINGS) return null
        return AimiWidgetRules.timeInRange(values.map { it.value }, lowMgdl, highMgdl)
    }

    private suspend fun profileBasal(now: Long): Double? {
        val profile = profileFunction.getProfile() ?: return null
        return iobCobCalculator.getBasalData(profile, now).basal
    }

    private suspend fun basalNow(now: Long): Double? {
        val profile = profileFunction.getProfile() ?: return null
        val data = iobCobCalculator.getBasalData(profile, now)
        return if (data.isTempBasalRunning) data.tempBasalAbsolute else data.basal
    }

    private suspend fun lastSmb(now: Long): Double? =
        // The newest by time, not "the first one": the order of this list is not what the flag suggests.
        persistenceLayer.getBolusesFromTime(now - DAY_MS, false)
            .filter { it.isValid && it.type == BS.Type.SMB }
            .maxByOrNull { it.timestamp }
            ?.amount

    private suspend fun pod(now: Long): AimiWidgetPod? {
        val event = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE) ?: return null
        val pump = activePlugin.activePump
        val description = pump.pumpDescription
        val totalHours = preferences.get(IntKey.OverviewCageCritical).toDouble().coerceAtLeast(1.0)
        val ageHours = (now - event.timestamp) / HOUR_MS.toDouble()
        // Omnipod keeps running 8 hours after the planned change time (80 h in all, see PodConstants.MAX_POD_LIFETIME).
        // Once that time is used up the bar counts the grace period down. No other pump has a grace period here.
        val graceHours = AimiWidgetRules.podGraceHours(pump.model())
        val time = AimiWidgetRules.lifeTime(ageHours, totalHours, graceHours)

        val reservoir = profileFunction.getProfile()?.let { pump.reservoirLevel.value?.iU(it.insulinConcentration()) }
        val shown = reservoir?.takeIf { it > 0 }
        val capped = shown != null && description.isPatchPump && shown >= description.maxReservoirReading
        // Omnipod and EOPatch only report an exact amount below their cap of 50 U, so that is where they turn yellow.
        val warnBelow = AimiWidgetRules.reservoirWarnBelowU(description.isPatchPump, description.maxReservoirReading, preferences.get(IntKey.OverviewResWarning))
        val reservoirLevel = when {
            // "over 75 U" is a cap of the pump, not the real level: never a warning.
            shown == null || capped                                -> AimiWidgetLevel.NORMAL
            shown <= preferences.get(IntKey.OverviewResCritical)  -> AimiWidgetLevel.CRIT
            shown < warnBelow                                      -> AimiWidgetLevel.WARN
            else                                                   -> AimiWidgetLevel.NORMAL
        }
        // Same thresholds as the status lights of the overview: the settings "warning" and "critical" (hours of age).
        val level = AimiWidgetRules.ageLevel(ageHours, preferences.get(IntKey.OverviewCageWarning), preferences.get(IntKey.OverviewCageCritical))
        return AimiWidgetPod(description.isPatchPump, time.left, time.fraction, level, shown, capped, reservoirLevel, time.graceLeft)
    }

    private suspend fun sensor(now: Long): AimiWidgetSensor? {
        val event = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE) ?: return null
        val totalHours = preferences.get(IntKey.OverviewSageCritical).toDouble().coerceAtLeast(1.0)
        val ageHours = (now - event.timestamp) / HOUR_MS.toDouble()
        // A G7 or ONE+ sensor keeps working 12 hours after its planned end (DexcomOnePlusStaging.SENSOR_GRACE_MS).
        // The sensor type comes from the last reading; any other or unknown sensor has no grace period here.
        val source = safe { persistenceLayer.getLastGlucoseValue()?.sourceSensor }
        val time = AimiWidgetRules.lifeTime(ageHours, totalHours, AimiWidgetRules.sensorGraceHours(source))
        val level = AimiWidgetRules.ageLevel(ageHours, preferences.get(IntKey.OverviewSageWarning), preferences.get(IntKey.OverviewSageCritical))
        return AimiWidgetSensor(time.left, time.fraction, level, time.graceLeft)
    }

    private companion object {

        const val HOUR_MS = 60L * 60 * 1000
        const val DAY_MS = 24 * HOUR_MS
        const val HISTORY_MS = 6 * HOUR_MS
        const val MIN_TIR_READINGS = 20
        /** The dashboard shows the time in range of the day only after 6 hours of it. */
        const val TIR_DAY_READY_MS = 6 * HOUR_MS
        const val DEFAULT_ALPHA = 150
    }
}
