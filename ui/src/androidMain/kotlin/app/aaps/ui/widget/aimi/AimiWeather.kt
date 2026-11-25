package app.aaps.ui.widget.aimi

import android.content.Context
import android.location.Location
import android.location.LocationManager
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The weather for the AIMI watch face: now and today's low and high, in degrees Celsius. */
data class AimiWeatherData(val nowC: Double, val lowC: Double, val highC: Double)

/**
 * Weather for the place of the phone, from Open-Meteo (free, no account, no key).
 *
 * Privacy: only the rounded coordinates (about 1 km) leave the phone, nothing else identifies it. The place
 * is the phone's last known location; nothing here asks for new fixes, so it costs no battery. The location
 * permission is the one automation already has. The setting "Weather on the AIMI watch face" in the Wear
 * preferences switches it off. Without a place (or one older than [MAX_FIX_AGE_MS]) or without network the last
 * value is kept for [MAX_AGE_MS] and then dropped.
 *
 * [current] never waits for the network: it returns what is cached and starts a refresh in the background when
 * the cache is older than [TTL_MS]. The watch gets the new value with the next data it is sent.
 */
@SingleIn(AppScope::class)
@Inject
class AimiWeather(private val context: Context, private val preferences: Preferences) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)

    /** The last answer and when it arrived, in one object so a reader never sees the value of one and the time of another. */
    private class Snapshot(val data: AimiWeatherData, val at: Long)

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var triedAt = 0L

    fun current(now: Long = System.currentTimeMillis()): AimiWeatherData? {
        // Switched off: nothing is requested and nothing is kept.
        if (!preferences.get(BooleanKey.WearAimiWeather)) {
            snapshot = null
            return null
        }
        val last = snapshot
        val stale = (last == null || now - last.at > TTL_MS) && now - triedAt > RETRY_MS
        if (stale && refreshing.compareAndSet(false, true)) {
            triedAt = now
            scope.launch {
                try {
                    fetch()?.let { snapshot = Snapshot(it, System.currentTimeMillis()) }
                } catch (_: Exception) {
                    // Keep the last value; the next call retries after RETRY_MS.
                } finally {
                    refreshing.set(false)
                }
            }
        }
        return snapshot?.takeIf { now - it.at <= MAX_AGE_MS }?.data
    }

    private fun fetch(): AimiWeatherData? {
        val place = lastKnownLocation() ?: return null
        // About 1 km: all the weather needs, and no exact position leaves the phone.
        val lat = String.format(Locale.US, "%.2f", place.latitude)
        val lon = String.format(Locale.US, "%.2f", place.longitude)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "AAPS-AIMI-watchface")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val now = json.getJSONObject("current").getDouble("temperature_2m")
            val daily = json.getJSONObject("daily")
            val high = daily.getJSONArray("temperature_2m_max").getDouble(0)
            val low = daily.getJSONArray("temperature_2m_min").getDouble(0)
            return AimiWeatherData(now, low, high)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The newest fix of any provider. Null without the permission, without a fix, or when the newest one is older than
     * [MAX_FIX_AGE_MS]: the weather of a place the phone left long ago is worse than none.
     */
    private fun lastKnownLocation(): Location? = try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        manager?.getProviders(true)
            ?.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            ?.maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time <= MAX_FIX_AGE_MS }
    } catch (_: SecurityException) {
        null
    }

    companion object {

        const val TTL_MS = 30 * 60_000L
        const val RETRY_MS = 5 * 60_000L
        const val MAX_AGE_MS = 3 * 60 * 60_000L
        const val MAX_FIX_AGE_MS = 12 * 60 * 60_000L
        private const val TIMEOUT_MS = 8_000
    }
}
