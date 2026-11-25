package app.aaps.wear.wearStepCount

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import app.aaps.wear.watchfaces.utils.AimiOverlayRules
import java.util.Calendar

/**
 * Keeps the day's step total for the AIMI watch face, whether the face is on screen or not.
 *
 * The step counter counts since the watch started, so the total of a day needs the count at the start of it. A face
 * that only listens while it is shown would miss every step taken with the screen off. This listens all day instead,
 * in batches: the sensor hardware collects the steps and hands them over every few minutes, which costs next to no
 * power. It runs in the always-on service of the app.
 *
 * It writes the total into the preferences the overlay reads ([AimiOverlayRules.KEY_STEPS_TODAY]). Without the
 * activity recognition permission it does nothing.
 */
class DailyStepsTracker(private val context: Context) : SensorEventListener {

    private val prefs = context.getSharedPreferences(AimiOverlayRules.PREFS, Context.MODE_PRIVATE)
    private var running = false

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        running = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_LATENCY_US)
    }

    fun stop() {
        if (!running) return
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val raw = event.values.firstOrNull() ?: return
        record(raw.toLong())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Works the day's total out of the raw count and writes it down together with what the next reading needs. */
    @Synchronized
    private fun record(raw: Long) {
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val result = AimiOverlayRules.stepsToday(
            raw, day,
            savedDay = prefs.getInt(AimiOverlayRules.KEY_DAY, 0),
            savedBaseline = prefs.getLong(AimiOverlayRules.KEY_BASELINE, AimiOverlayRules.NO_BASELINE),
            savedLastRaw = prefs.getLong(AimiOverlayRules.KEY_LAST_RAW, -1L),
            savedSteps = prefs.getLong(AimiOverlayRules.KEY_STEPS_TODAY, 0L)
        )
        prefs.edit()
            .putInt(AimiOverlayRules.KEY_DAY, day)
            .putLong(AimiOverlayRules.KEY_BASELINE, result.baseline)
            .putLong(AimiOverlayRules.KEY_LAST_RAW, raw)
            .putLong(AimiOverlayRules.KEY_STEPS_TODAY, result.steps)
            .apply()
    }

    private companion object {

        /** The sensor may keep steps this long before it delivers them: 5 minutes. */
        const val BATCH_LATENCY_US = 5 * 60 * 1_000_000
    }
}
